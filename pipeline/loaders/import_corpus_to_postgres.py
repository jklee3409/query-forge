from __future__ import annotations

import argparse
import json
import traceback
from pathlib import Path
from typing import Any, Callable

try:
    from loaders.common import (
        ImportOptions,
        RunRecorder,
        build_options,
        config_snapshot_json,
        configure_logging,
        connect,
        default_database_args,
        filter_rows,
        load_jsonl,
        load_raw_documents,
        source_scope_json,
    )
    from loaders.import_chunks import import_chunks
    from loaders.import_documents import import_documents
    from loaders.import_glossary import import_glossary
    from loaders.import_relations import import_relations
except ModuleNotFoundError:  # pragma: no cover - direct module execution fallback
    from pipeline.loaders.common import (
        ImportOptions,
        RunRecorder,
        build_options,
        config_snapshot_json,
        configure_logging,
        connect,
        default_database_args,
        filter_rows,
        load_jsonl,
        load_raw_documents,
        source_scope_json,
    )
    from pipeline.loaders.import_chunks import import_chunks
    from pipeline.loaders.import_documents import import_documents
    from pipeline.loaders.import_glossary import import_glossary
    from pipeline.loaders.import_relations import import_relations


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Import normalized corpus, chunks, relations, and glossary artifacts into PostgreSQL."
    )
    db_defaults = default_database_args()
    parser.add_argument("--database-url", default=db_defaults["database_url"])
    parser.add_argument("--db-host", default=db_defaults["db_host"])
    parser.add_argument("--db-port", type=int, default=db_defaults["db_port"])
    parser.add_argument("--db-name", default=db_defaults["db_name"])
    parser.add_argument("--db-user", default=db_defaults["db_user"])
    parser.add_argument("--db-password", default=db_defaults["db_password"])
    parser.add_argument("--source-config-dir", default="configs/app/sources")
    parser.add_argument("--raw-input", default="data/raw/spring_docs_raw.jsonl")
    parser.add_argument("--sections-input", default="data/processed/spring_docs_sections.jsonl")
    parser.add_argument("--chunks-input", default="data/processed/chunks.jsonl")
    parser.add_argument("--glossary-input", default="data/processed/glossary_terms.jsonl")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--batch-size", type=int, default=200)
    parser.add_argument("--trigger-type", default="manual", choices=["manual", "scheduled", "api"])
    parser.add_argument("--created-by", default=None)
    parser.add_argument("--run-type", default="import", choices=["import", "full_ingest"])
    parser.add_argument("--external-run-id", default=None)
    parser.add_argument("--source-id", action="append", default=None)
    parser.add_argument("--document-id", action="append", default=None)
    parser.add_argument("--log-level", default="INFO")
    return parser


def flatten_summary(step_summaries: dict[str, Any]) -> dict[str, Any]:
    docs = step_summaries["import_docs"]["documents"]
    sections = step_summaries["import_docs"]["sections"]
    chunks = step_summaries["import_chunks"]
    relations = step_summaries["import_relations"]
    glossary = step_summaries["import_glossary"]["terms"]
    return {
        "docs_inserted": docs["inserted"],
        "docs_updated": docs["updated"],
        "docs_skipped": docs["skipped"],
        "sections_inserted": sections["inserted"],
        "sections_updated": sections["updated"],
        "sections_skipped": sections["skipped"],
        "chunks_inserted": chunks["inserted"],
        "chunks_updated": chunks["updated"],
        "chunks_skipped": chunks["skipped"],
        "relations_inserted": relations["inserted"],
        "relations_updated": relations["updated"],
        "relations_skipped": relations["skipped"],
        "glossary_terms_inserted": glossary["inserted"],
        "glossary_terms_updated": glossary["updated"],
        "glossary_terms_skipped": glossary["skipped"],
    }


