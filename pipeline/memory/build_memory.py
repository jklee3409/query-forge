from __future__ import annotations

import logging
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import psycopg
from psycopg.types.json import Jsonb

try:
    from common.embeddings import embed_text, embedding_to_halfvec_literal
    from common.experiment_config import load_experiment_config
    from common.experiment_run import ExperimentRunRecorder
    from loaders.common import connect, default_database_args
except ModuleNotFoundError:  # pragma: no cover - direct module execution fallback
    from pipeline.common.embeddings import embed_text, embedding_to_halfvec_literal
    from pipeline.common.experiment_config import load_experiment_config
    from pipeline.common.experiment_run import ExperimentRunRecorder
    from pipeline.loaders.common import connect, default_database_args


LOGGER = logging.getLogger(__name__)

STAGE_CUTOFF_RULE_ONLY = "rule_only"
STAGE_CUTOFF_RULE_PLUS_LLM = "rule_plus_llm"
STAGE_CUTOFF_UTILITY = "utility"
STAGE_CUTOFF_DIVERSITY = "diversity"
STAGE_CUTOFF_FULL_GATING = "full_gating"


@dataclass(slots=True)
class GatedRow:
    gated_query_id: str
    synthetic_query_id: str
    query_text: str
    query_type: str
    generation_strategy: str
    target_chunk_ids: list[str]
    target_doc_id: str
    chunk_id_source: str
    glossary_terms: list[str]
    llm_scores: dict[str, Any]
    utility_score: float
    novelty_score: float
    final_score: float
    prompt_version: str | None
    prompt_hash: str | None
    product_name: str | None


def _latest_gating_snapshot(
    connection: psycopg.Connection[Any],
    preset: str,
    strategies: list[str] | None = None,
) -> tuple[str | None, str | None]:
    where_strategy_exists = ""
    parameters: list[Any] = [preset]
    normalized = [str(item).upper() for item in (strategies or []) if str(item).strip()]
    if normalized:
        where_strategy_exists = """
          AND EXISTS (
                SELECT 1
                FROM synthetic_query_gating_result gr
                JOIN synthetic_queries_raw_all r
                  ON r.synthetic_query_id = gr.synthetic_query_id
                WHERE gr.gating_batch_id = qb.gating_batch_id
                  AND r.generation_strategy = ANY(%s)
          )
        """
        parameters.append(normalized)
    with connection.cursor() as cursor:
        cursor.execute(
            f"""
            SELECT qb.source_gating_run_id::text AS run_id,
                   qb.gating_batch_id::text AS batch_id
            FROM quality_gating_batch qb
            WHERE qb.gating_preset = %s
              AND qb.status = 'completed'
              {where_strategy_exists}
            ORDER BY qb.finished_at DESC NULLS LAST, qb.created_at DESC
            LIMIT 1
            """,
            parameters,
        )
        row = cursor.fetchone()
    if row is None:
        return None, None
    return (str(row["run_id"]) if row["run_id"] else None, str(row["batch_id"]) if row["batch_id"] else None)


def _resolve_gating_batch_by_run(
    connection: psycopg.Connection[Any],
    *,
    preset: str,
    source_run_id: str | None,
    strategies: list[str] | None = None,
) -> str | None:
    if not source_run_id:
        return None
    where_strategy = ""
    parameters: list[Any] = [preset, source_run_id]
    normalized = [str(item).upper() for item in (strategies or []) if str(item).strip()]
    if normalized:
        where_strategy = """
          AND EXISTS (
                SELECT 1
                FROM synthetic_query_gating_result gr
                JOIN synthetic_queries_raw_all r
                  ON r.synthetic_query_id = gr.synthetic_query_id
                WHERE gr.gating_batch_id = qb.gating_batch_id
                  AND r.generation_strategy = ANY(%s)
          )
        """
        parameters.append(normalized)
    with connection.cursor() as cursor:
        cursor.execute(
            f"""
            SELECT qb.gating_batch_id::text AS batch_id
            FROM quality_gating_batch qb
            WHERE qb.gating_preset = %s
              AND qb.status = 'completed'
              AND qb.source_gating_run_id::text = %s
              {where_strategy}
            ORDER BY qb.finished_at DESC NULLS LAST, qb.created_at DESC
            LIMIT 1
            """,
            parameters,
        )
        row = cursor.fetchone()
    if row is None:
        return None
    batch_id = row["batch_id"]
    return str(batch_id) if batch_id else None


