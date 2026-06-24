from __future__ import annotations

import logging
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import psycopg
from psycopg.types.json import Jsonb

try:
    from common.anchor_normalization import (
        DEFAULT_MAPPING_VERSION as ANCHOR_MAPPING_VERSION,
        DEFAULT_NORMALIZATION_VERSION as ANCHOR_NORMALIZATION_VERSION,
        resolve_canonical_anchors,
    )
    from common.embeddings import embedding_to_halfvec_literal
    from common.experiment_config import load_experiment_config
    from common.experiment_run import ExperimentRunRecorder
    from common.local_retriever import (
        build_retriever_config,
        embed_query_with_retriever_config,
    )
    from loaders.common import connect, default_database_args
except ModuleNotFoundError:  # pragma: no cover - direct module execution fallback
    from pipeline.common.anchor_normalization import (
        DEFAULT_MAPPING_VERSION as ANCHOR_MAPPING_VERSION,
        DEFAULT_NORMALIZATION_VERSION as ANCHOR_NORMALIZATION_VERSION,
        resolve_canonical_anchors,
    )
    from pipeline.common.embeddings import embedding_to_halfvec_literal
    from pipeline.common.experiment_config import load_experiment_config
    from pipeline.common.experiment_run import ExperimentRunRecorder
    from pipeline.common.local_retriever import (
        build_retriever_config,
        embed_query_with_retriever_config,
    )
    from pipeline.loaders.common import connect, default_database_args


LOGGER = logging.getLogger(__name__)
TARGET_EMBEDDING_DIMENSION = 384

STAGE_CUTOFF_RULE_ONLY = "rule_only"
STAGE_CUTOFF_RULE_PLUS_LLM = "rule_plus_llm"
STAGE_CUTOFF_UTILITY = "utility"
STAGE_CUTOFF_DIVERSITY = "diversity"
STAGE_CUTOFF_FULL_GATING = "full_gating"


