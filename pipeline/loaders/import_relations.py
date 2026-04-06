from __future__ import annotations

from collections import defaultdict
from typing import Any

import psycopg

try:
    from loaders.common import ImportOptions, ImportStats, batch_iterable, filter_rows, load_jsonl, load_raw_documents, stable_uuid
except ModuleNotFoundError:  # pragma: no cover - direct module execution fallback
    from pipeline.loaders.common import ImportOptions, ImportStats, batch_iterable, filter_rows, load_jsonl, load_raw_documents, stable_uuid


def fetch_existing_relations(
    connection: psycopg.Connection[Any],
    source_chunk_ids: list[str],
) -> dict[tuple[str, str, str], dict[str, Any]]:
    if not source_chunk_ids:
        return {}
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT relation_id, source_chunk_id, target_chunk_id, relation_type, distance_in_doc
            FROM corpus_chunk_relations
            WHERE source_chunk_id = ANY(%s)
            """,
            (source_chunk_ids,),
        )
        return {
            (str(row["source_chunk_id"]), str(row["target_chunk_id"]), str(row["relation_type"])): row
            for row in cursor.fetchall()
        }


def build_relation_rows(chunks: list[dict[str, Any]], import_run_id: str) -> list[dict[str, Any]]:
    by_document: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for chunk in chunks:
        by_document[str(chunk["document_id"])].append(chunk)

    relation_rows: list[dict[str, Any]] = []

    for document_chunks in by_document.values():
        ordered_chunks = sorted(document_chunks, key=lambda item: item["chunk_index_in_doc"])
        for source_index, source in enumerate(ordered_chunks):
            source_section_ids = set(source.get("metadata", {}).get("section_ids", []))
            for target_index, target in enumerate(ordered_chunks):
                if source_index == target_index:
                    continue

                distance = abs(int(source["chunk_index_in_doc"]) - int(target["chunk_index_in_doc"]))
                relation_types: list[str] = []
                if 1 <= distance <= 2:
                    relation_types.append("near")
                elif 3 <= distance <= 6:
                    relation_types.append("far")

                target_section_ids = set(target.get("metadata", {}).get("section_ids", []))
                if source_section_ids.intersection(target_section_ids):
                    relation_types.append("same_section")

                if not relation_types:
                    relation_types.append("same_document")

                for relation_type in relation_types:
                    relation_rows.append(
                        {
                            "relation_id": stable_uuid(
                                f"corpus-relation:{source['chunk_id']}:{target['chunk_id']}:{relation_type}"
                            ),
                            "source_chunk_id": str(source["chunk_id"]),
                            "target_chunk_id": str(target["chunk_id"]),
                            "relation_type": relation_type,
                            "distance_in_doc": distance,
                            "import_run_id": import_run_id,
                        }
                    )

    return relation_rows


def rows_equal_relation(existing: dict[str, Any], candidate: dict[str, Any]) -> bool:
    return existing["distance_in_doc"] == candidate["distance_in_doc"]


def import_relations(
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
    relation_rows = build_relation_rows(chunk_rows, import_run_id)
    existing_relations = fetch_existing_relations(
        connection,
        sorted({row["source_chunk_id"] for row in relation_rows}),
    )

    stats = ImportStats()
    upserts: list[tuple[Any, ...]] = []

    for row in relation_rows:
        key = (row["source_chunk_id"], row["target_chunk_id"], row["relation_type"])
        existing = existing_relations.get(key)
        if existing and rows_equal_relation(existing, row):
            stats.skipped += 1
            continue
        if existing:
            stats.updated += 1
        else:
            stats.inserted += 1
        stats.preview_ids.append(str(row["relation_id"]))
        upserts.append(
            (
                row["relation_id"],
                row["source_chunk_id"],
                row["target_chunk_id"],
                row["relation_type"],
                row["distance_in_doc"],
                row["import_run_id"],
            )
        )

    if options.dry_run or not upserts:
        return stats

    sql = """
        INSERT INTO corpus_chunk_relations (
            relation_id, source_chunk_id, target_chunk_id, relation_type, distance_in_doc, import_run_id, created_at
        ) VALUES (%s, %s, %s, %s, %s, %s, NOW())
        ON CONFLICT (source_chunk_id, target_chunk_id, relation_type) DO UPDATE
        SET distance_in_doc = EXCLUDED.distance_in_doc,
            import_run_id = EXCLUDED.import_run_id
    """
    with connection.cursor() as cursor:
        for batch in batch_iterable(upserts, options.batch_size):
            cursor.executemany(sql, batch)

    return stats