def _resolve_source_run_id_by_batch(
    connection: psycopg.Connection[Any],
    *,
    source_batch_id: str | None,
) -> str | None:
    if not source_batch_id:
        return None
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT source_gating_run_id::text AS source_gating_run_id
            FROM quality_gating_batch
            WHERE gating_batch_id::text = %s
            """,
            (source_batch_id,),
        )
        row = cursor.fetchone()
    if row is None:
        return None
    value = row["source_gating_run_id"]
    return str(value) if value else None


def _resolve_gating_preset_by_batch(
    connection: psycopg.Connection[Any],
    *,
    source_batch_id: str | None,
) -> str | None:
    if not source_batch_id:
        return None
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT gating_preset
            FROM quality_gating_batch
            WHERE gating_batch_id::text = %s
            """,
            (source_batch_id,),
        )
        row = cursor.fetchone()
    if row is None:
        return None
    value = row["gating_preset"]
    return str(value) if value else None


def _load_gated_rows(
    connection: psycopg.Connection[Any],
    *,
    preset: str,
    source_run_id: str | None,
    strategies: list[str] | None = None,
) -> list[GatedRow]:
    where_source = ""
    parameters: list[Any] = [preset]
    if source_run_id:
        where_source = " AND g.metadata ->> 'experiment_run_id' = %s"
        parameters.append(source_run_id)
    where_strategy = ""
    normalized = [str(item).upper() for item in (strategies or []) if str(item).strip()]
    if normalized:
        where_strategy = " AND r.generation_strategy = ANY(%s)"
        parameters.append(normalized)
    with connection.cursor() as cursor:
        cursor.execute(
            f"""
            SELECT g.gated_query_id,
                   g.synthetic_query_id,
                   r.query_text,
                   r.query_type,
                   r.generation_strategy,
                   r.target_chunk_ids,
                   r.target_doc_id,
                   r.chunk_id_source,
                   r.glossary_terms,
                   g.llm_scores,
                   g.utility_score,
                   g.novelty_score,
                   g.final_score,
                   r.prompt_version,
                   r.prompt_hash,
                   sc.product_name
            FROM synthetic_queries_gated g
            JOIN synthetic_queries_raw_all r ON r.synthetic_query_id = g.synthetic_query_id
            JOIN corpus_documents td ON td.document_id = r.target_doc_id
            JOIN corpus_chunks sc ON sc.chunk_id = r.chunk_id_source
            WHERE g.gating_preset = %s
              AND g.final_decision = TRUE
              {where_source}
              {where_strategy}
            ORDER BY g.created_at ASC
            """,
            parameters,
        )
        rows = cursor.fetchall()
    return [
        GatedRow(
            gated_query_id=str(row["gated_query_id"]),
            synthetic_query_id=str(row["synthetic_query_id"]),
            query_text=str(row["query_text"]),
            query_type=str(row["query_type"]),
            generation_strategy=str(row["generation_strategy"]),
            target_chunk_ids=list(row["target_chunk_ids"] or []),
            target_doc_id=str(row["target_doc_id"]),
            chunk_id_source=str(row["chunk_id_source"]),
            glossary_terms=list(row["glossary_terms"] or []),
            llm_scores=dict(row["llm_scores"] or {}),
            utility_score=float(row["utility_score"] or 0.0),
            novelty_score=float(row["novelty_score"] or 0.0),
            final_score=float(row["final_score"] or 0.0),
            prompt_version=row["prompt_version"],
            prompt_hash=row["prompt_hash"],
            product_name=row["product_name"],
        )
        for row in rows
    ]