@dataclass(slots=True)
class GatedRow:
    gated_query_id: str
    synthetic_query_id: str
    domain_id: str | None
    query_text: str
    query_type: str
    query_language: str
    language_profile: str | None
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
                   g.domain_id::text AS domain_id,
                   r.query_text,
                   r.query_type,
                   r.query_language,
                   r.language_profile,
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
            domain_id=str(row["domain_id"]) if row["domain_id"] else None,
            query_text=str(row["query_text"]),
            query_type=str(row["query_type"]),
            query_language=str(row["query_language"] or ""),
            language_profile=row["language_profile"],
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
                   COALESCE(g.domain_id, gr.domain_id)::text AS domain_id,
                   r.query_text,
                   r.query_type,
                   r.query_language,
                   r.language_profile,
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
            domain_id=str(row["domain_id"]) if row["domain_id"] else None,
            query_text=str(row["query_text"]),
            query_type=str(row["query_type"]),
            query_language=str(row["query_language"] or ""),
            language_profile=row["language_profile"],
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
                   COALESCE(full_gated.domain_id, gr.domain_id)::text AS domain_id,
                   r.query_text,
                   r.query_type,
                   r.query_language,
                   r.language_profile,
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
            domain_id=str(row["domain_id"]) if row["domain_id"] else None,
            query_text=str(row["query_text"]),
            query_type=str(row["query_type"]),
            query_language=str(row["query_language"] or ""),
            language_profile=row["language_profile"],
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
                SELECT m.memory_id
                FROM memory_entries m
                WHERE {" AND ".join(conditions)}
                  AND ({" OR ".join(snapshot_conditions)})
            ),
            removed_retrieval_logs AS (
                DELETE FROM memory_retrieval_log mrl
                USING deleted d
                WHERE mrl.memory_id = d.memory_id
                RETURNING 1
            ),
            removed_embeddings AS (
                DELETE FROM query_embeddings qe
                USING deleted d
                WHERE qe.owner_type = 'memory'
                  AND qe.owner_id = d.memory_id::text
                RETURNING 1
            ),
            removed_memory AS (
                DELETE FROM memory_entries m
                USING deleted d
                WHERE m.memory_id = d.memory_id
                RETURNING m.memory_id
            )
            SELECT COUNT(*) AS deleted_count
            FROM removed_memory
            """,
            parameters,
        )
        row = cursor.fetchone()
    if row is None:
        return 0
    return int(row["deleted_count"] or 0)


def _load_glossary_term_candidates(
    connection: psycopg.Connection[Any],
    *,
    document_ids: set[str] | None = None,
) -> dict[str, list[dict[str, Any]]]:
    candidates_by_doc: dict[str, list[dict[str, Any]]] = {}
    selected_document_ids = sorted(document_ids or [])
    if document_ids is not None and not selected_document_ids:
        return candidates_by_doc
    with connection.cursor() as cursor:
        if selected_document_ids:
            cursor.execute(
                """
                SELECT term_id,
                       first_seen_document_id,
                       canonical_form,
                       normalized_form,
                       term_type,
                       is_active
                FROM corpus_glossary_terms
                WHERE is_active = TRUE
                  AND first_seen_document_id = ANY(%s)
                ORDER BY evidence_count DESC, canonical_form, term_id
                """,
                (selected_document_ids,),
            )
        else:
            cursor.execute(
                """
                SELECT term_id,
                       first_seen_document_id,
                       canonical_form,
                       normalized_form,
                       term_type,
                       is_active
                FROM corpus_glossary_terms
                WHERE is_active = TRUE
                  AND first_seen_document_id IS NOT NULL
                ORDER BY evidence_count DESC, canonical_form, term_id
                """
            )
        for row in cursor.fetchall():
            document_id = str(row["first_seen_document_id"])
            candidates_by_doc.setdefault(document_id, []).append(
                {
                    "term_id": str(row["term_id"]),
                    "canonical_form": str(row["canonical_form"]),
                    "normalized_form": str(row["normalized_form"] or ""),
                    "term_type": str(row["term_type"]),
                    "is_active": bool(row["is_active"]),
                }
            )
    return candidates_by_doc


def _insert_memory_entry(
    cursor: Any,
    *,
    memory_id: str,
    row: GatedRow,
    embedding_literal: str,
    memory_metadata: dict[str, Any],
) -> None:
    cursor.execute(
        """
        INSERT INTO memory_entries (
            memory_id,
            source_gated_query_id,
            domain_id,
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
            %s, %s, %s, %s, %s, %s, %s, %s, %s, %s,
            %s, %s, %s, %s, %s, %s, %s, CAST(%s AS halfvec), %s
        )
        """,
        (
            memory_id,
            row.gated_query_id,
            row.domain_id,
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
            Jsonb(memory_metadata),
        ),
    )


def _canonical_mapping_table_available(connection: Any | None) -> bool:
    if connection is None or not hasattr(connection, "cursor"):
        return False
    try:
        with connection.transaction():
            with connection.cursor() as cursor:
                cursor.execute("SELECT to_regclass('canonical_anchor_mapping')")
                row = cursor.fetchone()
    except Exception:
        LOGGER.warning(
            "Canonical anchor mapping table availability check failed; continuing without mapping lookup.",
            exc_info=True,
        )
        return False
    if row is None:
        return False
    if isinstance(row, dict):
        return row.get("to_regclass") is not None
    return row[0] is not None


def _canonical_alias_language(*, query_language: str, language_profile: str | None) -> str:
    if str(language_profile or "").strip().lower() == "code_mixed":
        return "und"
    language = str(query_language or "").strip().lower()
    if language in {"en", "ko"}:
        return language
    return "und"


def _candidate_items_for_glossary_terms(
    *,
    glossary_terms: list[str],
    glossary_term_candidates: list[dict[str, Any]],
    alias_language: str,
) -> list[dict[str, Any]]:
    candidates_by_form: dict[str, list[dict[str, Any]]] = {}
    for candidate in glossary_term_candidates:
        canonical_form = str(candidate.get("canonical_form") or "").strip()
        term_type = str(candidate.get("term_type") or "").strip()
        if not canonical_form or not term_type:
            continue
        candidates_by_form.setdefault(canonical_form, []).append(candidate)

    items: list[dict[str, Any]] = []
    seen: set[tuple[str, str]] = set()
    for term in glossary_terms:
        display_term = str(term or "").strip()
        if not display_term:
            continue
        for candidate in candidates_by_form.get(display_term, []):
            term_type = str(candidate.get("term_type") or "").strip()
            key = (display_term, term_type)
            if not term_type or key in seen:
                continue
            seen.add(key)
            items.append(
                {
                    "alias_text": display_term,
                    "alias_language": alias_language,
                    "term_type": term_type,
                    "source_field": "glossary_terms",
                }
            )
    return items


def _empty_memory_canonical_anchor_payload(
    *,
    memory_id: str,
    synthetic_query_id: str,
    source_gated_query_id: str,
    query_language: str,
    language_profile: str | None,
    generation_strategy: str,
) -> dict[str, Any]:
    return resolve_canonical_anchors(
        [],
        mapping_version=ANCHOR_MAPPING_VERSION,
        normalization_version=ANCHOR_NORMALIZATION_VERSION,
        source_context={
            "kind": "memory_entry",
            "source_id": memory_id,
            "source_field": "query",
            "synthetic_query_id": synthetic_query_id,
            "source_gated_query_id": source_gated_query_id,
            "query_language": query_language,
            "language_profile": language_profile,
            "generation_strategy": generation_strategy,
        },
    )


def _build_memory_canonical_anchor_payload(
    *,
    connection: Any | None,
    memory_id: str,
    source_gated_query_id: str,
    synthetic_query_id: str,
    query_language: str,
    language_profile: str | None,
    generation_strategy: str,
    glossary_terms: list[str],
    glossary_term_candidates: list[dict[str, Any]] | None,
) -> dict[str, Any]:
    candidates = list(glossary_term_candidates or [])
    alias_language = _canonical_alias_language(
        query_language=query_language,
        language_profile=language_profile,
    )
    items = _candidate_items_for_glossary_terms(
        glossary_terms=glossary_terms,
        glossary_term_candidates=candidates,
        alias_language=alias_language,
    )
    source_context = {
        "kind": "memory_entry",
        "source_id": memory_id,
        "source_field": "query",
        "synthetic_query_id": synthetic_query_id,
        "source_gated_query_id": source_gated_query_id,
        "query_language": query_language,
        "language_profile": language_profile,
        "generation_strategy": generation_strategy,
    }
    try:
        if connection is not None:
            with connection.transaction():
                return resolve_canonical_anchors(
                    items,
                    connection=connection,
                    mapping_version=ANCHOR_MAPPING_VERSION,
                    normalization_version=ANCHOR_NORMALIZATION_VERSION,
                    source_context=source_context,
                    fallback_term_candidates=candidates,
                )
        return resolve_canonical_anchors(
            items,
            connection=None,
            mapping_version=ANCHOR_MAPPING_VERSION,
            normalization_version=ANCHOR_NORMALIZATION_VERSION,
            source_context=source_context,
            fallback_term_candidates=candidates,
        )
    except Exception:
        LOGGER.warning(
            "Canonical anchor resolution failed for memory_id=%s; storing empty fail-closed payload.",
            memory_id,
            exc_info=True,
        )
        return _empty_memory_canonical_anchor_payload(
            memory_id=memory_id,
            synthetic_query_id=synthetic_query_id,
            source_gated_query_id=source_gated_query_id,
            query_language=query_language,
            language_profile=language_profile,
            generation_strategy=generation_strategy,
        )


def _with_canonical_anchor_metadata(
    metadata: dict[str, Any],
    *,
    connection: Any | None,
    memory_id: str,
    source_gated_query_id: str,
    synthetic_query_id: str,
    query_language: str,
    language_profile: str | None,
    generation_strategy: str,
    glossary_terms: list[str],
    glossary_term_candidates: list[dict[str, Any]] | None,
) -> dict[str, Any]:
    canonical_anchors = _build_memory_canonical_anchor_payload(
        connection=connection,
        memory_id=memory_id,
        source_gated_query_id=source_gated_query_id,
        synthetic_query_id=synthetic_query_id,
        query_language=query_language,
        language_profile=language_profile,
        generation_strategy=generation_strategy,
        glossary_terms=glossary_terms,
        glossary_term_candidates=glossary_term_candidates,
    )
    return {
        **metadata,
        "canonical_anchors": canonical_anchors,
        "anchor_mapping_version": ANCHOR_MAPPING_VERSION,
        "anchor_normalization_version": ANCHOR_NORMALIZATION_VERSION,
    }


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
    embedding_dim = len(embedding_values)
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
                %s, %s, %s, %s, CAST(%s AS halfvec), %s
            )
            ON CONFLICT (owner_type, owner_id, embedding_model) DO UPDATE
            SET embedding = EXCLUDED.embedding,
                metadata = EXCLUDED.metadata
            """,
            (owner_type, owner_id, embedding_model, embedding_dim, literal, Jsonb(metadata)),
        )


