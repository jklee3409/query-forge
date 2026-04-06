from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import psycopg

try:
    from loaders.common import (
        ImportOptions,
        ImportStats,
        batch_iterable,
        checksum_text,
        ensure_source_rows,
        filter_rows,
        jsonb,
        load_jsonl,
        load_raw_documents,
        load_sources,
    )
except ModuleNotFoundError:  # pragma: no cover - direct module execution fallback
    from pipeline.loaders.common import (
        ImportOptions,
        ImportStats,
        batch_iterable,
        checksum_text,
        ensure_source_rows,
        filter_rows,
        jsonb,
        load_jsonl,
        load_raw_documents,
        load_sources,
    )


@dataclass(slots=True)
class DocumentImportResult:
    source_stats: ImportStats
    document_stats: ImportStats
    section_stats: ImportStats

    def to_dict(self) -> dict[str, Any]:
        return {
            "sources": self.source_stats.to_dict(),
            "documents": self.document_stats.to_dict(),
            "sections": self.section_stats.to_dict(),
        }


def infer_source_id(
    section: dict[str, Any],
    raw_document: dict[str, Any] | None,
    source_configs: dict[str, Any],
) -> str:
    if raw_document and raw_document.get("source_id"):
        return str(raw_document["source_id"])

    product = str(section.get("product") or "")
    matches = [config.source_id for config in source_configs.values() if config.product == product]
    if len(matches) == 1:
        return matches[0]
    if matches:
        return matches[0]
    return f"{product}-reference"


def count_block_types(structural_blocks: list[dict[str, Any]]) -> tuple[int, int, int]:
    code_count = 0
    table_count = 0
    list_count = 0
    for block in structural_blocks:
        block_type = str(block.get("type") or "")
        if block_type == "code":
            code_count += 1
        elif block_type == "table":
            table_count += 1
        elif block_type == "list":
            list_count += 1
    return code_count, table_count, list_count