def _count_batch_accepted_queries(
    connection: psycopg.Connection[Any],
    *,
    source_batch_id: str,
    strategies: list[str] | None = None,
) -> int:
    where_strategy = ""
    parameters: list[Any] = [source_batch_id]
    normalized = [str(item).upper() for item in (strategies or []) if str(item).strip()]
    if normalized:
        where_strategy = " AND r.generation_strategy = ANY(%s)"
        parameters.append(normalized)
    with connection.cursor() as cursor:
        cursor.execute(
            f"""
            SELECT COUNT(*) AS accepted_count
            FROM synthetic_query_gating_result gr
            JOIN synthetic_queries_raw_all r ON r.synthetic_query_id = gr.synthetic_query_id
            JOIN corpus_documents td ON td.document_id = r.target_doc_id
            JOIN corpus_chunks sc ON sc.chunk_id = r.chunk_id_source
            WHERE gr.gating_batch_id::text = %s
              AND COALESCE(gr.accepted, FALSE)
              {where_strategy}
            """,
            parameters,
        )
        row = cursor.fetchone()
    if row is None:
        return 0
    return int(row["accepted_count"] or 0)


def _load_gated_rows_by_batch(
    connection: psycopg.Connection[Any],
    *,
    preset: str,
    source_batch_id: str,
    strategies: list[str] | None = None,
) -> list[GatedRow]:
    batch_preset = _resolve_gating_preset_by_batch(connection, source_batch_id=source_batch_id)
    normalized_preset = preset.strip().lower()
    if batch_preset and batch_preset.strip().lower() != normalized_preset:
        raise RuntimeError(
            "Gating snapshot preset mismatch for memory build: "
            f"batch_id={source_batch_id}, expected_preset={preset}, actual_preset={batch_preset}"
        )
    expected_count = _count_batch_accepted_queries(
        connection,
        source_batch_id=source_batch_id,
        strategies=strategies,
    )
    where_strategy = ""
    parameters: list[Any] = [preset, source_batch_id]
    normalized = [str(item).upper() for item in (strategies or []) if str(item).strip()]
    if normalized:
        where_strategy = " AND r.generation_strategy = ANY(%s)"
        parameters.append(normalized)
    with connection.cursor() as cursor:
        cursor.execute(
            f"""
            SELECT g.gated_query_id,
                   gr.synthetic_query_id,
                   r.query_text,
                   r.query_type,
                   r.generation_strategy,
                   r.target_chunk_ids,
                   r.target_doc_id,
                   r.chunk_id_source,
                   r.glossary_terms,
                   COALESCE(gr.llm_scores, g.llm_scores) AS llm_scores,
                   gr.utility_score,
                   gr.novelty_score,
                   gr.final_score,
                   r.prompt_version,
                   r.prompt_hash,
                   sc.product_name
            FROM synthetic_query_gating_result gr
            JOIN synthetic_queries_raw_all r ON r.synthetic_query_id = gr.synthetic_query_id
            JOIN synthetic_queries_gated g
              ON g.synthetic_query_id = gr.synthetic_query_id
             AND g.gating_preset = %s
            JOIN corpus_documents td ON td.document_id = r.target_doc_id
            JOIN corpus_chunks sc ON sc.chunk_id = r.chunk_id_source
            WHERE gr.gating_batch_id::text = %s
              AND COALESCE(gr.accepted, FALSE)
              {where_strategy}
            ORDER BY gr.created_at ASC
            """,
            parameters,
        )
        rows = cursor.fetchall()

    loaded_rows = [
        GatedRow(
            gated_query_id=str(row["gated_query_id"]),
            synthetic_query_id=str(row["synthetic_query_id"]),
            query_text=str(row["query_text"]),
            query_type=str(row["query_type"]),
            generation_strategy=str(row["generation_strategy"]),
            target_chunk_ids=list(row["target_chunk_ids"] or []),
            target_doc_id=str(row["target_doc_id"]),
            chunk_id_source=str(row["chunk_id_source"]),
            glossary_terms=list(row["glossary_terms"] or []),
            llm_scores=dict(row["llm_scores"] or {}),
            utility_score=float(row["utility_score"] or 0.0),
            novelty_score=float(row["novelty_score"] or 0.0),
            final_score=float(row["final_score"] or 0.0),
            prompt_version=row["prompt_version"],
            prompt_hash=row["prompt_hash"],
            product_name=row["product_name"],
        )
        for row in rows
    ]
    loaded_count = len(loaded_rows)
    if loaded_count != expected_count:
        raise RuntimeError(
            "Gating snapshot mismatch for memory build: "
            f"batch_id={source_batch_id}, preset={preset}, expected_accepted={expected_count}, loaded={loaded_count}. "
            "Check snapshot integrity (synthetic_query_gating_result <-> synthetic_queries_gated linkage)."
        )
    return loaded_rows