def resolve_domain_scope_ids(options: ImportOptions) -> tuple[list[str], list[str], list[str]]:
    raw_documents = load_raw_documents(options.raw_input_path)
    section_rows = filter_rows(
        load_jsonl(options.sections_input_path),
        source_ids=options.source_ids,
        document_ids=options.document_ids,
        raw_documents=raw_documents,
    )
    document_ids = sorted({str(row["document_id"]) for row in section_rows})
    source_ids = sorted(
        {
            str(raw_documents.get(document_id, {}).get("source_id") or "")
            for document_id in document_ids
        }
        | set(options.source_ids)
    )
    source_ids = [source_id for source_id in source_ids if source_id]
    chunk_rows = filter_rows(
        load_jsonl(options.chunks_input_path),
        source_ids=options.source_ids,
        document_ids=set(document_ids),
        raw_documents=raw_documents,
    )
    chunk_ids = sorted({str(row["chunk_id"]) for row in chunk_rows})
    return document_ids, chunk_ids, source_ids


def apply_domain_scope(connection: Any, options: ImportOptions, run_id: str) -> None:
    if not options.domain_id:
        return
    document_ids, chunk_ids, source_ids = resolve_domain_scope_ids(options)
    with connection.cursor() as cursor:
        cursor.execute(
            """
            UPDATE corpus_runs
            SET domain_id = %s,
                updated_at = NOW()
            WHERE run_id = %s
            """,
            (options.domain_id, run_id),
        )
        if source_ids:
            cursor.execute(
                """
                INSERT INTO tech_doc_domain_source (domain_id, source_id, source_role, active)
                SELECT %s, source_id, 'primary', TRUE
                FROM UNNEST(%s::text[]) AS source_ids(source_id)
                ON CONFLICT (source_id) DO UPDATE
                SET domain_id = EXCLUDED.domain_id,
                    source_role = EXCLUDED.source_role,
                    active = EXCLUDED.active
                """,
                (options.domain_id, source_ids),
            )
            cursor.execute(
                """
                UPDATE corpus_sources
                SET domain_id = %s,
                    updated_at = NOW()
                WHERE source_id = ANY(%s)
                """,
                (options.domain_id, source_ids),
            )
        if document_ids:
            cursor.execute(
                """
                UPDATE corpus_documents
                SET domain_id = %s,
                    updated_at = NOW()
                WHERE import_run_id = %s
                   OR document_id = ANY(%s)
                """,
                (options.domain_id, run_id, document_ids),
            )
            cursor.execute(
                """
                UPDATE corpus_sections
                SET domain_id = %s,
                    updated_at = NOW()
                WHERE import_run_id = %s
                   OR document_id = ANY(%s)
                """,
                (options.domain_id, run_id, document_ids),
            )
            cursor.execute(
                """
                UPDATE corpus_glossary_terms
                SET domain_id = %s,
                    updated_at = NOW()
                WHERE import_run_id = %s
                   OR first_seen_document_id = ANY(%s)
                """,
                (options.domain_id, run_id, document_ids),
            )
            cursor.execute(
                """
                UPDATE corpus_glossary_evidence
                SET domain_id = %s
                WHERE import_run_id = %s
                   OR document_id = ANY(%s)
                """,
                (options.domain_id, run_id, document_ids),
            )
        if chunk_ids:
            cursor.execute(
                """
                UPDATE corpus_chunks
                SET domain_id = %s,
                    updated_at = NOW()
                WHERE import_run_id = %s
                   OR chunk_id = ANY(%s)
                """,
                (options.domain_id, run_id, chunk_ids),
            )
            cursor.execute(
                """
                UPDATE corpus_chunk_relations
                SET domain_id = %s
                WHERE import_run_id = %s
                   OR source_chunk_id = ANY(%s)
                   OR target_chunk_id = ANY(%s)
                """,
                (options.domain_id, run_id, chunk_ids, chunk_ids),
            )
            cursor.execute(
                """
                UPDATE corpus_glossary_terms
                SET domain_id = %s,
                    updated_at = NOW()
                WHERE first_seen_chunk_id = ANY(%s)
                """,
                (options.domain_id, chunk_ids),
            )
            cursor.execute(
                """
                UPDATE corpus_glossary_evidence
                SET domain_id = %s
                WHERE chunk_id = ANY(%s)
                """,
                (options.domain_id, chunk_ids),
            )
        cursor.execute(
            """
            UPDATE corpus_glossary_aliases a
            SET domain_id = %s
            WHERE import_run_id = %s
               OR EXISTS (
                   SELECT 1
                   FROM corpus_glossary_terms t
                   WHERE t.term_id = a.term_id
                     AND t.domain_id = %s
               )
            """,
            (options.domain_id, run_id, options.domain_id),
        )


