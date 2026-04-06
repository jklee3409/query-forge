from __future__ import annotations

import re
from collections import defaultdict
from typing import Any

import psycopg

try:
    from loaders.common import (
        ImportOptions,
        ImportStats,
        batch_iterable,
        filter_rows,
        jsonb,
        load_jsonl,
        load_raw_documents,
        normalize_term_text,
        stable_uuid,
    )
except ModuleNotFoundError:  # pragma: no cover - direct module execution fallback
    from pipeline.loaders.common import (
        ImportOptions,
        ImportStats,
        batch_iterable,
        filter_rows,
        jsonb,
        load_jsonl,
        load_raw_documents,
        normalize_term_text,
        stable_uuid,
    )


TERM_TYPE_MAPPING = {
    "spring_product": "product",
    "annotation": "annotation",
    "class_interface": "class",
    "config_key": "config_key",
    "cli_command": "cli",
    "dependency_artifact": "artifact",
}


def filter_glossary_rows(
    rows: list[dict[str, Any]],
    *,
    options: ImportOptions,
    raw_documents: dict[str, dict[str, Any]],
) -> list[dict[str, Any]]:
    if not options.source_ids and not options.document_ids:
        return rows

    filtered: list[dict[str, Any]] = []
    for row in rows:
        document_ids = {str(value) for value in row.get("metadata", {}).get("document_ids", [])}
        if options.document_ids and not document_ids.intersection(options.document_ids):
            continue
        if options.source_ids:
            source_ids = {
                str(raw_documents.get(document_id, {}).get("source_id") or "")
                for document_id in document_ids
            }
            if not source_ids.intersection(options.source_ids):
                continue
        filtered.append(row)
    return filtered


def alias_type(alias: str, canonical: str) -> str:
    if alias.lower() == canonical.lower():
        return "same_case"
    if "-" in alias:
        return "kebab"
    if "." in alias:
        return "dotted"
    if " " in alias:
        return "spaced"
    if alias.isupper() and len(alias) <= 12:
        return "abbreviation"
    if any(ord(char) > 127 for char in alias):
        return "translated"
    return "same_case"


def source_confidence(row: dict[str, Any]) -> float:
    evidence_count = int(row.get("metadata", {}).get("evidence_count", 0))
    return min(1.0, 0.55 + evidence_count * 0.05)


def build_chunk_index(
    options: ImportOptions,
    raw_documents: dict[str, dict[str, Any]],
) -> tuple[list[dict[str, Any]], dict[str, list[dict[str, Any]]]]:
    chunk_rows = filter_rows(
        load_jsonl(options.chunks_input_path),
        source_ids=options.source_ids,
        document_ids=options.document_ids,
        raw_documents=raw_documents,
    )
    by_document: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in chunk_rows:
        by_document[str(row["document_id"])].append(row)
    for rows in by_document.values():
        rows.sort(key=lambda item: item["chunk_index_in_doc"])
    return chunk_rows, by_document