def _parse_comparison_snapshots(raw_value: Any) -> dict[str, dict[str, str]]:
    if not isinstance(raw_value, dict):
        return {}
    snapshots: dict[str, dict[str, str]] = {}
    for preset, payload in raw_value.items():
        if not isinstance(preset, str) or not preset.strip():
            continue
        if not isinstance(payload, dict):
            continue
        source_run_id = str(payload.get("source_gating_run_id") or "").strip()
        gating_batch_id = str(payload.get("gating_batch_id") or "").strip()
        if not source_run_id and not gating_batch_id:
            continue
        snapshots[preset.strip().lower()] = {
            "source_gating_run_id": source_run_id,
            "gating_batch_id": gating_batch_id,
        }
    return snapshots


def _normalize_stage_cutoff_level(value: str | None) -> str:
    normalized = str(value or "").strip().lower()
    if normalized in {
        STAGE_CUTOFF_RULE_ONLY,
        STAGE_CUTOFF_RULE_PLUS_LLM,
        STAGE_CUTOFF_UTILITY,
        STAGE_CUTOFF_DIVERSITY,
        STAGE_CUTOFF_FULL_GATING,
    }:
        return normalized
    return STAGE_CUTOFF_FULL_GATING


def _stage_cutoff_sql_clause(cutoff_level: str) -> str:
    if cutoff_level == STAGE_CUTOFF_RULE_ONLY:
        return "COALESCE(gr.rule_pass, TRUE)"
    if cutoff_level == STAGE_CUTOFF_RULE_PLUS_LLM:
        return (
            "COALESCE(gr.rule_pass, TRUE)"
            " AND COALESCE((gr.stage_payload_json ->> 'passed_llm_self_eval')::boolean, TRUE)"
        )
    if cutoff_level == STAGE_CUTOFF_UTILITY:
        return (
            "COALESCE(gr.rule_pass, TRUE)"
            " AND COALESCE((gr.stage_payload_json ->> 'passed_llm_self_eval')::boolean, TRUE)"
            " AND COALESCE((gr.stage_payload_json ->> 'passed_retrieval_utility')::boolean, TRUE)"
        )
    if cutoff_level == STAGE_CUTOFF_DIVERSITY:
        return (
            "COALESCE(gr.rule_pass, TRUE)"
            " AND COALESCE((gr.stage_payload_json ->> 'passed_llm_self_eval')::boolean, TRUE)"
            " AND COALESCE((gr.stage_payload_json ->> 'passed_retrieval_utility')::boolean, TRUE)"
            " AND COALESCE(gr.diversity_pass, TRUE)"
        )
    return "COALESCE(gr.accepted, FALSE)"


