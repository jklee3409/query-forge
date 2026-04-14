from __future__ import annotations

from collections import defaultdict
from typing import Any

import psycopg

try:
    from loaders.common import (
        ImportOptions,
        ImportStats,
        batch_iterable,
        checksum_text,
        filter_rows,
        jsonb,
        load_jsonl,
        load_raw_documents,
    )
except ModuleNotFoundError:  # pragma: no cover - direct module execution fallback
    from pipeline.loaders.common import (
        ImportOptions,
        ImportStats,
        batch_iterable,
        checksum_text,
        filter_rows,
        jsonb,
        load_jsonl,
        load_raw_documents,
    )


OVERLAP_LABEL = "Overlap context from previous chunk:"


def parse_overlap_from_chunk_text(chunk_text: str) -> tuple[int, str]:
    if not chunk_text.startswith(OVERLAP_LABEL):
        return 0, chunk_text
    parts = chunk_text.split("\n\n", 1)
    if len(parts) != 2:
        return 0, chunk_text
    overlap_payload = parts[0].split("\n", 1)
    overlap_text = overlap_payload[1] if len(overlap_payload) == 2 else ""
    return len(overlap_text), parts[1]


def fetch_section_presence(
    connection: psycopg.Connection[Any],
    section_ids: list[str],
) -> dict[str, dict[str, Any]]:
    if not section_ids:
        return {}
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT section_id, table_count, list_count
            FROM corpus_sections
            WHERE section_id = ANY(%s)
            """,
            (section_ids,),
        )
        return {str(row["section_id"]): row for row in cursor.fetchall()}


def fetch_document_presence(
    connection: psycopg.Connection[Any],
    document_ids: list[str],
) -> set[str]:
    if not document_ids:
        return set()
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT document_id
            FROM corpus_documents
            WHERE document_id = ANY(%s)
            """,
            (document_ids,),
        )
        return {str(row["document_id"]) for row in cursor.fetchall()}