def build_glossary_payloads(
    glossary_rows: list[dict[str, Any]],
    chunk_rows_by_document: dict[str, list[dict[str, Any]]],
    *,
    import_run_id: str,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
    term_rows: list[dict[str, Any]] = []
    alias_rows: list[dict[str, Any]] = []
    evidence_rows: list[dict[str, Any]] = []

    for row in glossary_rows:
        mapped_type = TERM_TYPE_MAPPING.get(str(row["term_type"]), "concept")
        canonical_form = str(row["canonical_form"])
        normalized_form = normalize_term_text(canonical_form)
        term_id = stable_uuid(f"corpus-term:{mapped_type}:{normalized_form}")

        provenance_document_ids = [str(value) for value in row.get("metadata", {}).get("document_ids", [])]
        candidate_texts = [canonical_form, *[str(alias) for alias in row.get("aliases", [])]]
        seen_evidences: set[str] = set()

        matched_evidence_for_term: list[dict[str, Any]] = []
        for document_id in provenance_document_ids:
            for chunk in chunk_rows_by_document.get(document_id, []):
                chunk_text = str(chunk["content"])
                for candidate_text in candidate_texts:
                    if not candidate_text:
                        continue
                    pattern = re.compile(re.escape(candidate_text), re.IGNORECASE)
                    for match in pattern.finditer(chunk_text):
                        evidence_id = stable_uuid(
                            f"corpus-evidence:{term_id}:{chunk['chunk_id']}:{candidate_text}:{match.start()}:{match.end()}"
                        )
                        if evidence_id in seen_evidences:
                            continue
                        seen_evidences.add(evidence_id)
                        matched = chunk_text[match.start() : match.end()]
                        evidence_row = {
                            "evidence_id": evidence_id,
                            "term_id": term_id,
                            "document_id": document_id,
                            "chunk_id": str(chunk["chunk_id"]),
                            "matched_text": matched,
                            "line_or_offset_info": {
                                "start_offset": match.start(),
                                "end_offset": match.end(),
                                "chunk_index_in_document": chunk["chunk_index_in_doc"],
                            },
                            "import_run_id": import_run_id,
                        }
                        evidence_rows.append(evidence_row)
                        matched_evidence_for_term.append(evidence_row)

        matched_evidence_for_term.sort(
            key=lambda item: (
                item["document_id"],
                item["line_or_offset_info"]["chunk_index_in_document"],
                item["line_or_offset_info"]["start_offset"],
            )
        )
        first_evidence = matched_evidence_for_term[0] if matched_evidence_for_term else None

        term_rows.append(
            {
                "term_id": term_id,
                "canonical_form": canonical_form,
                "normalized_form": normalized_form,
                "term_type": mapped_type,
                "keep_in_english": bool(row.get("keep_in_english", True)),
                "description_short": f"Imported {mapped_type} term from normalized Spring corpus artifacts.",
                "source_confidence": source_confidence(row),
                "first_seen_document_id": first_evidence["document_id"] if first_evidence else (provenance_document_ids[0] if provenance_document_ids else None),
                "first_seen_chunk_id": first_evidence["chunk_id"] if first_evidence else None,
                "evidence_count": len(matched_evidence_for_term),
                "is_active": True,
                "import_run_id": import_run_id,
                "metadata_json": row.get("metadata", {}),
            }
        )

        for alias in row.get("aliases", []):
            alias_text = str(alias)
            alias_rows.append(
                {
                    "alias_id": stable_uuid(f"corpus-alias:{term_id}:{normalize_term_text(alias_text)}"),
                    "term_id": term_id,
                    "alias_text": alias_text,
                    "alias_language": "en",
                    "alias_type": alias_type(alias_text, canonical_form),
                    "import_run_id": import_run_id,
                }
            )

    return term_rows, alias_rows, evidence_rows


def fetch_existing_terms(
    connection: psycopg.Connection[Any],
    term_ids: list[str],
) -> dict[str, dict[str, Any]]:
    if not term_ids:
        return {}
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT term_id, canonical_form, normalized_form, term_type, keep_in_english,
                   description_short, source_confidence, first_seen_document_id,
                   first_seen_chunk_id, evidence_count, is_active, metadata_json
            FROM corpus_glossary_terms
            WHERE term_id = ANY(%s)
            """,
            (term_ids,),
        )
        return {str(row["term_id"]): row for row in cursor.fetchall()}


def fetch_existing_aliases(
    connection: psycopg.Connection[Any],
    term_ids: list[str],
) -> dict[tuple[str, str, str], dict[str, Any]]:
    if not term_ids:
        return {}
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT alias_id, term_id, alias_text, alias_language, alias_type
            FROM corpus_glossary_aliases
            WHERE term_id = ANY(%s)
            """,
            (term_ids,),
        )
        return {
            (str(row["term_id"]), str(row["alias_text"]), str(row["alias_language"])): row
            for row in cursor.fetchall()
        }