def _load_stage_cutoff_rows(
    connection: psycopg.Connection[Any],
    *,
    source_batch_id: str,
    cutoff_level: str,
    strategies: list[str] | None = None,
) -> list[GatedRow]:
    if not source_batch_id:
        return []
    where_strategy = ""
    parameters: list[Any] = [source_batch_id]
    normalized = [str(item).upper() for item in (strategies or []) if str(item).strip()]
    if normalized:
        where_strategy = " AND r.generation_strategy = ANY(%s)"
        parameters.append(normalized)
    cutoff_clause = _stage_cutoff_sql_clause(cutoff_level)
    with connection.cursor() as cursor:
        cursor.execute(
            f"""
            SELECT full_gated.gated_query_id,
                   gr.synthetic_query_id,
                   r.query_text,
                   r.query_type,
                   r.generation_strategy,
                   r.target_chunk_ids,
                   r.target_doc_id,
                   r.chunk_id_source,
                   r.glossary_terms,
                   COALESCE(gr.llm_scores, full_gated.llm_scores) AS llm_scores,
                   gr.utility_score,
                   gr.novelty_score,
                   gr.final_score,
                   r.prompt_version,
                   r.prompt_hash,
                   sc.product_name
            FROM synthetic_query_gating_result gr
            JOIN synthetic_queries_raw_all r ON r.synthetic_query_id = gr.synthetic_query_id
            JOIN synthetic_queries_gated full_gated
              ON full_gated.synthetic_query_id = gr.synthetic_query_id
             AND full_gated.gating_preset = 'full_gating'
            JOIN corpus_documents td ON td.document_id = r.target_doc_id
            JOIN corpus_chunks sc ON sc.chunk_id = r.chunk_id_source
            WHERE gr.gating_batch_id = %s
              AND {cutoff_clause}
              {where_strategy}
            ORDER BY gr.created_at ASC
            """,
            parameters,
        )
        rows = cursor.fetchall()
    return [
        GatedRow(
            gated_query_id=str(row["gated_query_id"]),
            synthetic_query_id=str(row["synthetic_query_id"]),
            query_text=str(row["query_text"]),
            query_type=str(row["query_type"]),
            generation_strategy=str(row["generation_strategy"]),
            target_chunk_ids=list(row["target_chunk_ids"] or []),
            target_doc_id=str(row["target_doc_id"]),
            chunk_id_source=str(row["chunk_id_source"]),
            glossary_terms=list(row["glossary_terms"] or []),
            llm_scores=dict(row["llm_scores"] or {}),
            utility_score=float(row["utility_score"] or 0.0),
            novelty_score=float(row["novelty_score"] or 0.0),
            final_score=float(row["final_score"] or 0.0),
            prompt_version=row["prompt_version"],
            prompt_hash=row["prompt_hash"],
            product_name=row["product_name"],
        )
        for row in rows
    ]


def _delete_existing_snapshot_memory_entries(
    connection: psycopg.Connection[Any],
    *,
    preset: str,
    source_run_id: str | None,
    source_batch_id: str | None,
    strategies: list[str] | None = None,
) -> int:
    normalized_strategies = [
        str(item).upper().strip()
        for item in (strategies or [])
        if str(item).strip()
    ]
    conditions: list[str] = [
        "COALESCE(NULLIF(m.metadata ->> 'gating_preset', ''), %s) = %s",
    ]
    parameters: list[Any] = [preset, preset]
    if normalized_strategies:
        conditions.append("m.generation_strategy = ANY(%s)")
        parameters.append(normalized_strategies)

    snapshot_conditions: list[str] = []
    if source_run_id:
        snapshot_conditions.append("m.metadata ->> 'source_gate_run_id' = %s")
        parameters.append(source_run_id)
    if source_batch_id:
        snapshot_conditions.append("m.metadata ->> 'source_gating_batch_id' = %s")
        parameters.append(source_batch_id)
        snapshot_conditions.append(
            """
            m.source_gated_query_id IN (
                SELECT g.gated_query_id
                FROM synthetic_query_gating_result gr
                JOIN synthetic_queries_gated g
                  ON g.synthetic_query_id = gr.synthetic_query_id
                 AND g.gating_preset = %s
                WHERE gr.gating_batch_id::text = %s
            )
            """
        )
        parameters.extend([preset, source_batch_id])

    if not snapshot_conditions:
        return 0

    with connection.cursor() as cursor:
        cursor.execute(
            f"""
            WITH deleted AS (
                DELETE FROM memory_entries m
                WHERE {" AND ".join(conditions)}
                  AND ({" OR ".join(snapshot_conditions)})
                RETURNING m.memory_id
            ),
            removed_embeddings AS (
                DELETE FROM query_embeddings qe
                USING deleted d
                WHERE qe.owner_type = 'memory'
                  AND qe.owner_id = d.memory_id::text
                RETURNING 1
            )
            SELECT COUNT(*) AS deleted_count
            FROM deleted
            """,
            parameters,
        )
        row = cursor.fetchone()
    if row is None:
        return 0
    return int(row["deleted_count"] or 0)