def build_document_rows(
    *,
    sections: list[dict[str, Any]],
    raw_documents: dict[str, dict[str, Any]],
    source_configs: dict[str, Any],
    import_run_id: str | None,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    sections_by_document: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for section in sections:
        sections_by_document[str(section["document_id"])].append(section)

    document_rows: list[dict[str, Any]] = []
    section_rows: list[dict[str, Any]] = []

    normalized_at = datetime.now(timezone.utc).isoformat()

    for document_id, document_sections in sections_by_document.items():
        raw_document = raw_documents.get(document_id)
        first_section = document_sections[0]
        source_id = infer_source_id(first_section, raw_document, source_configs)
        ordered_sections = list(document_sections)
        section_by_path = {
            str(section["section_path"]): str(section["section_id"])
            for section in ordered_sections
        }

        aggregated_raw = "\n\n".join(str(section.get("raw_text") or "") for section in ordered_sections).strip()
        aggregated_cleaned = "\n\n".join(str(section.get("cleaned_text") or "") for section in ordered_sections).strip()
        heading_hierarchy = list(first_section.get("heading_hierarchy") or [])

        document_rows.append(
            {
                "document_id": document_id,
                "source_id": source_id,
                "product_name": str(first_section.get("product") or raw_document.get("product") if raw_document else ""),
                "version_label": str(first_section.get("version_if_available") or raw_document.get("version_if_available") if raw_document else "") or None,
                "canonical_url": str(raw_document.get("canonical_url") if raw_document else first_section.get("source_url") or ""),
                "title": str(raw_document.get("title") if raw_document else first_section.get("document_title") or first_section.get("title") or document_id),
                "section_path_text": str(first_section.get("section_path") or ""),
                "heading_hierarchy_json": heading_hierarchy,
                "raw_checksum": checksum_text(aggregated_raw),
                "cleaned_checksum": checksum_text(aggregated_cleaned),
                "raw_text": aggregated_raw,
                "cleaned_text": aggregated_cleaned,
                "language_code": str(raw_document.get("language_code") if raw_document else "en"),
                "content_type": str(
                    (raw_document.get("metadata", {}) if raw_document else {}).get("collection_format")
                    or "html"
                ),
                "collected_at": str(raw_document.get("fetched_at") if raw_document else normalized_at),
                "normalized_at": normalized_at,
                "is_active": True,
                "superseded_by_document_id": None,
                "import_run_id": import_run_id,
                "metadata_json": {
                    "source_url": first_section.get("source_url"),
                    "versioned_url": raw_document.get("versioned_url") if raw_document else None,
                    "source_record_title": raw_document.get("title") if raw_document else None,
                    "section_count": len(ordered_sections),
                },
            }
        )

        for section_order, section in enumerate(ordered_sections):
            code_count, table_count, list_count = count_block_types(section.get("structural_blocks", []))
            section_path = str(section["section_path"])
            parent_section_id = None
            if " > " in section_path:
                parent_path = section_path.rsplit(" > ", 1)[0]
                parent_section_id = section_by_path.get(parent_path)

            section_rows.append(
                {
                    "section_id": str(section["section_id"]),
                    "document_id": document_id,
                    "parent_section_id": parent_section_id,
                    "heading_level": section.get("heading_level"),
                    "heading_text": str(section.get("section_title") or section.get("title") or section_path),
                    "section_order": section_order,
                    "section_path_text": section_path,
                    "content_text": str(section.get("cleaned_text") or ""),
                    "code_block_count": code_count,
                    "table_count": table_count,
                    "list_count": list_count,
                    "import_run_id": import_run_id,
                    "structural_blocks_json": section.get("structural_blocks", []),
                }
            )

    return document_rows, section_rows


def fetch_existing_documents(
    connection: psycopg.Connection[Any],
    document_ids: list[str],
) -> dict[str, dict[str, Any]]:
    if not document_ids:
        return {}
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT document_id, source_id, product_name, version_label, canonical_url, title,
                   section_path_text, heading_hierarchy_json, raw_checksum, cleaned_checksum,
                   language_code, content_type, is_active, superseded_by_document_id,
                   metadata_json
            FROM corpus_documents
            WHERE document_id = ANY(%s)
            """,
            (document_ids,),
        )
        return {str(row["document_id"]): row for row in cursor.fetchall()}


def fetch_existing_sections(
    connection: psycopg.Connection[Any],
    section_ids: list[str],
) -> dict[str, dict[str, Any]]:
    if not section_ids:
        return {}
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT section_id, document_id, parent_section_id, heading_level, heading_text,
                   section_order, section_path_text, content_text, code_block_count,
                   table_count, list_count, structural_blocks_json
            FROM corpus_sections
            WHERE section_id = ANY(%s)
            """,
            (section_ids,),
        )
        return {str(row["section_id"]): row for row in cursor.fetchall()}