def run_import(options: ImportOptions) -> dict[str, Any]:
    meta_connection = None
    recorder = None
    run_id = None
    data_connection = connect(options, autocommit=False)

    try:
        if not options.dry_run and options.external_run_id is None:
            meta_connection = connect(options, autocommit=True)
            recorder = RunRecorder(meta_connection)
            run_id = recorder.create_run(
                run_type=options.run_type,
                trigger_type=options.trigger_type,
                source_scope=source_scope_json(options),
                config_snapshot=config_snapshot_json(options),
                created_by=options.created_by,
                domain_id=options.domain_id,
            )
        elif options.external_run_id is not None:
            run_id = options.external_run_id

        step_summaries: dict[str, Any] = {}

        def execute_step(
            *,
            step_name: str,
            step_order: int,
            input_path: Path | None,
            handler: Callable[[Any], Any],
        ) -> Any:
            step_id = None
            if recorder and run_id:
                step_id = recorder.create_step(
                    run_id=run_id,
                    step_name=step_name,
                    step_order=step_order,
                    input_artifact_path=str(input_path) if input_path else None,
                    output_artifact_path="postgresql://corpus",
                )

            try:
                with data_connection.transaction():
                    result = handler(data_connection)
                normalized = result.to_dict() if hasattr(result, "to_dict") else result
                if recorder and step_id:
                    recorder.finish_step(step_id, status="success", metrics_json=normalized)
                step_summaries[step_name] = normalized
                return normalized
            except Exception as exc:  # noqa: BLE001
                if recorder and step_id:
                    recorder.finish_step(
                        step_id,
                        status="failed",
                        metrics_json={"error_type": type(exc).__name__},
                        error_message=str(exc),
                    )
                raise

        execute_step(
            step_name="import_docs",
            step_order=1,
            input_path=options.sections_input_path,
            handler=lambda connection: import_documents(
                connection,
                options=options,
                import_run_id=run_id,
            ),
        )
        execute_step(
            step_name="import_chunks",
            step_order=2,
            input_path=options.chunks_input_path,
            handler=lambda connection: import_chunks(
                connection,
                options=options,
                import_run_id=run_id or "dry-run",
            ),
        )
        execute_step(
            step_name="import_relations",
            step_order=3,
            input_path=options.chunks_input_path,
            handler=lambda connection: import_relations(
                connection,
                options=options,
                import_run_id=run_id or "dry-run",
            ),
        )
        execute_step(
            step_name="import_glossary",
            step_order=4,
            input_path=options.glossary_input_path,
            handler=lambda connection: import_glossary(
                connection,
                options=options,
                import_run_id=run_id or "dry-run",
            ),
        )

        if not options.dry_run and run_id and options.domain_id:
            with data_connection.transaction():
                apply_domain_scope(data_connection, options, run_id)

        summary = {
            "dry_run": options.dry_run,
            "run_id": run_id,
            "step_summaries": step_summaries,
            **flatten_summary(step_summaries),
        }
        if recorder and run_id:
            recorder.finish_run(run_id, status="success", summary_json=summary)
        return summary
    except Exception as exc:  # noqa: BLE001
        error_summary = {
            "dry_run": options.dry_run,
            "run_id": run_id,
            "error_type": type(exc).__name__,
            "error_message": str(exc),
            "traceback": traceback.format_exc(limit=8),
        }
        if recorder and run_id:
            recorder.finish_run(run_id, status="failed", summary_json=error_summary, error_message=str(exc))
        raise
    finally:
        data_connection.close()
        if meta_connection is not None:
            meta_connection.close()


def main(argv: list[str] | None = None) -> int:
    parser = build_arg_parser()
    args = parser.parse_args(argv)
    configure_logging(args.log_level)
    options = build_options(args)
    summary = run_import(options)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