def _upsert_query_embedding(
    connection: psycopg.Connection[Any],
    *,
    owner_type: str,
    owner_id: str,
    embedding_model: str,
    embedding_values: list[float],
    metadata: dict[str, Any],
) -> None:
    literal = embedding_to_halfvec_literal(embedding_values)
    with connection.cursor() as cursor:
        cursor.execute(
            """
            INSERT INTO query_embeddings (
                owner_type,
                owner_id,
                embedding_model,
                embedding_dim,
                embedding,
                metadata
            ) VALUES (
                %s, %s, %s, 3072, CAST(%s AS halfvec), %s
            )
            ON CONFLICT (owner_type, owner_id, embedding_model) DO UPDATE
            SET embedding = EXCLUDED.embedding,
                metadata = EXCLUDED.metadata
            """,
            (owner_type, owner_id, embedding_model, literal, Jsonb(metadata)),
        )


def run_memory_build(
    *,
    experiment: str,
    experiment_root: Path = Path("configs/experiments"),
    database_url: str | None = None,
    db_host: str = "localhost",
    db_port: int = 5432,
    db_name: str = "query_forge",
    db_user: str = "query_forge",
    db_password: str = "query_forge",
) -> dict[str, Any]:
    config = load_experiment_config(experiment, experiment_root=experiment_root)
    options = type(
        "DbOptions",
        (),
        {
            "database_url": database_url,
            "host": db_host,
            "port": db_port,
            "database": db_name,
            "user": db_user,
            "password": db_password,
        },
    )()
    connection = connect(options, autocommit=False)
    try:
        recorder = ExperimentRunRecorder(connection)
        run_context = recorder.start_run(
            experiment_key=config.experiment_key,
            category=config.category,
            description=config.description,
            config_path=str(config.config_path),
            config_hash=config.config_hash,
            parameters={
                "stage": "build-memory",
                "gating_preset": config.gating_preset,
            },
            run_label="build-memory",
        )

        synthetic_free_baseline = bool(config.raw.get("synthetic_free_baseline", False))
        if synthetic_free_baseline:
            summary = {
                "experiment_key": config.experiment_key,
                "experiment_run_id": run_context.experiment_run_id,
                "gating_preset": "ungated",
                "source_gating_run_id": None,
                "source_generation_strategies": [],
                "memory_entries_built": 0,
                "memory_entries_by_snapshot": {},
                "memory_experiment_key": config.experiment_key,
                "preview_memory_ids": [],
                "embedding_model": "hash-embedding-v1",
                "synthetic_free_baseline": True,
                "skipped": True,
                "skip_reason": "synthetic_free_baseline",
            }
            recorder.finish_run(run_context, status="completed", metrics=summary)
            connection.commit()
            return summary

        configured_strategies = (
            config.raw.get("source_generation_strategies")
            or config.raw.get("memory_generation_strategies")
            or [config.generation_strategy]
        )
        strategy_filters: list[str] = []
        for item in configured_strategies:
            normalized = str(item).upper().strip()
            if not normalized or normalized in strategy_filters:
                continue
            strategy_filters.append(normalized)
        configured_gating_run_id = str(config.raw.get("source_gating_run_id") or "").strip() or None
        stage_cutoff_enabled = bool(config.raw.get("stage_cutoff_enabled", False))
        stage_cutoff_level = _normalize_stage_cutoff_level(str(config.raw.get("stage_cutoff_level") or config.gating_preset))
        stage_cutoff_source_batch_id = str(
            config.raw.get("stage_cutoff_source_gating_batch_id")
            or config.raw.get("source_gating_batch_id")
            or ""
        ).strip() or None
        comparison_snapshots = _parse_comparison_snapshots(config.raw.get("comparison_snapshots"))
        snapshot_plan: list[tuple[str, str | None, str | None]] = []
        if stage_cutoff_enabled:
            snapshot_plan.append((config.gating_preset, configured_gating_run_id, stage_cutoff_source_batch_id))
        elif comparison_snapshots:
            for preset, payload in comparison_snapshots.items():
                snapshot_plan.append(
                    (
                        preset,
                        payload.get("source_gating_run_id"),
                        payload.get("gating_batch_id"),
                    )
                )
        else:
            source_batch_id = str(config.raw.get("source_gating_batch_id") or "").strip() or None
            source_run_id = configured_gating_run_id
            if source_batch_id and not source_run_id:
                source_run_id = _resolve_source_run_id_by_batch(
                    connection,
                    source_batch_id=source_batch_id,
                )
            if source_run_id and not source_batch_id:
                source_batch_id = _resolve_gating_batch_by_run(
                    connection,
                    preset=config.gating_preset,
                    source_run_id=source_run_id,
                    strategies=strategy_filters,
                )
            if not source_run_id and not source_batch_id:
                source_run_id, source_batch_id = _latest_gating_snapshot(
                    connection,
                    config.gating_preset,
                    strategy_filters,
                )
            snapshot_plan.append((config.gating_preset, source_run_id, source_batch_id))

        inserted_memory_ids: list[str] = []
        built_by_snapshot: dict[str, int] = {}
        deleted_by_snapshot: dict[str, int] = {}
        total_rows = 0
        total_deleted_rows = 0
        primary_source_run_id: str | None = None
        for preset, source_run_id, source_batch_id in snapshot_plan:
            resolved_source_run_id = source_run_id or _resolve_source_run_id_by_batch(
                connection,
                source_batch_id=source_batch_id,
            )
            if primary_source_run_id is None and resolved_source_run_id:
                primary_source_run_id = resolved_source_run_id
            if stage_cutoff_enabled:
                if not source_batch_id:
                    raise RuntimeError(
                        "stage_cutoff_enabled requires source_gating_batch_id for deterministic snapshot loading"
                    )
                rows = _load_stage_cutoff_rows(
                    connection,
                    source_batch_id=source_batch_id or "",
                    cutoff_level=stage_cutoff_level,
                    strategies=strategy_filters,
                )
            else:
                if source_batch_id:
                    rows = _load_gated_rows_by_batch(
                        connection,
                        preset=preset,
                        source_batch_id=source_batch_id,
                        strategies=strategy_filters,
                    )
                else:
                    rows = _load_gated_rows(
                        connection,
                        preset=preset,
                        source_run_id=resolved_source_run_id,
                        strategies=strategy_filters,
                    )
            built_by_snapshot[preset] = len(rows)
            total_rows += len(rows)
            deleted_rows = _delete_existing_snapshot_memory_entries(
                connection,
                preset=preset,
                source_run_id=resolved_source_run_id,
                source_batch_id=source_batch_id,
                strategies=strategy_filters,
            )
            deleted_by_snapshot[preset] = deleted_rows
            total_deleted_rows += deleted_rows
            for row in rows:
                memory_id = str(uuid.uuid4())
                embedding = embed_text(row.query_text)
                embedding_literal = embedding_to_halfvec_literal(embedding)
                with connection.cursor() as cursor:
                    cursor.execute(
                        """
                        WITH deleted AS (
                            DELETE FROM memory_entries
                            WHERE source_gated_query_id = %s
                            RETURNING memory_id
                        )
                        DELETE FROM query_embeddings qe
                        USING deleted d
                        WHERE qe.owner_type = 'memory'
                          AND qe.owner_id = d.memory_id::text
                        """,
                        (row.gated_query_id,),
                    )
                    cursor.execute(
                        """
                        INSERT INTO memory_entries (
                            memory_id,
                            source_gated_query_id,
                            query_text,
                            query_type,
                            generation_strategy,
                            target_chunk_ids,
                            target_doc_id,
                            chunk_id_source,
                            product,
                            glossary_terms,
                            llm_scores,
                            utility_score,
                            novelty_score,
                            final_score,
                            prompt_version,
                            prompt_hash,
                            query_embedding,
                            metadata
                        ) VALUES (
                            %s, %s, %s, %s, %s, %s, %s, %s, %s,
                            %s, %s, %s, %s, %s, %s, %s, CAST(%s AS halfvec), %s
                        )
                        """,
                        (
                            memory_id,
                            row.gated_query_id,
                            row.query_text,
                            row.query_type,
                            row.generation_strategy,
                            Jsonb(row.target_chunk_ids),
                            row.target_doc_id,
                            row.chunk_id_source,
                            row.product_name,
                            Jsonb(row.glossary_terms),
                            Jsonb(row.llm_scores),
                            row.utility_score,
                            row.novelty_score,
                            row.final_score,
                            row.prompt_version,
                            row.prompt_hash,
                            embedding_literal,
                            Jsonb(
                                {
                                    "gating_preset": preset,
                                    "source_gate_run_id": resolved_source_run_id,
                                    "source_gating_batch_id": source_batch_id,
                                    "stage_cutoff_enabled": stage_cutoff_enabled,
                                    "stage_cutoff_level": stage_cutoff_level if stage_cutoff_enabled else None,
                                    "stage_cutoff_source_gating_batch_id": source_batch_id if stage_cutoff_enabled else None,
                                    "memory_build_run_id": run_context.experiment_run_id,
                                    "memory_experiment_key": config.experiment_key,
                                }
                            ),
                        ),
                    )

                _upsert_query_embedding(
                    connection,
                    owner_type="memory",
                    owner_id=memory_id,
                    embedding_model="hash-embedding-v1",
                    embedding_values=embedding,
                    metadata={"source_gated_query_id": row.gated_query_id},
                )
                _upsert_query_embedding(
                    connection,
                    owner_type="synthetic_gated",
                    owner_id=row.gated_query_id,
                    embedding_model="hash-embedding-v1",
                    embedding_values=embedding,
                    metadata={"synthetic_query_id": row.synthetic_query_id},
                )
                _upsert_query_embedding(
                    connection,
                    owner_type="synthetic_raw",
                    owner_id=row.synthetic_query_id,
                    embedding_model="hash-embedding-v1",
                    embedding_values=embedding,
                    metadata={"gated_query_id": row.gated_query_id},
                )
                if len(inserted_memory_ids) < 20:
                    inserted_memory_ids.append(memory_id)

        summary = {
            "experiment_key": config.experiment_key,
            "experiment_run_id": run_context.experiment_run_id,
            "gating_preset": config.gating_preset,
            "source_gating_run_id": primary_source_run_id,
            "source_generation_strategies": strategy_filters,
            "memory_entries_built": total_rows,
            "memory_entries_by_snapshot": built_by_snapshot,
            "memory_entries_deleted_before_build": total_deleted_rows,
            "memory_entries_deleted_by_snapshot": deleted_by_snapshot,
            "memory_experiment_key": config.experiment_key,
            "preview_memory_ids": inserted_memory_ids,
            "embedding_model": "hash-embedding-v1",
            "stage_cutoff_enabled": stage_cutoff_enabled,
            "stage_cutoff_level": stage_cutoff_level if stage_cutoff_enabled else None,
            "stage_cutoff_source_gating_batch_id": stage_cutoff_source_batch_id if stage_cutoff_enabled else None,
        }
        recorder.finish_run(run_context, status="completed", metrics=summary)
        connection.commit()
        return summary
    except Exception as exception:  # noqa: BLE001
        connection.rollback()
        LOGGER.exception("Memory build failed.")
        raise exception
    finally:
        connection.close()


def run_memory_build_from_env(experiment: str) -> dict[str, Any]:
    defaults = default_database_args()
    return run_memory_build(
        experiment=experiment,
        database_url=defaults["database_url"],
        db_host=defaults["db_host"],
        db_port=defaults["db_port"],
        db_name=defaults["db_name"],
        db_user=defaults["db_user"],
        db_password=defaults["db_password"],
    )