def fetch_active_documents_by_url(
    connection: psycopg.Connection[Any],
    canonical_urls: list[str],
) -> dict[str, str]:
    if not canonical_urls:
        return {}
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT canonical_url, document_id
            FROM corpus_documents
            WHERE canonical_url = ANY(%s)
              AND is_active = TRUE
            """,
            (canonical_urls,),
        )
        return {str(row["canonical_url"]): str(row["document_id"]) for row in cursor.fetchall()}


def rows_equal_document(existing: dict[str, Any], candidate: dict[str, Any]) -> bool:
    return all(
        [
            existing["source_id"] == candidate["source_id"],
            existing["product_name"] == candidate["product_name"],
            existing["version_label"] == candidate["version_label"],
            existing["canonical_url"] == candidate["canonical_url"],
            existing["title"] == candidate["title"],
            existing["section_path_text"] == candidate["section_path_text"],
            list(existing["heading_hierarchy_json"]) == candidate["heading_hierarchy_json"],
            existing["raw_checksum"] == candidate["raw_checksum"],
            existing["cleaned_checksum"] == candidate["cleaned_checksum"],
            existing["language_code"] == candidate["language_code"],
            existing["content_type"] == candidate["content_type"],
            bool(existing["is_active"]) == bool(candidate["is_active"]),
            existing["superseded_by_document_id"] == candidate["superseded_by_document_id"],
            dict(existing["metadata_json"]) == candidate["metadata_json"],
        ]
    )


def rows_equal_section(existing: dict[str, Any], candidate: dict[str, Any]) -> bool:
    return all(
        [
            existing["document_id"] == candidate["document_id"],
            existing["parent_section_id"] == candidate["parent_section_id"],
            existing["heading_level"] == candidate["heading_level"],
            existing["heading_text"] == candidate["heading_text"],
            existing["section_order"] == candidate["section_order"],
            existing["section_path_text"] == candidate["section_path_text"],
            existing["content_text"] == candidate["content_text"],
            existing["code_block_count"] == candidate["code_block_count"],
            existing["table_count"] == candidate["table_count"],
            existing["list_count"] == candidate["list_count"],
            list(existing["structural_blocks_json"]) == candidate["structural_blocks_json"],
        ]
    )


def import_documents(
    connection: psycopg.Connection[Any],
    *,
    options: ImportOptions,
    import_run_id: str | None,
) -> DocumentImportResult:
    raw_documents = load_raw_documents(options.raw_input_path)
    source_configs = load_sources(options.source_config_dir, options.source_ids or None)
    source_stats = ensure_source_rows(
        connection,
        source_configs,
        batch_size=options.batch_size,
        dry_run=options.dry_run,
    )

    sections = filter_rows(
        load_jsonl(options.sections_input_path),
        source_ids=options.source_ids,
        document_ids=options.document_ids,
        raw_documents=raw_documents,
    )
    document_rows, section_rows = build_document_rows(
        sections=sections,
        raw_documents=raw_documents,
        source_configs=source_configs,
        import_run_id=import_run_id,
    )

    existing_documents = fetch_existing_documents(connection, [row["document_id"] for row in document_rows])
    existing_sections = fetch_existing_sections(connection, [row["section_id"] for row in section_rows])
    active_document_urls = fetch_active_documents_by_url(
        connection,
        [row["canonical_url"] for row in document_rows if row["canonical_url"]],
    )

    document_stats = ImportStats()
    section_stats = ImportStats()
    document_upserts: list[tuple[Any, ...]] = []
    section_upserts: list[tuple[Any, ...]] = []
    supersede_updates: list[tuple[str, str]] = []

    for row in document_rows:
        existing = existing_documents.get(row["document_id"])
        if existing and rows_equal_document(existing, row):
            document_stats.skipped += 1
            continue

        if existing:
            document_stats.updated += 1
        else:
            document_stats.inserted += 1

        document_stats.preview_ids.append(row["document_id"])
        active_document_id = active_document_urls.get(row["canonical_url"])
        if active_document_id and active_document_id != row["document_id"]:
            supersede_updates.append((row["document_id"], active_document_id))

        document_upserts.append(
            (
                row["document_id"],
                row["source_id"],
                row["product_name"],
                row["version_label"],
                row["canonical_url"],
                row["title"],
                row["section_path_text"],
                jsonb(row["heading_hierarchy_json"]),
                row["raw_checksum"],
                row["cleaned_checksum"],
                row["raw_text"],
                row["cleaned_text"],
                row["language_code"],
                row["content_type"],
                row["collected_at"],
                row["normalized_at"],
                row["is_active"],
                row["superseded_by_document_id"],
                row["import_run_id"],
                jsonb(row["metadata_json"]),
            )
        )

    for row in section_rows:
        existing = existing_sections.get(row["section_id"])
        if existing and rows_equal_section(existing, row):
            section_stats.skipped += 1
            continue

        if existing:
            section_stats.updated += 1
        else:
            section_stats.inserted += 1
        section_stats.preview_ids.append(row["section_id"])
        section_upserts.append(
            (
                row["section_id"],
                row["document_id"],
                row["parent_section_id"],
                row["heading_level"],
                row["heading_text"],
                row["section_order"],
                row["section_path_text"],
                row["content_text"],
                row["code_block_count"],
                row["table_count"],
                row["list_count"],
                row["import_run_id"],
                jsonb(row["structural_blocks_json"]),
            )
        )

    if options.dry_run:
        return DocumentImportResult(source_stats, document_stats, section_stats)

    if supersede_updates:
        with connection.cursor() as cursor:
            cursor.executemany(
                """
                UPDATE corpus_documents
                SET is_active = FALSE,
                    superseded_by_document_id = %s,
                    updated_at = NOW()
                WHERE document_id = %s
                """,
                supersede_updates,
            )

    if document_upserts:
        sql = """
            INSERT INTO corpus_documents (
                document_id, source_id, product_name, version_label, canonical_url, title,
                section_path_text, heading_hierarchy_json, raw_checksum, cleaned_checksum,
                raw_text, cleaned_text, language_code, content_type, collected_at,
                normalized_at, is_active, superseded_by_document_id, import_run_id,
                metadata_json, created_at, updated_at
            ) VALUES (
                %s, %s, %s, %s, %s, %s,
                %s, %s, %s, %s,
                %s, %s, %s, %s, %s,
                %s, %s, %s, %s,
                %s, NOW(), NOW()
            )
            ON CONFLICT (document_id) DO UPDATE
            SET source_id = EXCLUDED.source_id,
                product_name = EXCLUDED.product_name,
                version_label = EXCLUDED.version_label,
                canonical_url = EXCLUDED.canonical_url,
                title = EXCLUDED.title,
                section_path_text = EXCLUDED.section_path_text,
                heading_hierarchy_json = EXCLUDED.heading_hierarchy_json,
                raw_checksum = EXCLUDED.raw_checksum,
                cleaned_checksum = EXCLUDED.cleaned_checksum,
                raw_text = EXCLUDED.raw_text,
                cleaned_text = EXCLUDED.cleaned_text,
                language_code = EXCLUDED.language_code,
                content_type = EXCLUDED.content_type,
                collected_at = EXCLUDED.collected_at,
                normalized_at = EXCLUDED.normalized_at,
                is_active = EXCLUDED.is_active,
                superseded_by_document_id = EXCLUDED.superseded_by_document_id,
                import_run_id = EXCLUDED.import_run_id,
                metadata_json = EXCLUDED.metadata_json,
                updated_at = NOW()
        """
        with connection.cursor() as cursor:
            for batch in batch_iterable(document_upserts, options.batch_size):
                cursor.executemany(sql, batch)

    if section_upserts:
        sql = """
            INSERT INTO corpus_sections (
                section_id, document_id, parent_section_id, heading_level, heading_text,
                section_order, section_path_text, content_text, code_block_count,
                table_count, list_count, import_run_id, structural_blocks_json,
                created_at, updated_at
            ) VALUES (
                %s, %s, %s, %s, %s,
                %s, %s, %s, %s,
                %s, %s, %s, %s,
                NOW(), NOW()
            )
            ON CONFLICT (section_id) DO UPDATE
            SET document_id = EXCLUDED.document_id,
                parent_section_id = EXCLUDED.parent_section_id,
                heading_level = EXCLUDED.heading_level,
                heading_text = EXCLUDED.heading_text,
                section_order = EXCLUDED.section_order,
                section_path_text = EXCLUDED.section_path_text,
                content_text = EXCLUDED.content_text,
                code_block_count = EXCLUDED.code_block_count,
                table_count = EXCLUDED.table_count,
                list_count = EXCLUDED.list_count,
                import_run_id = EXCLUDED.import_run_id,
                structural_blocks_json = EXCLUDED.structural_blocks_json,
                updated_at = NOW()
        """
        with connection.cursor() as cursor:
            for batch in batch_iterable(section_upserts, options.batch_size):
                cursor.executemany(sql, batch)

    return DocumentImportResult(source_stats, document_stats, section_stats)
