from __future__ import annotations

from typing import Any

import psycopg


def sync_shadow_tables(connection: psycopg.Connection[Any]) -> None:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            INSERT INTO documents (
                document_id,
                document_family,
                source_url,
                product,
                version_if_available,
                title,
                source_format,
                heading_hierarchy,
                source_hash,
                raw_text,
                cleaned_text,
                language_code,
                metadata,
                created_at,
                updated_at
            )
            SELECT d.document_id,
                   split_part(d.document_id, '::', 1) AS document_family,
                   d.canonical_url AS source_url,
                   d.product_name AS product,
                   d.version_label AS version_if_available,
                   d.title,
                   d.content_type AS source_format,
                   d.heading_hierarchy_json AS heading_hierarchy,
                   d.raw_checksum AS source_hash,
                   d.raw_text,
                   d.cleaned_text,
                   d.language_code,
                   d.metadata_json AS metadata,
                   d.created_at,
                   d.updated_at
            FROM corpus_documents d
            ON CONFLICT (document_id) DO UPDATE
            SET source_url = EXCLUDED.source_url,
                product = EXCLUDED.product,
                version_if_available = EXCLUDED.version_if_available,
                title = EXCLUDED.title,
                source_format = EXCLUDED.source_format,
                heading_hierarchy = EXCLUDED.heading_hierarchy,
                source_hash = EXCLUDED.source_hash,
                raw_text = EXCLUDED.raw_text,
                cleaned_text = EXCLUDED.cleaned_text,
                language_code = EXCLUDED.language_code,
                metadata = EXCLUDED.metadata,
                updated_at = EXCLUDED.updated_at
            """
        )

        cursor.execute(
            """
            INSERT INTO sections (
                section_id,
                document_id,
                parent_section_id,
                source_url,
                title,
                section_path,
                heading_level,
                heading_hierarchy,
                raw_text,
                cleaned_text,
                structural_blocks,
                dedupe_hash,
                metadata,
                created_at,
                updated_at
            )
            SELECT s.section_id,
                   s.document_id,
                   s.parent_section_id,
                   d.canonical_url AS source_url,
                   s.heading_text AS title,
                   s.section_path_text AS section_path,
                   s.heading_level,
                   jsonb_build_array(s.section_path_text) AS heading_hierarchy,
                   s.content_text AS raw_text,
                   s.content_text AS cleaned_text,
                   s.structural_blocks_json AS structural_blocks,
                   md5(s.content_text) AS dedupe_hash,
                   '{}'::jsonb AS metadata,
                   s.created_at,
                   s.updated_at
            FROM corpus_sections s
            JOIN corpus_documents d ON d.document_id = s.document_id
            ON CONFLICT (section_id) DO UPDATE
            SET document_id = EXCLUDED.document_id,
                parent_section_id = EXCLUDED.parent_section_id,
                source_url = EXCLUDED.source_url,
                title = EXCLUDED.title,
                section_path = EXCLUDED.section_path,
                heading_level = EXCLUDED.heading_level,
                heading_hierarchy = EXCLUDED.heading_hierarchy,
                raw_text = EXCLUDED.raw_text,
                cleaned_text = EXCLUDED.cleaned_text,
                structural_blocks = EXCLUDED.structural_blocks,
                dedupe_hash = EXCLUDED.dedupe_hash,
                updated_at = EXCLUDED.updated_at
            """
        )

        cursor.execute(
            """
            INSERT INTO chunks (
                chunk_id,
                document_id,
                section_id,
                chunk_index_in_doc,
                section_path,
                content,
                char_len,
                token_len,
                previous_chunk_id,
                next_chunk_id,
                code_presence,
                product,
                version_if_available,
                metadata,
                created_at,
                updated_at
            )
            SELECT c.chunk_id,
                   c.document_id,
                   s.section_id,
                   c.chunk_index_in_document AS chunk_index_in_doc,
                   c.section_path_text AS section_path,
                   c.chunk_text AS content,
                   c.char_len,
                   c.token_len,
                   c.previous_chunk_id,
                   c.next_chunk_id,
                   c.code_presence,
                   c.product_name AS product,
                   c.version_label AS version_if_available,
                   c.metadata_json AS metadata,
                   c.created_at,
                   c.updated_at
            FROM corpus_chunks c
            JOIN corpus_documents d ON d.document_id = c.document_id
            LEFT JOIN sections s ON s.section_id = c.section_id
            ON CONFLICT (chunk_id) DO UPDATE
            SET document_id = EXCLUDED.document_id,
                section_id = EXCLUDED.section_id,
                chunk_index_in_doc = EXCLUDED.chunk_index_in_doc,
                section_path = EXCLUDED.section_path,
                content = EXCLUDED.content,
                char_len = EXCLUDED.char_len,
                token_len = EXCLUDED.token_len,
                previous_chunk_id = EXCLUDED.previous_chunk_id,
                next_chunk_id = EXCLUDED.next_chunk_id,
                code_presence = EXCLUDED.code_presence,
                product = EXCLUDED.product,
                version_if_available = EXCLUDED.version_if_available,
                metadata = EXCLUDED.metadata,
                updated_at = EXCLUDED.updated_at
            """
        )

        cursor.execute(
            """
            INSERT INTO chunk_neighbors (
                source_chunk_id,
                neighbor_chunk_id,
                neighbor_type,
                distance,
                metadata,
                created_at
            )
            SELECT r.source_chunk_id,
                   r.target_chunk_id AS neighbor_chunk_id,
                   CASE WHEN r.relation_type = 'far' THEN 'far' ELSE 'near' END AS neighbor_type,
                   COALESCE(r.distance_in_doc, 1) AS distance,
                   '{}'::jsonb AS metadata,
                   r.created_at
            FROM corpus_chunk_relations r
            JOIN chunks source_chunk ON source_chunk.chunk_id = r.source_chunk_id
            JOIN chunks target_chunk ON target_chunk.chunk_id = r.target_chunk_id
            WHERE r.relation_type IN ('near', 'far')
            ON CONFLICT (source_chunk_id, neighbor_chunk_id, neighbor_type) DO UPDATE
            SET distance = EXCLUDED.distance,
                created_at = EXCLUDED.created_at
            """
        )

        cursor.execute(
            """
            INSERT INTO glossary_terms (
                term_type,
                canonical_form,
                aliases,
                keep_in_english,
                source_product,
                metadata,
                created_at
            )
            SELECT gt.term_type,
                   gt.canonical_form,
                   COALESCE(
                       (
                           SELECT jsonb_agg(a.alias_text ORDER BY a.alias_text)
                           FROM corpus_glossary_aliases a
                           WHERE a.term_id = gt.term_id
                       ),
                       '[]'::jsonb
                   ) AS aliases,
                   gt.keep_in_english,
                   d.product_name AS source_product,
                   gt.metadata_json AS metadata,
                   gt.created_at
            FROM corpus_glossary_terms gt
            LEFT JOIN corpus_documents d ON d.document_id = gt.first_seen_document_id
            ON CONFLICT (term_type, canonical_form) DO UPDATE
            SET aliases = EXCLUDED.aliases,
                keep_in_english = EXCLUDED.keep_in_english,
                source_product = EXCLUDED.source_product,
                metadata = EXCLUDED.metadata
            """
        )