def fetch_existing_evidence(
    connection: psycopg.Connection[Any],
    term_ids: list[str],
) -> dict[str, dict[str, Any]]:
    if not term_ids:
        return {}
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT evidence_id, term_id, document_id, chunk_id, matched_text, line_or_offset_info
            FROM corpus_glossary_evidence
            WHERE term_id = ANY(%s)
            """,
            (term_ids,),
        )
        return {str(row["evidence_id"]): row for row in cursor.fetchall()}


def rows_equal_term(existing: dict[str, Any], candidate: dict[str, Any]) -> bool:
    return all(
        [
            existing["canonical_form"] == candidate["canonical_form"],
            existing["normalized_form"] == candidate["normalized_form"],
            existing["term_type"] == candidate["term_type"],
            bool(existing["keep_in_english"]) == candidate["keep_in_english"],
            existing["description_short"] == candidate["description_short"],
            float(existing["source_confidence"]) == float(candidate["source_confidence"]),
            existing["first_seen_document_id"] == candidate["first_seen_document_id"],
            existing["first_seen_chunk_id"] == candidate["first_seen_chunk_id"],
            existing["evidence_count"] == candidate["evidence_count"],
            bool(existing["is_active"]) == candidate["is_active"],
            dict(existing["metadata_json"]) == candidate["metadata_json"],
        ]
    )


def import_glossary(
    connection: psycopg.Connection[Any],
    *,
    options: ImportOptions,
    import_run_id: str,
) -> dict[str, Any]:
    raw_documents = load_raw_documents(options.raw_input_path)
    glossary_rows = filter_glossary_rows(
        load_jsonl(options.glossary_input_path),
        options=options,
        raw_documents=raw_documents,
    )
    _chunk_rows, chunk_rows_by_document = build_chunk_index(options, raw_documents)
    term_rows, alias_rows, evidence_rows = build_glossary_payloads(
        glossary_rows,
        chunk_rows_by_document,
        import_run_id=import_run_id,
    )

    existing_terms = fetch_existing_terms(connection, [row["term_id"] for row in term_rows])
    existing_aliases = fetch_existing_aliases(connection, [row["term_id"] for row in term_rows])
    existing_evidence = fetch_existing_evidence(connection, [row["term_id"] for row in term_rows])

    term_stats = ImportStats()
    alias_stats = ImportStats()
    evidence_stats = ImportStats()
    term_upserts: list[tuple[Any, ...]] = []
    alias_upserts: list[tuple[Any, ...]] = []
    evidence_upserts: list[tuple[Any, ...]] = []

    for row in term_rows:
        existing = existing_terms.get(row["term_id"])
        if existing and rows_equal_term(existing, row):
            term_stats.skipped += 1
            continue
        if existing:
            term_stats.updated += 1
        else:
            term_stats.inserted += 1
        term_stats.preview_ids.append(row["term_id"])
        term_upserts.append(
            (
                row["term_id"],
                row["canonical_form"],
                row["normalized_form"],
                row["term_type"],
                row["keep_in_english"],
                row["description_short"],
                row["source_confidence"],
                row["first_seen_document_id"],
                row["first_seen_chunk_id"],
                row["evidence_count"],
                row["is_active"],
                row["import_run_id"],
                jsonb(row["metadata_json"]),
            )
        )

    for row in alias_rows:
        key = (row["term_id"], row["alias_text"], row["alias_language"])
        existing = existing_aliases.get(key)
        if existing and existing["alias_type"] == row["alias_type"]:
            alias_stats.skipped += 1
            continue
        if existing:
            alias_stats.updated += 1
        else:
            alias_stats.inserted += 1
        alias_stats.preview_ids.append(row["alias_id"])
        alias_upserts.append(
            (
                row["alias_id"],
                row["term_id"],
                row["alias_text"],
                row["alias_language"],
                row["alias_type"],
                row["import_run_id"],
            )
        )

    for row in evidence_rows:
        existing = existing_evidence.get(row["evidence_id"])
        if existing and dict(existing["line_or_offset_info"]) == row["line_or_offset_info"]:
            evidence_stats.skipped += 1
            continue
        if existing:
            evidence_stats.updated += 1
        else:
            evidence_stats.inserted += 1
        evidence_stats.preview_ids.append(row["evidence_id"])
        evidence_upserts.append(
            (
                row["evidence_id"],
                row["term_id"],
                row["document_id"],
                row["chunk_id"],
                row["matched_text"],
                jsonb(row["line_or_offset_info"]),
                row["import_run_id"],
            )
        )

    if not options.dry_run:
        if term_upserts:
            sql = """
                INSERT INTO corpus_glossary_terms (
                    term_id, canonical_form, normalized_form, term_type, keep_in_english,
                    description_short, source_confidence, first_seen_document_id,
                    first_seen_chunk_id, evidence_count, is_active, import_run_id,
                    metadata_json, created_at, updated_at
                ) VALUES (
                    %s, %s, %s, %s, %s,
                    %s, %s, %s,
                    %s, %s, %s, %s,
                    %s, NOW(), NOW()
                )
                ON CONFLICT (term_id) DO UPDATE
                SET canonical_form = EXCLUDED.canonical_form,
                    normalized_form = EXCLUDED.normalized_form,
                    term_type = EXCLUDED.term_type,
                    keep_in_english = EXCLUDED.keep_in_english,
                    description_short = EXCLUDED.description_short,
                    source_confidence = EXCLUDED.source_confidence,
                    first_seen_document_id = EXCLUDED.first_seen_document_id,
                    first_seen_chunk_id = EXCLUDED.first_seen_chunk_id,
                    evidence_count = EXCLUDED.evidence_count,
                    is_active = EXCLUDED.is_active,
                    import_run_id = EXCLUDED.import_run_id,
                    metadata_json = EXCLUDED.metadata_json,
                    updated_at = NOW()
            """
            with connection.cursor() as cursor:
                for batch in batch_iterable(term_upserts, options.batch_size):
                    cursor.executemany(sql, batch)

        if alias_upserts:
            sql = """
                INSERT INTO corpus_glossary_aliases (
                    alias_id, term_id, alias_text, alias_language, alias_type, import_run_id, created_at
                ) VALUES (%s, %s, %s, %s, %s, %s, NOW())
                ON CONFLICT (term_id, alias_text, alias_language) DO UPDATE
                SET alias_type = EXCLUDED.alias_type,
                    import_run_id = EXCLUDED.import_run_id
            """
            with connection.cursor() as cursor:
                for batch in batch_iterable(alias_upserts, options.batch_size):
                    cursor.executemany(sql, batch)

        if evidence_upserts:
            sql = """
                INSERT INTO corpus_glossary_evidence (
                    evidence_id, term_id, document_id, chunk_id, matched_text, line_or_offset_info, import_run_id, created_at
                ) VALUES (%s, %s, %s, %s, %s, %s, %s, NOW())
                ON CONFLICT (evidence_id) DO UPDATE
                SET matched_text = EXCLUDED.matched_text,
                    line_or_offset_info = EXCLUDED.line_or_offset_info,
                    import_run_id = EXCLUDED.import_run_id
            """
            with connection.cursor() as cursor:
                for batch in batch_iterable(evidence_upserts, options.batch_size):
                    cursor.executemany(sql, batch)

    return {
        "terms": term_stats.to_dict(),
        "aliases": alias_stats.to_dict(),
        "evidence": evidence_stats.to_dict(),
    }