def _embed_memory_query(
    text: str,
    *,
    retriever_config: Any,
    require_real_dense: bool,
) -> tuple[list[float], str, bool]:
    embedding, embedding_model, fallback_used = embed_query_with_retriever_config(
        text,
        retriever_config=retriever_config,
        require_real_dense=require_real_dense,
    )
    if len(embedding) != TARGET_EMBEDDING_DIMENSION:
        if len(embedding) > TARGET_EMBEDDING_DIMENSION:
            embedding = embedding[:TARGET_EMBEDDING_DIMENSION]
        else:
            embedding = embedding + ([0.0] * (TARGET_EMBEDDING_DIMENSION - len(embedding)))
    return embedding, embedding_model, fallback_used


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
        retriever_config = build_retriever_config(config.raw)
        summary_embedding_model = retriever_config.dense_embedding_model
        retrieval_backend = str(config.raw.get("retrieval_backend") or "local").strip().lower().replace("-", "_")
        require_real_dense = retrieval_backend == "db_ann"
        canonical_mapping_connection = connection if _canonical_mapping_table_available(connection) else None
        memory_embedding_fallback_used = False
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
                raise RuntimeError(
                    "source_gating_batch_id is required for deterministic snapshot loading "
                    "(auto-latest snapshot selection is disabled)"
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
            glossary_term_candidates_by_doc = _load_glossary_term_candidates(
                connection,
                document_ids={row.target_doc_id for row in rows},
            )
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
                embedding, embedding_model, fallback_used = _embed_memory_query(
                    row.query_text,
                    retriever_config=retriever_config,
                    require_real_dense=require_real_dense,
                )
                memory_embedding_fallback_used = memory_embedding_fallback_used or fallback_used
                if require_real_dense and fallback_used:
                    raise RuntimeError("db-ann memory build must not fall back to hash-embedding-v1")
                summary_embedding_model = embedding_model
                embedding_literal = embedding_to_halfvec_literal(embedding)
                memory_metadata = _with_canonical_anchor_metadata(
                    {
                        "gating_preset": preset,
                        "source_gate_run_id": resolved_source_run_id,
                        "source_gating_batch_id": source_batch_id,
                        "stage_cutoff_enabled": stage_cutoff_enabled,
                        "stage_cutoff_level": stage_cutoff_level if stage_cutoff_enabled else None,
                        "stage_cutoff_source_gating_batch_id": source_batch_id if stage_cutoff_enabled else None,
                        "memory_build_run_id": run_context.experiment_run_id,
                        "memory_experiment_key": config.experiment_key,
                        "embedding_model": embedding_model,
                        "retrieval_backend": retrieval_backend,
                        "fallback_used": fallback_used,
                    },
                    connection=canonical_mapping_connection,
                    memory_id=memory_id,
                    source_gated_query_id=row.gated_query_id,
                    synthetic_query_id=row.synthetic_query_id,
                    query_language=row.query_language,
                    language_profile=row.language_profile,
                    generation_strategy=row.generation_strategy,
                    glossary_terms=row.glossary_terms,
                    glossary_term_candidates=glossary_term_candidates_by_doc.get(row.target_doc_id, []),
                )
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
                    _insert_memory_entry(
                        cursor,
                        memory_id=memory_id,
                        row=row,
                        embedding_literal=embedding_literal,
                        memory_metadata=memory_metadata,
                    )

                _upsert_query_embedding(
                    connection,
                    owner_type="memory",
                    owner_id=memory_id,
                    embedding_model=embedding_model,
                    embedding_values=embedding,
                    metadata={"source_gated_query_id": row.gated_query_id},
                )
                _upsert_query_embedding(
                    connection,
                    owner_type="synthetic_gated",
                    owner_id=row.gated_query_id,
                    embedding_model=embedding_model,
                    embedding_values=embedding,
                    metadata={"synthetic_query_id": row.synthetic_query_id},
                )
                _upsert_query_embedding(
                    connection,
                    owner_type="synthetic_raw",
                    owner_id=row.synthetic_query_id,
                    embedding_model=embedding_model,
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
            "embedding_model": summary_embedding_model,
            "retrieval_backend": retrieval_backend,
            "vector_store": "postgresql-pgvector" if retrieval_backend == "db_ann" else None,
            "fallback_used": memory_embedding_fallback_used,
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