def fetch_existing_chunks(
    connection: psycopg.Connection[Any],
    chunk_ids: list[str],
) -> dict[str, dict[str, Any]]:
    if not chunk_ids:
        return {}
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT chunk_id, document_id, section_id, chunk_index_in_document, chunk_index_in_section,
                   section_path_text, chunk_text, char_len, token_len, overlap_from_prev_chars,
                   previous_chunk_id, next_chunk_id, code_presence, table_presence, list_presence,
                   product_name, version_label, content_checksum, metadata_json
            FROM corpus_chunks
            WHERE chunk_id = ANY(%s)
            """,
            (chunk_ids,),
        )
        return {str(row["chunk_id"]): row for row in cursor.fetchall()}


def rows_equal_chunk(existing: dict[str, Any], candidate: dict[str, Any]) -> bool:
    return all(
        [
            existing["document_id"] == candidate["document_id"],
            existing["section_id"] == candidate["section_id"],
            existing["chunk_index_in_document"] == candidate["chunk_index_in_document"],
            existing["chunk_index_in_section"] == candidate["chunk_index_in_section"],
            existing["section_path_text"] == candidate["section_path_text"],
            existing["chunk_text"] == candidate["chunk_text"],
            existing["char_len"] == candidate["char_len"],
            existing["token_len"] == candidate["token_len"],
            existing["overlap_from_prev_chars"] == candidate["overlap_from_prev_chars"],
            existing["previous_chunk_id"] == candidate["previous_chunk_id"],
            existing["next_chunk_id"] == candidate["next_chunk_id"],
            bool(existing["code_presence"]) == candidate["code_presence"],
            bool(existing["table_presence"]) == candidate["table_presence"],
            bool(existing["list_presence"]) == candidate["list_presence"],
            existing["product_name"] == candidate["product_name"],
            existing["version_label"] == candidate["version_label"],
            existing["content_checksum"] == candidate["content_checksum"],
            dict(existing["metadata_json"]) == candidate["metadata_json"],
        ]
    )


def import_chunks(
    connection: psycopg.Connection[Any],
    *,
    options: ImportOptions,
    import_run_id: str,
) -> ImportStats:
    raw_documents = load_raw_documents(options.raw_input_path)
    chunk_rows = filter_rows(
        load_jsonl(options.chunks_input_path),
        source_ids=options.source_ids,
        document_ids=options.document_ids,
        raw_documents=raw_documents,
    )
    document_presence = fetch_document_presence(
        connection,
        sorted({str(row["document_id"]) for row in chunk_rows}),
    )
    section_ids = sorted(
        {
            section_id
            for row in chunk_rows
            for section_id in row.get("metadata", {}).get("section_ids", [])
        }
    )
    section_presence = fetch_section_presence(connection, section_ids)
    existing_chunks = fetch_existing_chunks(connection, [str(row["chunk_id"]) for row in chunk_rows])

    stats = ImportStats()
    chunk_index_per_section: dict[str, int] = defaultdict(int)
    upserts: list[tuple[Any, ...]] = []

    for row in sorted(chunk_rows, key=lambda item: (item["document_id"], item["chunk_index_in_doc"])):
        document_id = str(row["document_id"])
        if document_id not in document_presence:
            stats.skipped += 1
            if len(stats.errors) < 50:
                stats.errors.append(
                    {
                        "chunk_id": str(row["chunk_id"]),
                        "document_id": document_id,
                        "reason": "missing_document",
                    }
                )
            continue

        metadata = dict(row.get("metadata") or {})
        candidate_section_ids = [
            str(value)
            for value in metadata.get("section_ids", [row.get("section_id")])
            if value is not None and str(value).strip()
        ]
        primary_section_id = next(
            (section_id for section_id in candidate_section_ids if section_id in section_presence),
            None,
        )
        section_bucket = primary_section_id or f"__missing__:{row['document_id']}"
        chunk_index_in_section = chunk_index_per_section[section_bucket]
        chunk_index_per_section[section_bucket] += 1
        overlap_from_prev_chars, _base_chunk_text = parse_overlap_from_chunk_text(str(row["content"]))

        related_section_ids = candidate_section_ids
        table_presence = any(section_presence.get(section_id, {}).get("table_count", 0) > 0 for section_id in related_section_ids)
        list_presence = any(section_presence.get(section_id, {}).get("list_count", 0) > 0 for section_id in related_section_ids)

        candidate = {
            "chunk_id": str(row["chunk_id"]),
            "document_id": document_id,
            "section_id": primary_section_id,
            "chunk_index_in_document": int(row["chunk_index_in_doc"]),
            "chunk_index_in_section": chunk_index_in_section,
            "section_path_text": str(row["section_path"]),
            "chunk_text": str(row["content"]),
            "char_len": int(row["char_len"]),
            "token_len": int(row["token_len"]),
            "overlap_from_prev_chars": overlap_from_prev_chars,
            "previous_chunk_id": row.get("previous_chunk_id"),
            "next_chunk_id": row.get("next_chunk_id"),
            "code_presence": bool(row["code_presence"]),
            "table_presence": table_presence,
            "list_presence": list_presence,
            "product_name": str(row["product"]),
            "version_label": row.get("version_if_available"),
            "content_checksum": checksum_text(str(row["content"])),
            "import_run_id": import_run_id,
            "metadata_json": metadata,
        }

        existing = existing_chunks.get(candidate["chunk_id"])
        if existing and rows_equal_chunk(existing, candidate):
            stats.skipped += 1
            continue

        if existing:
            stats.updated += 1
        else:
            stats.inserted += 1
        stats.preview_ids.append(candidate["chunk_id"])
        upserts.append(
            (
                candidate["chunk_id"],
                candidate["document_id"],
                candidate["section_id"],
                candidate["chunk_index_in_document"],
                candidate["chunk_index_in_section"],
                candidate["section_path_text"],
                candidate["chunk_text"],
                candidate["char_len"],
                candidate["token_len"],
                candidate["overlap_from_prev_chars"],
                candidate["previous_chunk_id"],
                candidate["next_chunk_id"],
                candidate["code_presence"],
                candidate["table_presence"],
                candidate["list_presence"],
                candidate["product_name"],
                candidate["version_label"],
                candidate["content_checksum"],
                candidate["import_run_id"],
                jsonb(candidate["metadata_json"]),
            )
        )

    if options.dry_run or not upserts:
        return stats

    sql = """
        INSERT INTO corpus_chunks (
            chunk_id, document_id, section_id, chunk_index_in_document, chunk_index_in_section,
            section_path_text, chunk_text, char_len, token_len, overlap_from_prev_chars,
            previous_chunk_id, next_chunk_id, code_presence, table_presence, list_presence,
            product_name, version_label, content_checksum, import_run_id, metadata_json,
            created_at, updated_at
        ) VALUES (
            %s, %s, %s, %s, %s,
            %s, %s, %s, %s, %s,
            %s, %s, %s, %s, %s,
            %s, %s, %s, %s, %s,
            NOW(), NOW()
        )
        ON CONFLICT (chunk_id) DO UPDATE
        SET document_id = EXCLUDED.document_id,
            section_id = EXCLUDED.section_id,
            chunk_index_in_document = EXCLUDED.chunk_index_in_document,
            chunk_index_in_section = EXCLUDED.chunk_index_in_section,
            section_path_text = EXCLUDED.section_path_text,
            chunk_text = EXCLUDED.chunk_text,
            char_len = EXCLUDED.char_len,
            token_len = EXCLUDED.token_len,
            overlap_from_prev_chars = EXCLUDED.overlap_from_prev_chars,
            previous_chunk_id = EXCLUDED.previous_chunk_id,
            next_chunk_id = EXCLUDED.next_chunk_id,
            code_presence = EXCLUDED.code_presence,
            table_presence = EXCLUDED.table_presence,
            list_presence = EXCLUDED.list_presence,
            product_name = EXCLUDED.product_name,
            version_label = EXCLUDED.version_label,
            content_checksum = EXCLUDED.content_checksum,
            import_run_id = EXCLUDED.import_run_id,
            metadata_json = EXCLUDED.metadata_json,
            updated_at = NOW()
    """
    with connection.cursor() as cursor:
        for batch in batch_iterable(upserts, options.batch_size):
            cursor.executemany(sql, batch)

    return stats
