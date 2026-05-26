from __future__ import annotations

import hashlib
import json
import logging
import random
import re
import uuid
from collections import Counter, defaultdict
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Any

import psycopg
from psycopg import sql
from psycopg.types.json import Jsonb

try:
    from common.corpus_shadow_sync import sync_shadow_tables
    from common.anchor_normalization import (
        DEFAULT_MAPPING_VERSION as ANCHOR_MAPPING_VERSION,
        DEFAULT_NORMALIZATION_VERSION as ANCHOR_NORMALIZATION_VERSION,
        resolve_canonical_anchors,
    )
    from common.experiment_config import ExperimentConfig, load_experiment_config
    from common.experiment_run import ExperimentRunRecorder
    from common.gemini_batch import (
        GeminiBatchAdapter,
        GeminiBatchExecutionError,
        GeminiBatchRequestItem,
        build_gemini_generate_content_request,
        parse_gemini_json_response,
    )
    from common.llm_client import LlmClient, load_stage_config
    from common.prompt_assets import PromptAsset, load_and_register_prompt
    from loaders.common import connect, default_database_args
except ModuleNotFoundError:  # pragma: no cover - direct module execution fallback
    from pipeline.common.corpus_shadow_sync import sync_shadow_tables
    from pipeline.common.anchor_normalization import (
        DEFAULT_MAPPING_VERSION as ANCHOR_MAPPING_VERSION,
        DEFAULT_NORMALIZATION_VERSION as ANCHOR_NORMALIZATION_VERSION,
        resolve_canonical_anchors,
    )
    from pipeline.common.experiment_config import ExperimentConfig, load_experiment_config
    from pipeline.common.experiment_run import ExperimentRunRecorder
    from pipeline.common.gemini_batch import (
        GeminiBatchAdapter,
        GeminiBatchExecutionError,
        GeminiBatchRequestItem,
        build_gemini_generate_content_request,
        parse_gemini_json_response,
    )
    from pipeline.common.llm_client import LlmClient, load_stage_config
    from pipeline.common.prompt_assets import PromptAsset, load_and_register_prompt
    from pipeline.loaders.common import connect, default_database_args


LOGGER = logging.getLogger(__name__)

TRANSLATION_SEGMENTATION_VERSION = "segmented-full-v1"
TRANSLATION_SEGMENT_TARGET_MAX_CHARS = 900


QUERY_TYPE_LABELS_KO: dict[str, str] = {
    "definition": "정의/개념형",
    "reason": "원인/이유형",
    "procedure": "절차/방법형",
    "comparison": "비교형",
    "short_user": "짧은 사용자형",
    "code_mixed": "code-mixed",
    "follow_up": "문맥 의존형 후속 질의",
}

SUMMARY_EN_RESPONSE_SCHEMA: dict[str, Any] = {
    "type": "object",
    "required": ["extractive_summary_en"],
    "properties": {
        "extractive_summary_en": {"type": "string"},
        "key_terms": {
            "type": "array",
            "items": {"type": "string"},
        },
    },
    "additionalProperties": True,
}

TRANSLATION_RESPONSE_SCHEMA: dict[str, Any] = {
    "type": "object",
    "required": ["translated_chunk_ko"],
    "properties": {
        "translated_chunk_ko": {"type": "string"},
    },
    "additionalProperties": True,
}

SUMMARY_KO_RESPONSE_SCHEMA: dict[str, Any] = {
    "type": "object",
    "required": ["summary_ko"],
    "properties": {
        "summary_ko": {"type": "string", "description": "2-3 concise Korean sentences, max 380 characters."},
        "grounding_note": {
            "type": "string",
            "enum": ["all claims grounded in input"],
            "description": "Always exactly: all claims grounded in input",
        },
    },
    "additionalProperties": True,
}

QUERY_BASE_RESPONSE_SCHEMA: dict[str, Any] = {
    "type": "object",
    "required": [],
    "properties": {
        "query": {"type": "string"},
        "query_ko": {"type": "string"},
        "query_en": {"type": "string"},
        "query_code_mixed": {"type": "string"},
        "query_type": {"type": "string"},
        "answerability_type": {"type": "string"},
        "style_note": {"type": "string"},
    },
    "additionalProperties": True,
}

QUERY_TEXT_FIELDS: tuple[str, ...] = ("query", "query_en", "query_ko", "query_code_mixed")

QUERY_REQUIRED_FIELDS_BY_STRATEGY: dict[str, tuple[str, ...]] = {
    "A": ("query_en", "query_ko"),
    "B": ("query_ko", "query_type", "answerability_type"),
    "C": ("query_ko",),
    "D": ("query_ko", "query_code_mixed"),
    "E": ("query_en",),
    "F": ("query_ko", "query_en"),
    "G": ("query_ko",),
}

FG_SUMMARY_KO_MIN_MAX_TOKENS = 2048
FG_SUMMARY_KO_SOURCE_CHAR_LIMITS_ON_TRUNCATION: tuple[int, ...] = (3200, 2200, 1400)
SUMMARY_KO_SOURCE_CHAR_LIMITS_ON_TRUNCATION: tuple[int, ...] = (900, 500)
QUERY_PAYLOAD_TEXT_LIMITS_ON_TRUNCATION: tuple[int, ...] = (2200, 1400)
B_DEFAULT_SUMMARY_MAX_CHARS = 900
B_DEFAULT_QUERY_ORIGINAL_CHUNK_MAX_CHARS = 1800
B_DEFAULT_QUERY_TRANSLATED_CHUNK_MAX_CHARS = 1200
B_DEFAULT_QUERY_SUMMARY_MAX_CHARS = 900
DETERMINISTIC_KO_SUMMARY_PROVIDER = "deterministic"
DETERMINISTIC_KO_SUMMARY_MODEL = "extractive-ko-v1"
FG_DETERMINISTIC_SUMMARY_PROVIDER = DETERMINISTIC_KO_SUMMARY_PROVIDER
FG_DETERMINISTIC_SUMMARY_MODEL = DETERMINISTIC_KO_SUMMARY_MODEL
FG_DEFAULT_SUMMARY_MAX_CHARS = 1800
FG_RELATED_CHUNK_MAX_CHARS = 900
OVERLAP_CONTEXT_LABEL = "Overlap context from previous chunk:"

STRATEGY_RAW_TABLES: dict[str, str] = {
    "A": "synthetic_queries_raw_a",
    "B": "synthetic_queries_raw_b",
    "C": "synthetic_queries_raw_c",
    "D": "synthetic_queries_raw_d",
    "E": "synthetic_queries_raw_e",
    "F": "synthetic_queries_raw_f",
    "G": "synthetic_queries_raw_g",
}


@dataclass(slots=True)
class ChunkRow:
    chunk_id: str
    document_id: str
    chunk_text: str
    title: str
    product_name: str
    version_label: str | None
    content_checksum: str | None
    cleaned_checksum: str | None


@dataclass(slots=True)
class PromptBundle:
    summary_en_asset: PromptAsset
    summary_en_text: str
    summary_ko_asset: PromptAsset
    summary_ko_text: str
    translate_asset: PromptAsset
    translate_text: str
    query_assets: dict[str, PromptAsset]
    query_texts: dict[str, str]


@dataclass(frozen=True, slots=True)
class BQueryPayloadLimits:
    original_chunk_en_max_chars: int
    translated_chunk_ko_max_chars: int
    extractive_summary_ko_max_chars: int


@dataclass(slots=True)
class PlannedQueryItem:
    chunk: ChunkRow
    query_index: int
    query_type: str
    answerability_type: str
    target_chunk_ids: list[str]
    generation_strategy: str
    raw_table_name: str
    generation_method_id: str | None
    query_prompt_asset: PromptAsset
    query_prompt_text: str
    stable_query_id: str
    source_fingerprint: str
    glossary_terms_keep_english: list[str]


@dataclass(slots=True)
class BTranslationAssetState:
    asset_id: str
    translated_chunk_ko: str
    cached: bool


@dataclass(frozen=True, slots=True)
class TranslationSegment:
    index: int
    kind: str
    text: str
    start_offset: int
    end_offset: int
    source_hash: str


@dataclass(slots=True)
class BSummaryAssetState:
    asset_id: str
    summary_ko: str
    cached: bool


@dataclass(slots=True)
class PendingBatchQueryRow:
    plan: PlannedQueryItem
    query_payload: dict[str, Any]
    generation_asset_ids: list[str]
    translated_chunk_ko: str
    summary_ko: str
    related_chunks_ko: list[dict[str, Any]]


@dataclass(slots=True)
class BatchJsonExecution:
    job_name: str | None
    display_name: str
    input_mode: str
    submitted_item_count: int
    completed_item_count: int
    failed_item_count: int
    batch_stats: dict[str, int]
    item_mapping: list[dict[str, Any]]
    failures: list[dict[str, Any]]
    responses_by_key: dict[str, dict[str, Any]]
    jsonl_path: str | None = None


def _stable_id(parts: list[str]) -> str:
    return str(uuid.uuid5(uuid.NAMESPACE_URL, "|".join(parts)))


def _weighted_choice(rng: random.Random, distribution: dict[str, float]) -> str:
    picks = list(distribution.items())
    roll = rng.random()
    cumulative = 0.0
    for key, weight in picks:
        cumulative += weight
        if roll <= cumulative:
            return key
    return picks[-1][0]


def _read_prompt(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def _resolve_prompt_bundle(
    connection: psycopg.Connection[Any],
    *,
    config: ExperimentConfig,
    prompt_root: Path,
) -> PromptBundle:
    summary_en_path = prompt_root / "summary_extraction" / "extractive_summary_v1.md"
    summary_ko_path = prompt_root / "summary_extraction" / "summarize_ko_v1.md"
    translate_path = prompt_root / "translation" / "translate_chunk_en_to_ko_v1.md"
    query_paths = {
        "A": prompt_root / "query_generation" / "gen_a_v1.md",
        "B": prompt_root / "query_generation" / "gen_b_v1.md",
        "C": prompt_root / "query_generation" / "gen_c_v1.md",
        "D": prompt_root / "query_generation" / "gen_d_v1.md",
        "E": prompt_root / "query_generation" / "gen_e_v1.md",
        "F": prompt_root / "query_generation" / "gen_f_v1.md",
        "G": prompt_root / "query_generation" / "gen_g_v1.md",
    }
    for required_path in (summary_en_path, summary_ko_path, translate_path, *query_paths.values()):
        if not required_path.exists():
            raise FileNotFoundError(f"Prompt file not found: {required_path}")

    return PromptBundle(
        summary_en_asset=load_and_register_prompt(connection, summary_en_path),
        summary_en_text=_read_prompt(summary_en_path),
        summary_ko_asset=load_and_register_prompt(connection, summary_ko_path),
        summary_ko_text=_read_prompt(summary_ko_path),
        translate_asset=load_and_register_prompt(connection, translate_path),
        translate_text=_read_prompt(translate_path),
        query_assets={key: load_and_register_prompt(connection, path) for key, path in query_paths.items()},
        query_texts={key: _read_prompt(path) for key, path in query_paths.items()},
    )


def _load_chunks(
    connection: psycopg.Connection[Any],
    *,
    limit: int | None,
    source_document_id: str | None = None,
    source_id: str | None = None,
    source_ids: list[str] | None = None,
    random_chunk_sampling: bool = False,
    random_seed: int | None = None,
) -> list[ChunkRow]:
    statement = """
        SELECT c.chunk_id,
               c.document_id,
               c.chunk_text,
               d.title,
               c.product_name,
               c.version_label,
               c.content_checksum,
               d.cleaned_checksum
        FROM corpus_chunks c
        JOIN corpus_documents d ON d.document_id = c.document_id
    """
    where_clauses: list[str] = []
    parameters: list[Any] = []
    if source_document_id:
        where_clauses.append("c.document_id = %s")
        parameters.append(source_document_id)
    if source_id:
        where_clauses.append("d.source_id = %s")
        parameters.append(source_id)
    if source_ids:
        where_clauses.append("d.source_id = ANY(%s)")
        parameters.append(source_ids)
    where_clause = ""
    if where_clauses:
        where_clause = " WHERE " + " AND ".join(where_clauses) + " "
    statement = statement + where_clause + " ORDER BY c.document_id, c.chunk_index_in_document "
    with connection.cursor() as cursor:
        if random_chunk_sampling:
            cursor.execute(statement, parameters)
            rows = cursor.fetchall()
            if rows:
                sampler = random.Random(random_seed)
                sampler.shuffle(rows)
                if limit:
                    rows = rows[:limit]
        else:
            if limit:
                cursor.execute(statement + " LIMIT %s", (*parameters, limit))
            else:
                cursor.execute(statement, parameters)
            rows = cursor.fetchall()
    return [
        ChunkRow(
            chunk_id=str(row["chunk_id"]),
            document_id=str(row["document_id"]),
            chunk_text=str(row["chunk_text"]),
            title=str(row["title"]),
            product_name=str(row["product_name"]),
            version_label=row["version_label"],
            content_checksum=row["content_checksum"],
            cleaned_checksum=row["cleaned_checksum"],
        )
        for row in rows
    ]


def _normalize_source_ids(raw_value: Any) -> list[str]:
    if raw_value is None:
        return []
    raw_items = raw_value
    if isinstance(raw_value, str):
        raw_items = raw_value.split(",")
    if not isinstance(raw_items, (list, tuple, set)):
        raw_items = [raw_items]

    source_ids: list[str] = []
    seen: set[str] = set()
    for item in raw_items:
        source_id = str(item or "").strip()
        if not source_id or source_id in seen:
            continue
        source_ids.append(source_id)
        seen.add(source_id)
    return source_ids


def _load_relations(
    connection: psycopg.Connection[Any],
    *,
    chunk_ids: set[str] | None = None,
) -> dict[str, dict[str, list[str]]]:
    relations: dict[str, dict[str, list[str]]] = defaultdict(
        lambda: {"near": [], "far": []}
    )
    selected_chunk_ids = sorted(chunk_ids or [])
    if chunk_ids is not None and not selected_chunk_ids:
        return relations
    with connection.cursor() as cursor:
        if selected_chunk_ids:
            cursor.execute(
                """
                SELECT source_chunk_id, target_chunk_id, relation_type
                FROM corpus_chunk_relations
                WHERE relation_type IN ('near', 'far')
                  AND source_chunk_id = ANY(%s)
                  AND target_chunk_id = ANY(%s)
                ORDER BY source_chunk_id, relation_type, distance_in_doc
                """,
                (selected_chunk_ids, selected_chunk_ids),
            )
        else:
            cursor.execute(
                """
                SELECT source_chunk_id, target_chunk_id, relation_type
                FROM corpus_chunk_relations
                WHERE relation_type IN ('near', 'far')
                ORDER BY source_chunk_id, relation_type, distance_in_doc
                """
            )
        for row in cursor.fetchall():
            source_chunk_id = str(row["source_chunk_id"])
            target_chunk_id = str(row["target_chunk_id"])
            relation_type = str(row["relation_type"])
            relations[source_chunk_id][relation_type].append(target_chunk_id)
    return relations


def _load_glossary(
    connection: psycopg.Connection[Any],
    *,
    document_ids: set[str] | None = None,
) -> dict[str, list[str]]:
    glossary_by_doc: dict[str, list[str]] = defaultdict(list)
    selected_document_ids = sorted(document_ids or [])
    if document_ids is not None and not selected_document_ids:
        return glossary_by_doc
    with connection.cursor() as cursor:
        if selected_document_ids:
            cursor.execute(
                """
                SELECT first_seen_document_id, canonical_form
                FROM corpus_glossary_terms
                WHERE is_active = TRUE
                  AND first_seen_document_id = ANY(%s)
                ORDER BY evidence_count DESC, canonical_form
                """,
                (selected_document_ids,),
            )
        else:
            cursor.execute(
                """
                SELECT first_seen_document_id, canonical_form
                FROM corpus_glossary_terms
                WHERE is_active = TRUE
                  AND first_seen_document_id IS NOT NULL
                ORDER BY evidence_count DESC, canonical_form
                """
            )
        for row in cursor.fetchall():
            document_id = str(row["first_seen_document_id"])
            term = str(row["canonical_form"])
            if term not in glossary_by_doc[document_id]:
                glossary_by_doc[document_id].append(term)
    return glossary_by_doc


def _load_glossary_term_candidates(
    connection: psycopg.Connection[Any],
    *,
    document_ids: set[str] | None = None,
) -> dict[str, list[dict[str, Any]]]:
    candidates_by_doc: dict[str, list[dict[str, Any]]] = defaultdict(list)
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
            candidates_by_doc[document_id].append(
                {
                    "term_id": str(row["term_id"]),
                    "canonical_form": str(row["canonical_form"]),
                    "normalized_form": str(row["normalized_form"] or ""),
                    "term_type": str(row["term_type"]),
                    "is_active": bool(row["is_active"]),
                }
            )
    return candidates_by_doc


def _canonical_mapping_table_available(connection: Any | None) -> bool:
    if connection is None or not hasattr(connection, "cursor"):
        return False
    try:
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


def _canonical_alias_language(*, query_language: str, language_profile: str) -> str:
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
    candidates_by_form: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for candidate in glossary_term_candidates:
        canonical_form = str(candidate.get("canonical_form") or "").strip()
        term_type = str(candidate.get("term_type") or "").strip()
        if not canonical_form or not term_type:
            continue
        candidates_by_form[canonical_form].append(candidate)

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


def _empty_canonical_anchor_payload(
    *,
    synthetic_query_id: str,
    query_language: str,
    language_profile: str,
    generation_strategy: str,
) -> dict[str, Any]:
    return resolve_canonical_anchors(
        [],
        source_context={
            "kind": "synthetic_query",
            "source_id": synthetic_query_id,
            "source_field": "query_text",
            "query_language": query_language,
            "language_profile": language_profile,
            "generation_strategy": generation_strategy,
        },
    )


def _build_canonical_anchor_payload(
    *,
    connection: Any | None,
    synthetic_query_id: str,
    query_language: str,
    language_profile: str,
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
        "kind": "synthetic_query",
        "source_id": synthetic_query_id,
        "source_field": "query_text",
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
            "Canonical anchor resolution failed for synthetic_query_id=%s; storing empty fail-closed payload.",
            synthetic_query_id,
            exc_info=True,
        )
        return _empty_canonical_anchor_payload(
            synthetic_query_id=synthetic_query_id,
            query_language=query_language,
            language_profile=language_profile,
            generation_strategy=generation_strategy,
        )


def _with_canonical_anchor_metadata(
    metadata: dict[str, Any],
    *,
    connection: Any | None,
    synthetic_query_id: str,
    query_language: str,
    language_profile: str,
    generation_strategy: str,
    glossary_terms: list[str],
    glossary_term_candidates: list[dict[str, Any]] | None,
) -> dict[str, Any]:
    canonical_anchors = _build_canonical_anchor_payload(
        connection=connection,
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


def _select_answerability_target(
    chunk: ChunkRow,
    answerability_type: str,
    relations: dict[str, dict[str, list[str]]],
    rng: random.Random,
) -> tuple[str, list[str]]:
    source_relations = relations.get(chunk.chunk_id, {"near": [], "far": []})
    if answerability_type == "single":
        return "single", [chunk.chunk_id]

    candidates = source_relations.get(answerability_type, [])
    if candidates:
        target = rng.choice(candidates)
        return answerability_type, [chunk.chunk_id, target]

    near_candidates = source_relations.get("near", [])
    if near_candidates:
        target = rng.choice(near_candidates)
        return "near", [chunk.chunk_id, target]
    return "single", [chunk.chunk_id]


def _resolve_generation_method_id(
    connection: psycopg.Connection[Any],
    method_code: str,
) -> str | None:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT generation_method_id
            FROM synthetic_query_generation_method
            WHERE method_code = %s
            """,
            (method_code,),
        )
        row = cursor.fetchone()
    if row is None:
        return None
    value = row["generation_method_id"] if isinstance(row, dict) else row[0]
    return str(value) if value is not None else None


def _batch_exists(
    connection: psycopg.Connection[Any],
    batch_id: str,
) -> bool:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT 1
            FROM synthetic_query_generation_batch
            WHERE batch_id = %s
            """,
            (batch_id,),
        )
        return cursor.fetchone() is not None


def _count_queries_for_generation_batch(
    connection: psycopg.Connection[Any],
    generation_batch_id: str | None,
) -> int:
    if not generation_batch_id:
        return 0
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT COUNT(*)::int
            FROM synthetic_queries_raw_all
            WHERE generation_batch_id = %s
            """,
            (generation_batch_id,),
        )
        row = cursor.fetchone()
    if row is None:
        return 0
    value = row["count"] if isinstance(row, dict) else row[0]
    return int(value or 0)


def _refresh_generation_count(
    connection: psycopg.Connection[Any],
    generation_batch_id: str | None,
    current_count: int,
    *,
    fallback_increment: int = 1,
) -> int:
    if generation_batch_id:
        return max(current_count, _count_queries_for_generation_batch(connection, generation_batch_id))
    return current_count + fallback_increment


def _normalize_query_text(text: str) -> str:
    return " ".join(text.strip().lower().split())


def _query_response_schema_for_strategy(generation_strategy: str) -> dict[str, Any]:
    strategy = generation_strategy.strip().upper()
    required_fields = QUERY_REQUIRED_FIELDS_BY_STRATEGY.get(strategy)
    if required_fields is None:
        raise ValueError(f"unsupported generation strategy for response schema: {generation_strategy}")
    return {
        **QUERY_BASE_RESPONSE_SCHEMA,
        "required": list(required_fields),
    }


def _summary_max_tokens_for_strategy(*, generation_strategy: str, base_max_tokens: int) -> int:
    strategy = generation_strategy.strip().upper()
    if strategy in {"F", "G"}:
        return max(base_max_tokens, FG_SUMMARY_KO_MIN_MAX_TOKENS)
    return base_max_tokens


def _is_max_tokens_truncation_error(error: Exception) -> bool:
    cause = getattr(error, "__cause__", None)
    details = getattr(cause, "details", None)
    category = getattr(details, "category", None)
    if str(category or "").strip().lower() == "max_tokens_truncated":
        return True
    message = str(cause) if cause is not None else str(error)
    lowered = message.lower()
    return "category=max_tokens_truncated" in lowered or "finish_reason=max_tokens" in lowered


def _summary_source_text_candidates(*, generation_strategy: str, source_text_ko: str) -> list[str]:
    source_text = str(source_text_ko or "")
    strategy = generation_strategy.strip().upper()
    candidates = [source_text]
    if strategy in {"F", "G"}:
        for char_limit in FG_SUMMARY_KO_SOURCE_CHAR_LIMITS_ON_TRUNCATION:
            if len(source_text) > char_limit:
                candidates.append(source_text[:char_limit])
    deduped: list[str] = []
    seen: set[str] = set()
    for candidate in candidates:
        if candidate in seen:
            continue
        seen.add(candidate)
        deduped.append(candidate)
    return deduped


def _summary_ko_payload_candidates(*, generation_strategy: str, source_text_ko: str) -> list[dict[str, str]]:
    source_text = str(source_text_ko or "")
    strategy = generation_strategy.strip().upper()
    candidates: list[dict[str, str]] = [
        {"source_text_ko": candidate}
        for candidate in _summary_source_text_candidates(
            generation_strategy=generation_strategy,
            source_text_ko=source_text,
        )
    ]
    retry_limits = (
        FG_SUMMARY_KO_SOURCE_CHAR_LIMITS_ON_TRUNCATION
        if strategy in {"F", "G"}
        else SUMMARY_KO_SOURCE_CHAR_LIMITS_ON_TRUNCATION
    )
    for max_chars in retry_limits:
        candidates.append(
            {
                "source_text_ko": _bounded_query_evidence_text(source_text, max_chars=max_chars),
                "retry_hint": (
                    "Previous Korean summary response hit MAX_TOKENS. "
                    "Return exactly one concise JSON object with summary_ko only as 2 short Korean sentences."
                ),
            }
        )
    deduped: list[dict[str, str]] = []
    seen: set[tuple[str, str]] = set()
    for candidate in candidates:
        key = (candidate.get("source_text_ko", ""), candidate.get("retry_hint", ""))
        if key in seen:
            continue
        seen.add(key)
        deduped.append(candidate)
    return deduped


def _compact_query_payload_after_max_tokens(payload: dict[str, Any], *, max_chars: int) -> dict[str, Any]:
    compacted = dict(payload)
    for key in (
        "original_chunk_en",
        "original_chunk_ko",
        "translated_chunk_ko",
        "extractive_summary_en",
        "extractive_summary_ko",
    ):
        value = compacted.get(key)
        if isinstance(value, str):
            compacted[key] = _bounded_query_evidence_text(value, max_chars=max_chars)
    related_chunks = compacted.get("related_chunks_ko")
    if isinstance(related_chunks, list):
        compacted_related: list[dict[str, Any]] = []
        related_limit = max(400, max_chars // 2)
        for item in related_chunks:
            if not isinstance(item, dict):
                continue
            compacted_item = dict(item)
            text_ko = compacted_item.get("text_ko")
            if isinstance(text_ko, str):
                compacted_item["text_ko"] = _bounded_query_evidence_text(text_ko, max_chars=related_limit)
            compacted_related.append(compacted_item)
        compacted["related_chunks_ko"] = compacted_related
    compacted["retry_hint"] = (
        "Previous query-generation response hit MAX_TOKENS. "
        "Return exactly one concise JSON object. Do not quote or summarize the evidence."
    )
    return compacted


def _query_payload_candidates_after_max_tokens(payload: dict[str, Any]) -> list[dict[str, Any]]:
    candidates = [payload]
    for max_chars in QUERY_PAYLOAD_TEXT_LIMITS_ON_TRUNCATION:
        candidates.append(_compact_query_payload_after_max_tokens(payload, max_chars=max_chars))
    return candidates


def _llm_query_json(
    client: LlmClient,
    *,
    prompt_text: str,
    payload: dict[str, Any],
    response_schema: dict[str, Any],
    request_purpose: str,
    trace_id: str,
) -> dict[str, Any]:
    candidates = _query_payload_candidates_after_max_tokens(payload)
    for index, candidate in enumerate(candidates):
        try:
            return _llm_json(
                client,
                prompt_text=prompt_text,
                payload=candidate,
                response_schema=response_schema,
                request_purpose=request_purpose,
                trace_id=trace_id,
            )
        except RuntimeError as exc:
            is_last = (index + 1) >= len(candidates)
            if not is_last and _is_max_tokens_truncation_error(exc):
                LOGGER.warning(
                    "query_retry_after_max_tokens trace_id=%s attempt=%s/%s compact_chars=%s",
                    trace_id,
                    index + 1,
                    len(candidates),
                    QUERY_PAYLOAD_TEXT_LIMITS_ON_TRUNCATION[index]
                    if index < len(QUERY_PAYLOAD_TEXT_LIMITS_ON_TRUNCATION)
                    else None,
                )
                continue
            raise
    raise RuntimeError(f"query response missing for trace_id={trace_id}")


def _deterministic_summary_template_version(
    *,
    prompt_version: str,
    prompt_version_suffix: str,
    max_chars: int,
) -> str:
    return f"{prompt_version}:{prompt_version_suffix}:extractive:max{max_chars}"


def _requires_en_summary_asset(generation_strategy: str) -> bool:
    strategy = generation_strategy.strip().upper()
    return strategy in {"A", "C", "D", "E"}


def _primary_chunk_text(source_text: str) -> str:
    text = str(source_text or "").replace("\r\n", "\n").strip()
    if not text.startswith(OVERLAP_CONTEXT_LABEL):
        return text
    parts = text.split("\n\n", 1)
    if len(parts) == 2 and parts[1].strip():
        return parts[1].strip()
    lines = text.splitlines()
    for index, line in enumerate(lines[1:], start=1):
        if not line.strip():
            primary_text = "\n".join(lines[index + 1 :]).strip()
            return primary_text or text
    return text


def _compact_ko_evidence_summary(source_text_ko: str, *, max_chars: int = FG_DEFAULT_SUMMARY_MAX_CHARS) -> str:
    max_chars = max(200, int(max_chars))
    text = _primary_chunk_text(source_text_ko)
    if len(text) <= max_chars:
        return text

    paragraphs = [paragraph.strip() for paragraph in text.split("\n\n") if paragraph.strip()]
    if not paragraphs:
        return text[:max_chars].rstrip()

    selected: list[str] = []
    for paragraph in paragraphs:
        lowered = paragraph.lower()
        is_structural = paragraph.startswith("Section Path:")
        is_technical = any(
            marker in lowered
            for marker in (
                ">>>",
                "```",
                "`",
                "def ",
                "class ",
                "import ",
                "python",
                "pip ",
                "exception",
                "traceback",
                "async",
                "decorator",
                "module",
                "attribute",
            )
        )
        if selected and not is_structural and not is_technical and len(paragraph) < 40:
            continue
        next_summary = "\n\n".join([*selected, paragraph])
        if len(next_summary) > max_chars:
            if not selected:
                return paragraph[:max_chars].rstrip()
            break
        selected.append(paragraph)

    summary = "\n\n".join(selected or [paragraphs[0]])
    return summary[:max_chars].rstrip()


def _bounded_query_evidence_text(source_text: str, *, max_chars: int) -> str:
    max_chars = max(1, int(max_chars))
    text = str(source_text or "").replace("\r\n", "\n").strip()
    if len(text) <= max_chars:
        return text

    paragraphs = [paragraph.strip() for paragraph in text.split("\n\n") if paragraph.strip()]
    if not paragraphs:
        return text[:max_chars].rstrip()

    selected: list[str] = []
    for paragraph in paragraphs:
        next_text = "\n\n".join([*selected, paragraph])
        if len(next_text) > max_chars:
            if not selected:
                return paragraph[:max_chars].rstrip()
            break
        selected.append(paragraph)
    return "\n\n".join(selected or [paragraphs[0]])[:max_chars].rstrip()


def _fg_summary_mode(raw_config: dict[str, Any]) -> str:
    mode = str(raw_config.get("fg_summary_mode") or "extractive").strip().lower()
    if mode not in {"extractive", "llm"}:
        raise ValueError("fg_summary_mode must be one of: extractive, llm")
    return mode


def _bounded_int_config(
    raw_config: dict[str, Any],
    *,
    key: str,
    default: int,
    min_value: int,
    max_value: int,
) -> int:
    raw_value = raw_config.get(key, default)
    try:
        value = int(raw_value)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"{key} must be an integer") from exc
    return max(min_value, min(value, max_value))


def _b_summary_max_chars(raw_config: dict[str, Any]) -> int:
    return _bounded_int_config(
        raw_config,
        key="b_summary_max_chars",
        default=B_DEFAULT_SUMMARY_MAX_CHARS,
        min_value=300,
        max_value=1600,
    )


def _b_query_payload_limits(raw_config: dict[str, Any]) -> BQueryPayloadLimits:
    return BQueryPayloadLimits(
        original_chunk_en_max_chars=_bounded_int_config(
            raw_config,
            key="b_query_original_chunk_max_chars",
            default=B_DEFAULT_QUERY_ORIGINAL_CHUNK_MAX_CHARS,
            min_value=600,
            max_value=4000,
        ),
        translated_chunk_ko_max_chars=_bounded_int_config(
            raw_config,
            key="b_query_translated_chunk_max_chars",
            default=B_DEFAULT_QUERY_TRANSLATED_CHUNK_MAX_CHARS,
            min_value=300,
            max_value=2400,
        ),
        extractive_summary_ko_max_chars=_bounded_int_config(
            raw_config,
            key="b_query_summary_max_chars",
            default=B_DEFAULT_QUERY_SUMMARY_MAX_CHARS,
            min_value=300,
            max_value=1600,
        ),
    )


def _b_query_payload_limits_dict(limits: BQueryPayloadLimits | None) -> dict[str, int] | None:
    if limits is None:
        return None
    return {
        "original_chunk_en_max_chars": limits.original_chunk_en_max_chars,
        "translated_chunk_ko_max_chars": limits.translated_chunk_ko_max_chars,
        "extractive_summary_ko_max_chars": limits.extractive_summary_ko_max_chars,
    }


def _truthy_config(value: Any) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return value != 0
    return str(value or "").strip().lower() in {"1", "true", "yes", "y", "on"}


def _llm_execution_mode(raw_config: dict[str, Any]) -> str:
    raw_mode = str(raw_config.get("llm_execution_mode") or "").strip().lower()
    if not raw_mode and _truthy_config(raw_config.get("gemini_batch_enabled")):
        raw_mode = "gemini_batch"
    if not raw_mode:
        return "online"
    normalized = raw_mode.replace("-", "_")
    if normalized not in {"online", "gemini_batch"}:
        raise ValueError("llm_execution_mode must be one of: online, gemini_batch")
    return normalized


def _gemini_batch_input_mode(raw_config: dict[str, Any]) -> str:
    raw_mode = str(raw_config.get("gemini_batch_input_mode") or "inline").strip().lower()
    normalized = raw_mode.replace("-", "_")
    if normalized not in {"inline", "jsonl"}:
        raise ValueError("gemini_batch_input_mode must be one of: inline, jsonl")
    return normalized


def _float_config(
    raw_config: dict[str, Any],
    *,
    key: str,
    default: float,
    min_value: float,
    max_value: float,
) -> float:
    raw_value = raw_config.get(key, default)
    try:
        value = float(raw_value)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"{key} must be a number") from exc
    return max(min_value, min(value, max_value))


def _gemini_batch_poll_interval_seconds(raw_config: dict[str, Any]) -> float:
    return _float_config(
        raw_config,
        key="gemini_batch_poll_interval_seconds",
        default=30.0,
        min_value=1.0,
        max_value=600.0,
    )


def _gemini_batch_timeout_seconds(raw_config: dict[str, Any]) -> float:
    return _float_config(
        raw_config,
        key="gemini_batch_timeout_seconds",
        default=24.0 * 60.0 * 60.0,
        min_value=60.0,
        max_value=48.0 * 60.0 * 60.0,
    )


def _gemini_batch_work_dir(raw_config: dict[str, Any]) -> Path:
    raw_value = str(raw_config.get("gemini_batch_work_dir") or "data/reports/gemini_batch").strip()
    return Path(raw_value)


def _fg_summary_max_chars(raw_config: dict[str, Any]) -> int:
    return _bounded_int_config(
        raw_config,
        key="fg_summary_max_chars",
        default=FG_DEFAULT_SUMMARY_MAX_CHARS,
        min_value=600,
        max_value=4000,
    )


def _generation_strategy_for_query_type(
    base_strategy: str,
    query_type: str,
    enable_code_mixed: bool,
) -> str:
    strategy = base_strategy.strip().upper()
    if enable_code_mixed and query_type == "code_mixed" and strategy in {"A", "C"}:
        return "D"
    return strategy


def _language_profile(strategy: str, query_type: str) -> str:
    if strategy in {"E", "F"}:
        return "en"
    if strategy == "D" or query_type == "code_mixed":
        return "code_mixed"
    return "ko"


def _source_fingerprint(chunk: ChunkRow) -> str:
    if chunk.cleaned_checksum:
        return str(chunk.cleaned_checksum)
    if chunk.content_checksum:
        return str(chunk.content_checksum)
    return _stable_id([chunk.chunk_id, chunk.chunk_text[:256]])


def _sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def _translation_full_prompt_version(prompt_version: str) -> str:
    return f"{prompt_version}:translation:{TRANSLATION_SEGMENTATION_VERSION}:full"


def _translation_segment_prompt_version(prompt_version: str) -> str:
    return f"{prompt_version}:translation:{TRANSLATION_SEGMENTATION_VERSION}:segment"


def _translation_segment_source_fingerprint(
    *,
    source_fingerprint: str,
    segment: TranslationSegment,
) -> str:
    return _stable_id(
        [
            source_fingerprint,
            TRANSLATION_SEGMENTATION_VERSION,
            str(segment.index),
            segment.source_hash,
        ]
    )


def _is_code_fence_line(line: str) -> bool:
    stripped = line.lstrip()
    return stripped.startswith("```") or stripped.startswith("~~~")


def _translation_block_kind(line: str) -> str:
    stripped = line.strip()
    if not stripped:
        return "blank"
    if re.match(r"^\s{0,3}#{1,6}\s+", line):
        return "heading"
    if stripped.startswith("|"):
        return "table"
    if re.match(r"^\s*(?:[-*+]\s+|\d+[.)]\s+)", line):
        return "list"
    return "paragraph"


def _split_long_text_on_sentence_boundaries(text: str, max_chars: int) -> list[str]:
    if len(text) <= max_chars:
        return [text]
    parts = re.split(r"(?<=[.!?。！？])(\s+)", text)
    candidates: list[str] = []
    if len(parts) > 1:
        for index in range(0, len(parts), 2):
            sentence = parts[index]
            if index + 1 < len(parts):
                sentence += parts[index + 1]
            if sentence:
                candidates.append(sentence)
    if not candidates:
        return [text]
    chunks: list[str] = []
    current = ""
    for sentence in candidates:
        if current and len(current) + len(sentence) > max_chars:
            chunks.append(current)
            current = sentence
        else:
            current += sentence
    if current:
        chunks.append(current)
    return chunks or [text]


def _split_long_block(block_text: str, kind: str, max_chars: int) -> list[str]:
    if len(block_text) <= max_chars or kind in {"code", "blank"}:
        return [block_text]
    if kind in {"list", "table"}:
        chunks: list[str] = []
        current = ""
        for line in block_text.splitlines(keepends=True):
            if current and len(current) + len(line) > max_chars:
                chunks.append(current)
                current = line
            else:
                current += line
        if current:
            chunks.append(current)
        return chunks or [block_text]
    return _split_long_text_on_sentence_boundaries(block_text, max_chars)


def _translation_blocks(text: str) -> list[tuple[str, str]]:
    blocks: list[tuple[str, str]] = []
    current_lines: list[str] = []
    current_kind: str | None = None
    in_code = False

    def flush() -> None:
        nonlocal current_lines, current_kind
        if current_lines:
            blocks.append((current_kind or "paragraph", "".join(current_lines)))
            current_lines = []
            current_kind = None

    for line in text.splitlines(keepends=True):
        if in_code:
            current_lines.append(line)
            if _is_code_fence_line(line):
                flush()
                in_code = False
            continue

        if _is_code_fence_line(line):
            flush()
            current_kind = "code"
            current_lines = [line]
            in_code = True
            continue

        kind = _translation_block_kind(line)
        if kind == "blank":
            flush()
            blocks.append(("blank", line))
            continue

        should_start_new = (
            current_kind is None
            or kind in {"heading", "table", "list"}
            or current_kind in {"heading", "table", "list"}
            or kind != current_kind
        )
        if should_start_new:
            flush()
            current_kind = kind
        current_lines.append(line)

    flush()
    return blocks


def _build_translation_segments(text: str, *, max_chars: int = TRANSLATION_SEGMENT_TARGET_MAX_CHARS) -> list[TranslationSegment]:
    raw_segments: list[tuple[str, str]] = []

    for kind, block_text in _translation_blocks(text):
        for part in _split_long_block(block_text, kind, max_chars):
            if kind in {"code", "blank"}:
                raw_segments.append((kind, part))
                continue
            raw_segments.append(("text", part))

    segments: list[TranslationSegment] = []
    cursor = 0
    for index, (kind, segment_text) in enumerate(raw_segments):
        start_offset = text.find(segment_text, cursor)
        if start_offset < 0:
            start_offset = cursor
        end_offset = start_offset + len(segment_text)
        cursor = end_offset
        segments.append(
            TranslationSegment(
                index=index,
                kind=kind,
                text=segment_text,
                start_offset=start_offset,
                end_offset=end_offset,
                source_hash=_sha256_text(segment_text),
            )
        )
    return segments


def _find_existing_asset(
    connection: psycopg.Connection[Any],
    *,
    chunk_id: str,
    asset_type: str,
    llm_provider: str,
    llm_model: str,
    prompt_template_version: str,
    source_fingerprint: str,
) -> tuple[str, str] | None:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT asset_id, text_content
            FROM chunk_generation_asset
            WHERE chunk_id = %s
              AND asset_type = %s
              AND llm_provider = %s
              AND llm_model = %s
              AND prompt_template_version = %s
              AND source_fingerprint = %s
            ORDER BY created_at DESC
            LIMIT 1
            """,
            (
                chunk_id,
                asset_type,
                llm_provider,
                llm_model,
                prompt_template_version,
                source_fingerprint,
            ),
        )
        row = cursor.fetchone()
    if row is None:
        return None
    return str(row["asset_id"]), str(row["text_content"])


def _create_asset(
    connection: psycopg.Connection[Any],
    *,
    chunk: ChunkRow,
    asset_type: str,
    text_content: str,
    llm_provider: str,
    llm_model: str,
    prompt_template_version: str,
    source_fingerprint: str,
    metadata: dict[str, Any],
) -> str:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            INSERT INTO chunk_generation_asset (
                chunk_id,
                source_document_id,
                asset_type,
                text_content,
                llm_provider,
                llm_model,
                prompt_template_version,
                source_fingerprint,
                metadata_json
            ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
            RETURNING asset_id
            """,
            (
                chunk.chunk_id,
                chunk.document_id,
                asset_type,
                text_content,
                llm_provider,
                llm_model,
                prompt_template_version,
                source_fingerprint,
                Jsonb(metadata),
            ),
        )
        row = cursor.fetchone()
    return str(row["asset_id"])


def _commit_if_supported(connection: psycopg.Connection[Any]) -> None:
    commit = getattr(connection, "commit", None)
    if callable(commit):
        commit()


def _llm_json(
    client: LlmClient,
    *,
    prompt_text: str,
    payload: dict[str, Any],
    response_schema: dict[str, Any],
    request_purpose: str,
    trace_id: str,
) -> dict[str, Any]:
    return client.chat_json(
        system_prompt=prompt_text,
        user_prompt=json.dumps(payload, ensure_ascii=False, indent=2),
        response_schema=response_schema,
        request_purpose=request_purpose,
        trace_id=trace_id,
    )


def _resolve_or_create_summary_en(
    connection: psycopg.Connection[Any],
    *,
    chunk: ChunkRow,
    source_fingerprint: str,
    prompt_asset: PromptAsset,
    prompt_text: str,
    client: LlmClient,
) -> tuple[str, str, bool]:
    cached = _find_existing_asset(
        connection,
        chunk_id=chunk.chunk_id,
        asset_type="EN_EXTRACTIVE_SUMMARY",
        llm_provider=client.config.provider,
        llm_model=client.config.model,
        prompt_template_version=prompt_asset.version,
        source_fingerprint=source_fingerprint,
    )
    if cached:
        return cached[0], cached[1], True
    response = _llm_json(
        client,
        prompt_text=prompt_text,
        payload={
            "chunk_id": chunk.chunk_id,
            "title": chunk.title,
            "product": chunk.product_name,
            "chunk_text_en": chunk.chunk_text,
        },
        response_schema=SUMMARY_EN_RESPONSE_SCHEMA,
        request_purpose="summary_extraction_en",
        trace_id=f"chunk:{chunk.chunk_id}",
    )
    summary_en = str(response.get("extractive_summary_en") or response.get("summary_en") or "").strip()
    if not summary_en:
        raise RuntimeError(f"empty extractive_summary_en for chunk={chunk.chunk_id}")
    asset_id = _create_asset(
        connection,
        chunk=chunk,
        asset_type="EN_EXTRACTIVE_SUMMARY",
        text_content=summary_en,
        llm_provider=client.config.provider,
        llm_model=client.config.model,
        prompt_template_version=prompt_asset.version,
        source_fingerprint=source_fingerprint,
        metadata={"key_terms": response.get("key_terms") or []},
    )
    return asset_id, summary_en, False


def _resolve_or_create_translated_chunk(
    connection: psycopg.Connection[Any],
    *,
    chunk: ChunkRow,
    source_fingerprint: str,
    prompt_asset: PromptAsset,
    prompt_text: str,
    client: LlmClient,
) -> tuple[str, str, bool]:
    full_prompt_version = _translation_full_prompt_version(prompt_asset.version)
    cached = _find_existing_asset(
        connection,
        chunk_id=chunk.chunk_id,
        asset_type="KO_TRANSLATED_CHUNK",
        llm_provider=client.config.provider,
        llm_model=client.config.model,
        prompt_template_version=full_prompt_version,
        source_fingerprint=source_fingerprint,
    )
    if cached:
        return cached[0], cached[1], True

    segment_prompt_version = _translation_segment_prompt_version(prompt_asset.version)
    segments = _build_translation_segments(chunk.chunk_text)
    translated_segments: list[str] = []
    segment_metadata: list[dict[str, Any]] = []
    segment_cache_hits = 0
    segment_created = 0
    for segment in segments:
        if segment.kind in {"code", "blank"}:
            translated_segments.append(segment.text)
            segment_metadata.append(
                {
                    "index": segment.index,
                    "kind": segment.kind,
                    "start_offset": segment.start_offset,
                    "end_offset": segment.end_offset,
                    "source_hash": segment.source_hash,
                    "translation_source": "verbatim",
                }
            )
            continue

        segment_source_fingerprint = _translation_segment_source_fingerprint(
            source_fingerprint=source_fingerprint,
            segment=segment,
        )
        cached_segment = _find_existing_asset(
            connection,
            chunk_id=chunk.chunk_id,
            asset_type="KO_TRANSLATED_CHUNK",
            llm_provider=client.config.provider,
            llm_model=client.config.model,
            prompt_template_version=segment_prompt_version,
            source_fingerprint=segment_source_fingerprint,
        )
        if cached_segment:
            translated = cached_segment[1]
            segment_asset_id = cached_segment[0]
            segment_cache_hits += 1
            cached_flag = True
        else:
            response = _llm_json(
                client,
                prompt_text=prompt_text,
                payload={
                    "chunk_id": chunk.chunk_id,
                    "title": chunk.title,
                    "translation_mode": "segmented_full",
                    "segmentation_version": TRANSLATION_SEGMENTATION_VERSION,
                    "segment_index": segment.index,
                    "segment_count": len(segments),
                    "segment_kind": segment.kind,
                    "chunk_text_en": segment.text,
                },
                response_schema=TRANSLATION_RESPONSE_SCHEMA,
                request_purpose="translate_chunk_en_to_ko_segment",
                trace_id=f"chunk:{chunk.chunk_id}:segment:{segment.index}",
            )
            translated = str(response.get("translated_chunk_ko") or "").strip()
            if not translated:
                raise RuntimeError(f"empty translated_chunk_ko for chunk={chunk.chunk_id} segment={segment.index}")
            llm_meta = response.get("_llm_meta") if isinstance(response.get("_llm_meta"), dict) else {}
            segment_asset_id = _create_asset(
                connection,
                chunk=chunk,
                asset_type="KO_TRANSLATED_CHUNK",
                text_content=translated,
                llm_provider=client.config.provider,
                llm_model=client.config.model,
                prompt_template_version=segment_prompt_version,
                source_fingerprint=segment_source_fingerprint,
                metadata={
                    "translation_mode": "segmented_full",
                    "segment_role": "segment",
                    "segmentation_version": TRANSLATION_SEGMENTATION_VERSION,
                    "segment_index": segment.index,
                    "segment_count": len(segments),
                    "segment_kind": segment.kind,
                    "source_hash": segment.source_hash,
                    "start_offset": segment.start_offset,
                    "end_offset": segment.end_offset,
                    "source_chunk_fingerprint": source_fingerprint,
                    "usage": llm_meta.get("usage"),
                    "usage_metadata": llm_meta.get("usage_metadata"),
                },
            )
            _commit_if_supported(connection)
            segment_created += 1
            cached_flag = False

        translated_segments.append(translated)
        segment_metadata.append(
            {
                "index": segment.index,
                "kind": segment.kind,
                "start_offset": segment.start_offset,
                "end_offset": segment.end_offset,
                "source_hash": segment.source_hash,
                "segment_asset_id": segment_asset_id,
                "cached": cached_flag,
            }
        )

    translated = "".join(translated_segments).strip()
    if not translated:
        raise RuntimeError(f"empty segmented translated_chunk_ko for chunk={chunk.chunk_id}")
    asset_id = _create_asset(
        connection,
        chunk=chunk,
        asset_type="KO_TRANSLATED_CHUNK",
        text_content=translated,
        llm_provider=client.config.provider,
        llm_model=client.config.model,
        prompt_template_version=full_prompt_version,
        source_fingerprint=source_fingerprint,
        metadata={
            "source": "en_chunk",
            "translation_mode": "segmented_full",
            "segmentation_version": TRANSLATION_SEGMENTATION_VERSION,
            "segment_count": len(segments),
            "segment_cache_hits": segment_cache_hits,
            "segment_created": segment_created,
            "source_checksum": _sha256_text(chunk.chunk_text),
            "source_fingerprint": source_fingerprint,
            "segments": segment_metadata,
            "reconstruction_checksum": _sha256_text(translated),
        },
    )
    return asset_id, translated, False


def _resolve_or_create_summary_ko(
    connection: psycopg.Connection[Any],
    *,
    chunk: ChunkRow,
    source_fingerprint: str,
    prompt_asset: PromptAsset,
    prompt_text: str,
    prompt_version_suffix: str,
    source_text_ko: str,
    client: LlmClient,
) -> tuple[str, str, bool]:
    template_version = f"{prompt_asset.version}:{prompt_version_suffix}"
    cached = _find_existing_asset(
        connection,
        chunk_id=chunk.chunk_id,
        asset_type="KO_SUMMARY",
        llm_provider=client.config.provider,
        llm_model=client.config.model,
        prompt_template_version=template_version,
        source_fingerprint=source_fingerprint,
    )
    if cached:
        return cached[0], cached[1], True
    response: dict[str, Any] | None = None
    candidate_payloads = _summary_ko_payload_candidates(
        generation_strategy=prompt_version_suffix,
        source_text_ko=source_text_ko,
    )
    for index, payload_candidate in enumerate(candidate_payloads):
        try:
            payload = {
                "chunk_id": chunk.chunk_id,
                **payload_candidate,
            }
            response = _llm_json(
                client,
                prompt_text=prompt_text,
                payload=payload,
                response_schema=SUMMARY_KO_RESPONSE_SCHEMA,
                request_purpose="summary_extraction_ko",
                trace_id=f"chunk:{chunk.chunk_id}",
            )
            break
        except RuntimeError as exc:
            is_last = (index + 1) >= len(candidate_payloads)
            if not is_last and _is_max_tokens_truncation_error(exc):
                LOGGER.warning(
                    "summary_ko_retry_after_max_tokens chunk=%s strategy=%s attempt=%s/%s source_chars=%s",
                    chunk.chunk_id,
                    prompt_version_suffix,
                    index + 1,
                    len(candidate_payloads),
                    len(str(payload_candidate.get("source_text_ko") or "")),
                )
                continue
            raise
    if response is None:
        raise RuntimeError(f"summary_ko response missing for chunk={chunk.chunk_id}")
    summary_ko = str(response.get("summary_ko") or "").strip()
    if not summary_ko:
        raise RuntimeError(f"empty summary_ko for chunk={chunk.chunk_id}")
    asset_id = _create_asset(
        connection,
        chunk=chunk,
        asset_type="KO_SUMMARY",
        text_content=summary_ko,
        llm_provider=client.config.provider,
        llm_model=client.config.model,
        prompt_template_version=template_version,
        source_fingerprint=source_fingerprint,
        metadata={"source": prompt_version_suffix},
    )
    return asset_id, summary_ko, False


def _resolve_or_create_extractive_summary_ko(
    connection: psycopg.Connection[Any],
    *,
    chunk: ChunkRow,
    source_fingerprint: str,
    prompt_asset: PromptAsset,
    prompt_version_suffix: str,
    source_text_ko: str,
    max_chars: int,
    metadata: dict[str, Any] | None = None,
) -> tuple[str, str, bool]:
    template_version = _deterministic_summary_template_version(
        prompt_version=prompt_asset.version,
        prompt_version_suffix=prompt_version_suffix,
        max_chars=max_chars,
    )
    cached = _find_existing_asset(
        connection,
        chunk_id=chunk.chunk_id,
        asset_type="KO_SUMMARY",
        llm_provider=DETERMINISTIC_KO_SUMMARY_PROVIDER,
        llm_model=DETERMINISTIC_KO_SUMMARY_MODEL,
        prompt_template_version=template_version,
        source_fingerprint=source_fingerprint,
    )
    if cached:
        return cached[0], cached[1], True

    summary_ko = _compact_ko_evidence_summary(source_text_ko, max_chars=max_chars)
    if not summary_ko:
        raise RuntimeError(f"empty extractive summary_ko for chunk={chunk.chunk_id}")
    asset_metadata = {
        "source": prompt_version_suffix,
        "summary_mode": "extractive",
        "max_chars": max_chars,
    }
    if metadata:
        asset_metadata.update(metadata)
    asset_id = _create_asset(
        connection,
        chunk=chunk,
        asset_type="KO_SUMMARY",
        text_content=summary_ko,
        llm_provider=DETERMINISTIC_KO_SUMMARY_PROVIDER,
        llm_model=DETERMINISTIC_KO_SUMMARY_MODEL,
        prompt_template_version=template_version,
        source_fingerprint=source_fingerprint,
        metadata=asset_metadata,
    )
    return asset_id, summary_ko, False


def _extract_query_text(
    *,
    generation_strategy: str,
    query_type: str,
    response: dict[str, Any],
) -> tuple[str, dict[str, Any]]:
    def _read_query_field(*candidate_keys: str) -> str:
        normalized_keys: list[str] = []
        seen: set[str] = set()
        for key in candidate_keys:
            key_name = str(key).strip()
            if key_name in QUERY_TEXT_FIELDS and key_name not in seen:
                seen.add(key_name)
                normalized_keys.append(key_name)
        if not normalized_keys:
            normalized_keys = list(QUERY_TEXT_FIELDS)

        for key in normalized_keys:
            value = response.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()

        queries_value = response.get("queries")
        if isinstance(queries_value, list):
            for item in queries_value:
                if not isinstance(item, dict):
                    continue
                for key in normalized_keys:
                    value = item.get(key)
                    if isinstance(value, str) and value.strip():
                        return value.strip()
        return ""

    strategy = generation_strategy.strip().upper()
    if strategy == "A":
        query_text = _read_query_field("query_ko", "query_en", "query")
        query_en = _read_query_field("query_en", "query")
        return query_text, {"query_en": query_en}
    if strategy == "E":
        query_en = _read_query_field("query_en", "query", "query_ko")
        return query_en, {"query_en": query_en}
    if strategy == "F":
        query_en = _read_query_field("query_en", "query", "query_ko")
        query_ko = _read_query_field("query_ko")
        return query_en, {"query_en": query_en, "query_ko": query_ko}
    if strategy == "D":
        query_ko = _read_query_field("query_ko", "query")
        query_code_mixed = _read_query_field("query_code_mixed")
        if query_type == "code_mixed" and query_code_mixed:
            return query_code_mixed, {"query_ko": query_ko, "query_code_mixed": query_code_mixed}
        return (query_ko or query_code_mixed), {"query_ko": query_ko, "query_code_mixed": query_code_mixed}
    if strategy in {"B", "C", "G"}:
        return _read_query_field("query_ko", "query", "query_en"), {}
    return _read_query_field("query_ko", "query_en", "query"), {}


def _raw_table_for_strategy(generation_strategy: str) -> str:
    normalized = generation_strategy.strip().upper()
    table_name = STRATEGY_RAW_TABLES.get(normalized)
    if table_name is None:
        raise ValueError(f"unsupported generation strategy: {generation_strategy}")
    return table_name


def _build_query_payload(
    *,
    chunk: ChunkRow,
    generation_strategy: str,
    original_chunk_ko: str,
    related_chunks_ko: list[dict[str, Any]],
    extractive_summary_en: str,
    translated_chunk_ko: str,
    extractive_summary_ko: str,
    glossary_terms_keep_english: list[str],
    query_type: str,
    answerability_type: str,
    target_chunk_ids: list[str],
    b_payload_limits: BQueryPayloadLimits | None = None,
) -> dict[str, Any]:
    strategy = generation_strategy.strip().upper()
    original_chunk_en = "" if strategy in {"F", "G"} else chunk.chunk_text
    payload_original_chunk_ko = "" if strategy == "B" else original_chunk_ko
    payload_translated_chunk_ko = translated_chunk_ko
    payload_summary_ko = extractive_summary_ko
    if strategy == "B" and b_payload_limits is not None:
        original_chunk_en = _bounded_query_evidence_text(
            original_chunk_en,
            max_chars=b_payload_limits.original_chunk_en_max_chars,
        )
        payload_translated_chunk_ko = _compact_ko_evidence_summary(
            translated_chunk_ko,
            max_chars=b_payload_limits.translated_chunk_ko_max_chars,
        )
        payload_summary_ko = _compact_ko_evidence_summary(
            extractive_summary_ko,
            max_chars=b_payload_limits.extractive_summary_ko_max_chars,
        )
    return {
        "chunk_id": chunk.chunk_id,
        "document_id": chunk.document_id,
        "title": chunk.title,
        "product": chunk.product_name,
        "version": chunk.version_label,
        "original_chunk_en": original_chunk_en,
        "original_chunk_ko": payload_original_chunk_ko,
        "related_chunks_ko": related_chunks_ko,
        "extractive_summary_en": extractive_summary_en,
        "translated_chunk_ko": payload_translated_chunk_ko,
        "extractive_summary_ko": payload_summary_ko,
        "glossary_terms_keep_english": glossary_terms_keep_english,
        "query_type": query_type,
        "answerability_type": answerability_type,
        "target_chunk_ids": target_chunk_ids,
    }


def _find_cached_query(
    connection: psycopg.Connection[Any],
    *,
    table_name: str,
    synthetic_query_id: str,
    source_fingerprint: str,
    prompt_template_version: str,
) -> bool:
    statement = sql.SQL(
        """
        SELECT 1
        FROM {table_name}
        WHERE synthetic_query_id = %s
          AND source_fingerprint = %s
          AND prompt_template_version = %s
        """
    ).format(table_name=sql.Identifier(table_name))
    with connection.cursor() as cursor:
        cursor.execute(
            statement,
            (
                synthetic_query_id,
                source_fingerprint,
                prompt_template_version,
            ),
        )
        return cursor.fetchone() is not None


def _attach_cached_query(
    connection: psycopg.Connection[Any],
    *,
    table_name: str,
    synthetic_query_id: str,
    generation_method_id: str | None,
    generation_batch_id: str | None,
    llm_provider: str,
    llm_model: str,
    generation_asset_ids: list[str],
) -> None:
    statement = sql.SQL(
        """
        UPDATE {table_name}
        SET generation_method_id = %s,
            generation_batch_id = %s,
            llm_provider = %s,
            llm_model = %s,
            generation_asset_ids = %s
        WHERE synthetic_query_id = %s
        """
    ).format(table_name=sql.Identifier(table_name))
    with connection.cursor() as cursor:
        cursor.execute(
            statement,
            (
                generation_method_id,
                generation_batch_id,
                llm_provider,
                llm_model,
                Jsonb(generation_asset_ids),
                synthetic_query_id,
            ),
        )


def _insert_query_row(
    connection: psycopg.Connection[Any],
    *,
    table_name: str,
    payload: dict[str, Any],
) -> None:
    statement = sql.SQL(
        """
        INSERT INTO {table_name} (
            synthetic_query_id,
            experiment_run_id,
            generation_method_id,
            generation_batch_id,
            chunk_id_source,
            source_chunk_group_id,
            target_doc_id,
            target_chunk_ids,
            answerability_type,
            query_text,
            normalized_query_text,
            query_language,
            language_profile,
            query_type,
            generation_strategy,
            prompt_asset_id,
            prompt_template_version,
            prompt_version,
            prompt_hash,
            source_summary,
            source_fingerprint,
            source_chunk_ids,
            glossary_terms,
            llm_provider,
            llm_model,
            generation_asset_ids,
            llm_output,
            metadata
        ) VALUES (
            %(synthetic_query_id)s,
            %(experiment_run_id)s,
            %(generation_method_id)s,
            %(generation_batch_id)s,
            %(chunk_id_source)s,
            %(source_chunk_group_id)s,
            %(target_doc_id)s,
            %(target_chunk_ids)s,
            %(answerability_type)s,
            %(query_text)s,
            %(normalized_query_text)s,
            %(query_language)s,
            %(language_profile)s,
            %(query_type)s,
            %(generation_strategy)s,
            %(prompt_asset_id)s,
            %(prompt_template_version)s,
            %(prompt_version)s,
            %(prompt_hash)s,
            %(source_summary)s,
            %(source_fingerprint)s,
            %(source_chunk_ids)s,
            %(glossary_terms)s,
            %(llm_provider)s,
            %(llm_model)s,
            %(generation_asset_ids)s,
            %(llm_output)s,
            %(metadata)s
        )
        ON CONFLICT (synthetic_query_id) DO UPDATE
        SET query_text = EXCLUDED.query_text,
            target_chunk_ids = EXCLUDED.target_chunk_ids,
            source_summary = EXCLUDED.source_summary,
            source_fingerprint = EXCLUDED.source_fingerprint,
            glossary_terms = EXCLUDED.glossary_terms,
            llm_provider = EXCLUDED.llm_provider,
            llm_model = EXCLUDED.llm_model,
            generation_asset_ids = EXCLUDED.generation_asset_ids,
            llm_output = EXCLUDED.llm_output,
            metadata = EXCLUDED.metadata,
            generation_method_id = EXCLUDED.generation_method_id,
            generation_batch_id = EXCLUDED.generation_batch_id,
            normalized_query_text = EXCLUDED.normalized_query_text,
            language_profile = EXCLUDED.language_profile,
            prompt_template_version = EXCLUDED.prompt_template_version
        """
    ).format(table_name=sql.Identifier(table_name))

    with connection.cursor() as cursor:
        cursor.execute(statement, payload)


def _insert_source_link(
    connection: psycopg.Connection[Any],
    *,
    synthetic_query_id: str,
    source_doc_id: str,
    source_chunk_id: str,
    source_chunk_group_id: str | None,
    source_role: str,
) -> None:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            INSERT INTO synthetic_query_source_link (
                synthetic_query_id,
                source_doc_id,
                source_chunk_id,
                source_chunk_group_id,
                source_role,
                metadata_json
            ) VALUES (
                %s, %s, %s, %s, %s, %s
            )
            ON CONFLICT (synthetic_query_id, source_chunk_id, source_role) DO UPDATE
            SET source_doc_id = EXCLUDED.source_doc_id,
                source_chunk_group_id = EXCLUDED.source_chunk_group_id,
                metadata_json = EXCLUDED.metadata_json
            """,
            (
                synthetic_query_id,
                source_doc_id,
                source_chunk_id,
                source_chunk_group_id,
                source_role,
                Jsonb({"linked_by": "generate-queries"}),
            ),
        )


def _insert_source_links_for_targets(
    connection: psycopg.Connection[Any],
    *,
    synthetic_query_id: str,
    primary_chunk: ChunkRow,
    target_chunk_ids: list[str],
    chunks_by_id: dict[str, ChunkRow],
) -> None:
    _insert_source_link(
        connection,
        synthetic_query_id=synthetic_query_id,
        source_doc_id=primary_chunk.document_id,
        source_chunk_id=primary_chunk.chunk_id,
        source_chunk_group_id=None,
        source_role="primary",
    )
    for target_chunk_id in target_chunk_ids:
        if target_chunk_id == primary_chunk.chunk_id:
            continue
        target_chunk = chunks_by_id.get(target_chunk_id)
        if target_chunk is None:
            continue
        _insert_source_link(
            connection,
            synthetic_query_id=synthetic_query_id,
            source_doc_id=target_chunk.document_id,
            source_chunk_id=target_chunk.chunk_id,
            source_chunk_group_id=None,
            source_role="related",
        )


def _build_query_row_payload(
    *,
    canonical_mapping_connection: Any | None = None,
    glossary_term_candidates: list[dict[str, Any]] | None = None,
    synthetic_query_id: str,
    run_context_id: str,
    generation_method_id: str | None,
    generation_batch_id: str | None,
    chunk: ChunkRow,
    generation_strategy: str,
    query_prompt_asset: PromptAsset,
    source_fingerprint: str,
    target_chunk_ids: list[str],
    answerability_type: str,
    query_text: str,
    query_type: str,
    generation_asset_ids: list[str],
    query_response: dict[str, Any],
    extra_trace: dict[str, Any],
    chunk_glossary_terms: list[str],
    en_summary: str,
    summary_ko: str,
    translated_chunk_ko: str,
    query_payload: dict[str, Any],
    b_payload_limits: BQueryPayloadLimits | None,
    fg_summary_mode: str,
    related_chunks_ko: list[dict[str, Any]],
    llm_provider: str,
    llm_model: str,
    execution_mode: str,
) -> dict[str, Any]:
    normalized_query_text = _normalize_query_text(query_text)
    language_profile = _language_profile(generation_strategy, query_type)
    query_language = "en" if generation_strategy in {"E", "F"} else "ko"
    trace = {
        "en_summary": en_summary,
        "ko_summary": summary_ko,
        "llm_execution_mode": execution_mode,
        "b_summary_mode": "extractive" if generation_strategy == "B" else None,
        "b_query_payload_limits": (
            _b_query_payload_limits_dict(b_payload_limits)
            if generation_strategy == "B"
            else None
        ),
        "b_query_payload_chars": (
            {
                "original_chunk_en": len(query_payload.get("original_chunk_en") or ""),
                "original_chunk_ko": len(query_payload.get("original_chunk_ko") or ""),
                "extractive_summary_en": len(query_payload.get("extractive_summary_en") or ""),
                "translated_chunk_ko": len(query_payload.get("translated_chunk_ko") or ""),
                "extractive_summary_ko": len(query_payload.get("extractive_summary_ko") or ""),
                "translated_chunk_ko_asset": len(translated_chunk_ko),
                "extractive_summary_ko_asset": len(summary_ko),
            }
            if generation_strategy == "B"
            else None
        ),
        "fg_summary_mode": fg_summary_mode if generation_strategy in {"F", "G"} else None,
        "related_chunks_ko_count": len(related_chunks_ko),
        "translated_chunk_excerpt": translated_chunk_ko[:320],
        **extra_trace,
    }
    llm_meta = query_response.get("_llm_meta") if isinstance(query_response.get("_llm_meta"), dict) else {}
    if isinstance(llm_meta.get("gemini_batch"), dict):
        trace["gemini_batch"] = llm_meta.get("gemini_batch")
    metadata = _with_canonical_anchor_metadata(
        {
            "query_type_label": QUERY_TYPE_LABELS_KO.get(query_type, query_type),
            "title": chunk.title,
            "product_name": chunk.product_name,
            "version_label": chunk.version_label,
            "generation_batch_id": generation_batch_id,
            "source_fingerprint": source_fingerprint,
            "llm_execution_mode": execution_mode,
        },
        connection=canonical_mapping_connection,
        synthetic_query_id=synthetic_query_id,
        query_language=query_language,
        language_profile=language_profile,
        generation_strategy=generation_strategy,
        glossary_terms=chunk_glossary_terms,
        glossary_term_candidates=glossary_term_candidates,
    )
    return {
        "synthetic_query_id": synthetic_query_id,
        "experiment_run_id": run_context_id,
        "generation_method_id": generation_method_id,
        "generation_batch_id": generation_batch_id,
        "chunk_id_source": chunk.chunk_id,
        "source_chunk_group_id": None,
        "target_doc_id": chunk.document_id,
        "target_chunk_ids": Jsonb(target_chunk_ids),
        "answerability_type": answerability_type,
        "query_text": query_text,
        "normalized_query_text": normalized_query_text,
        "query_language": query_language,
        "language_profile": language_profile,
        "query_type": query_type,
        "generation_strategy": generation_strategy,
        "prompt_asset_id": query_prompt_asset.prompt_asset_id,
        "prompt_template_version": query_prompt_asset.version,
        "prompt_version": query_prompt_asset.version,
        "prompt_hash": query_prompt_asset.content_hash,
        "source_summary": summary_ko if summary_ko else en_summary,
        "source_fingerprint": source_fingerprint,
        "source_chunk_ids": Jsonb(target_chunk_ids),
        "glossary_terms": Jsonb(chunk_glossary_terms),
        "llm_provider": llm_provider,
        "llm_model": llm_model,
        "generation_asset_ids": Jsonb(generation_asset_ids),
        "llm_output": Jsonb(
            {
                "schema_version": "v1",
                "response": query_response,
                "query_type": query_type,
                "answerability_type": answerability_type,
                "trace": trace,
            }
        ),
        "metadata": Jsonb(metadata),
    }


def _related_chunks_ko_payload(
    *,
    primary_chunk_id: str,
    target_chunk_ids: list[str],
    chunks_by_id: dict[str, ChunkRow],
) -> list[dict[str, str]]:
    related_chunks: list[dict[str, str]] = []
    for target_chunk_id in target_chunk_ids:
        if target_chunk_id == primary_chunk_id:
            continue
        target_chunk = chunks_by_id.get(target_chunk_id)
        if target_chunk is None:
            continue
        related_chunks.append(
            {
                "chunk_id": target_chunk.chunk_id,
                "document_id": target_chunk.document_id,
                "title": target_chunk.title,
                "text_ko": _compact_ko_evidence_summary(
                    target_chunk.chunk_text,
                    max_chars=FG_RELATED_CHUNK_MAX_CHARS,
                ),
            }
        )
    return related_chunks


def _plan_query_items(
    *,
    chunks: list[ChunkRow],
    config: ExperimentConfig,
    strategy: str,
    prompts: PromptBundle,
    relations: dict[str, dict[str, list[str]]],
    glossary_by_doc: dict[str, list[str]],
    method_id_cache: dict[str, str | None],
    max_total_queries: int | None,
) -> list[PlannedQueryItem]:
    rng = random.Random(config.random_seed)
    planned: list[PlannedQueryItem] = []
    for chunk in chunks:
        if max_total_queries is not None and len(planned) >= max_total_queries:
            break
        chunk_glossary_terms = glossary_by_doc.get(chunk.document_id, [])[:12]
        base_count = max(1, int(round(config.avg_queries_per_chunk + rng.uniform(-0.9, 0.9))))
        source_fingerprint = _source_fingerprint(chunk)
        for query_index in range(base_count):
            if max_total_queries is not None and len(planned) >= max_total_queries:
                break
            query_type = _weighted_choice(rng, config.query_type_distribution)
            answerability_type = _weighted_choice(rng, config.answerability_distribution)
            answerability_type, target_chunk_ids = _select_answerability_target(
                chunk,
                answerability_type,
                relations,
                rng,
            )
            generation_strategy = _generation_strategy_for_query_type(
                strategy,
                query_type,
                config.enable_code_mixed,
            )
            raw_table_name = _raw_table_for_strategy(generation_strategy)
            query_prompt_asset = prompts.query_assets[generation_strategy]
            stable_query_id = _stable_id(
                [
                    generation_strategy,
                    chunk.chunk_id,
                    source_fingerprint,
                    query_prompt_asset.version,
                    query_type,
                    answerability_type,
                    str(query_index),
                ]
            )
            planned.append(
                PlannedQueryItem(
                    chunk=chunk,
                    query_index=query_index,
                    query_type=query_type,
                    answerability_type=answerability_type,
                    target_chunk_ids=target_chunk_ids,
                    generation_strategy=generation_strategy,
                    raw_table_name=raw_table_name,
                    generation_method_id=method_id_cache.get(generation_strategy),
                    query_prompt_asset=query_prompt_asset,
                    query_prompt_text=prompts.query_texts[generation_strategy],
                    stable_query_id=stable_query_id,
                    source_fingerprint=source_fingerprint,
                    glossary_terms_keep_english=chunk_glossary_terms,
                )
            )
    return planned


def _batch_item_mapping(items: list[GeminiBatchRequestItem]) -> list[dict[str, Any]]:
    return [
        {
            "item_key": item.key,
            **{
                key: value
                for key, value in item.metadata.items()
                if key in {"chunk_id", "query_id", "purpose", "query_type", "answerability_type"}
            },
        }
        for item in items
    ]


def _usage_details_from_gemini_usage(usage_metadata: dict[str, Any]) -> dict[str, int]:
    mapping = {
        "prompt_tokens": usage_metadata.get("promptTokenCount"),
        "completion_tokens": usage_metadata.get("candidatesTokenCount"),
        "total_tokens": usage_metadata.get("totalTokenCount"),
    }
    usage: dict[str, int] = {}
    for key, value in mapping.items():
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            usage[key] = int(value)
    return usage


def _batch_failure(
    *,
    item_key: str,
    metadata: dict[str, Any] | None,
    category: str,
    message: str,
    raw: Any | None = None,
) -> dict[str, Any]:
    safe_metadata = metadata or {}
    failure = {
        "item_key": item_key,
        "category": category,
        "message": message,
    }
    for key in ("chunk_id", "query_id", "purpose", "query_type", "answerability_type"):
        if safe_metadata.get(key) is not None:
            failure[key] = safe_metadata.get(key)
    if raw is not None:
        failure["raw"] = raw
    return failure


def _sanitize_batch_display_name(value: str) -> str:
    allowed = []
    for char in value:
        if char.isalnum() or char in {"-", "_"}:
            allowed.append(char)
        else:
            allowed.append("_")
    return "".join(allowed).strip("_")[:96] or "query_forge_batch"


def _create_gemini_batch_adapter(stage_config: Any) -> GeminiBatchAdapter:
    provider = str(stage_config.provider or "").strip().lower()
    if provider not in {"gemini", "gemini-native"}:
        raise ValueError("gemini_batch execution requires llm provider gemini or gemini-native")
    if "/openai" in str(stage_config.base_url or "").rstrip("/"):
        raise ValueError("gemini_batch execution requires native Gemini API base_url, not OpenAI compatibility")
    return GeminiBatchAdapter(
        base_url=stage_config.base_url,
        api_key=stage_config.api_key,
        timeout_seconds=max(60.0, float(stage_config.timeout_seconds)),
    )


def _execute_gemini_batch_json_requests(
    *,
    adapter: GeminiBatchAdapter,
    stage_config: Any,
    items: list[GeminiBatchRequestItem],
    response_schema: dict[str, Any],
    display_name: str,
    input_mode: str,
    work_dir: Path,
    poll_interval_seconds: float,
    timeout_seconds: float,
    request_purpose: str,
) -> BatchJsonExecution:
    display_name = _sanitize_batch_display_name(display_name)
    item_mapping = _batch_item_mapping(items)
    if not items:
        return BatchJsonExecution(
            job_name=None,
            display_name=display_name,
            input_mode=input_mode,
            submitted_item_count=0,
            completed_item_count=0,
            failed_item_count=0,
            batch_stats={},
            item_mapping=item_mapping,
            failures=[],
            responses_by_key={},
        )

    jsonl_path: Path | None = None
    if input_mode == "jsonl":
        jsonl_path = work_dir / f"{display_name}.jsonl"
        submitted_job = adapter.submit_jsonl(
            model=stage_config.model,
            items=items,
            display_name=display_name,
            jsonl_path=jsonl_path,
        )
    else:
        submitted_job = adapter.submit_inline(
            model=stage_config.model,
            items=items,
            display_name=display_name,
        )
    if not submitted_job.name:
        raise GeminiBatchExecutionError("Gemini batch submission did not return a job name.")

    LOGGER.info(
        "gemini_batch_submitted purpose=%s job=%s display_name=%s input_mode=%s items=%s",
        request_purpose,
        submitted_job.name,
        display_name,
        input_mode,
        len(items),
    )
    final_job = adapter.poll_job(
        name=submitted_job.name,
        poll_interval_seconds=poll_interval_seconds,
        timeout_seconds=timeout_seconds,
    )
    if not final_job.succeeded:
        raise GeminiBatchExecutionError(
            f"Gemini batch job did not succeed. job={final_job.name} state={final_job.state}",
            job=final_job,
            failures=[
                {
                    "category": "batch_job_failed",
                    "state": final_job.state,
                    "job_name": final_job.name,
                    "raw": final_job.raw,
                }
            ],
        )

    raw_results = adapter.fetch_results(job=final_job, expected_items=items)
    item_metadata_by_key = {item.key: item.metadata for item in items}
    expected_keys = [item.key for item in items]
    responses_by_key: dict[str, dict[str, Any]] = {}
    failures: list[dict[str, Any]] = []
    seen_keys: set[str] = set()

    for result in raw_results:
        item_key = result.key
        metadata = result.metadata or item_metadata_by_key.get(item_key, {})
        if not item_key:
            failures.append(
                _batch_failure(
                    item_key="",
                    metadata=metadata,
                    category="missing_item_key",
                    message="Gemini batch result did not include an item key.",
                    raw=result.raw,
                )
            )
            continue
        if item_key in seen_keys:
            failures.append(
                _batch_failure(
                    item_key=item_key,
                    metadata=metadata,
                    category="duplicate_item_key",
                    message="Gemini batch returned duplicate item key.",
                    raw=result.raw,
                )
            )
            continue
        seen_keys.add(item_key)
        if result.error is not None:
            failures.append(
                _batch_failure(
                    item_key=item_key,
                    metadata=metadata,
                    category="batch_item_error",
                    message=str(result.error.get("message") or result.error.get("status") or result.error.get("code") or "batch item failed"),
                    raw=result.error,
                )
            )
            continue
        if result.response is None:
            failures.append(
                _batch_failure(
                    item_key=item_key,
                    metadata=metadata,
                    category="response_missing",
                    message="Gemini batch item had no response or error.",
                    raw=result.raw,
                )
            )
            continue
        try:
            parsed = parse_gemini_json_response(result.response, response_schema=response_schema)
        except Exception as exc:  # noqa: BLE001
            failures.append(
                _batch_failure(
                    item_key=item_key,
                    metadata=metadata,
                    category="invalid_json",
                    message=str(exc),
                    raw=result.response,
                )
            )
            continue
        usage_details = _usage_details_from_gemini_usage(result.usage_metadata)
        parsed["_llm_meta"] = {
            "provider": stage_config.provider,
            "provider_type": "gemini-native",
            "model": stage_config.model,
            "fallback_used": False,
            "structured_output_used": True,
            "thinking_budget": stage_config.thinking_budget,
            "retry_count": 0,
            "request_purpose": request_purpose,
            "trace_id": metadata.get("trace_id") or item_key,
            "usage": usage_details,
            "usage_metadata": result.usage_metadata,
            "gemini_batch": {
                "job_name": final_job.name,
                "item_key": item_key,
                "display_name": display_name,
                "input_mode": input_mode,
                "batch_stats": final_job.batch_stats,
            },
        }
        responses_by_key[item_key] = parsed

    for expected_key in expected_keys:
        if expected_key not in seen_keys:
            failures.append(
                _batch_failure(
                    item_key=expected_key,
                    metadata=item_metadata_by_key.get(expected_key, {}),
                    category="missing_batch_result",
                    message="Gemini batch did not return a result for this item.",
                )
            )

    execution = BatchJsonExecution(
        job_name=final_job.name,
        display_name=display_name,
        input_mode=input_mode,
        submitted_item_count=len(items),
        completed_item_count=len(responses_by_key),
        failed_item_count=len(failures),
        batch_stats=final_job.batch_stats,
        item_mapping=item_mapping,
        failures=failures,
        responses_by_key=responses_by_key,
        jsonl_path=str(jsonl_path) if jsonl_path is not None else None,
    )
    if failures:
        raise GeminiBatchExecutionError(
            f"Gemini batch item failures. job={final_job.name} failures={len(failures)}/{len(items)}",
            job=final_job,
            failures=failures,
        )
    return execution


def _batch_observability(stage: str, execution: BatchJsonExecution) -> dict[str, Any]:
    return {
        "stage": stage,
        "job_name": execution.job_name,
        "display_name": execution.display_name,
        "input_mode": execution.input_mode,
        "submitted_item_count": execution.submitted_item_count,
        "completed_item_count": execution.completed_item_count,
        "failed_item_count": execution.failed_item_count,
        "batch_stats": execution.batch_stats,
        "item_mapping": execution.item_mapping,
        "failures": execution.failures,
        "jsonl_path": execution.jsonl_path,
    }


def _run_strategy_b_gemini_batch(
    *,
    connection: psycopg.Connection[Any],
    config: ExperimentConfig,
    run_context_id: str,
    prompts: PromptBundle,
    chunks: list[ChunkRow],
    chunks_by_id: dict[str, ChunkRow],
    relations: dict[str, dict[str, list[str]]],
    glossary_by_doc: dict[str, list[str]],
    glossary_term_candidates_by_doc: dict[str, list[dict[str, Any]]] | None = None,
    canonical_mapping_connection: Any | None = None,
    generation_batch_id: str | None,
    method_id_cache: dict[str, str | None],
    max_total_queries: int | None,
    initial_generated_count: int,
    b_summary_max_chars: int,
    b_payload_limits: BQueryPayloadLimits,
    query_client: LlmClient,
    translate_client: LlmClient,
    input_mode: str,
    poll_interval_seconds: float,
    timeout_seconds: float,
    work_dir: Path,
) -> dict[str, Any]:
    if max_total_queries is not None and initial_generated_count >= max_total_queries:
        return {
            "planned_queries": 0,
            "initial_generated_queries": initial_generated_count,
            "new_generated_queries": 0,
            "generated_queries": initial_generated_count,
            "reused_queries": 0,
            "skipped_empty_queries": 0,
            "query_type_distribution": {},
            "answerability_distribution": {},
            "asset_created": {},
            "asset_cache_hits": {},
            "preview_query_ids": [],
            "gemini_batch": [],
        }

    planned_items = _plan_query_items(
        chunks=chunks,
        config=config,
        strategy="B",
        prompts=prompts,
        relations=relations,
        glossary_by_doc=glossary_by_doc,
        method_id_cache=method_id_cache,
        max_total_queries=max_total_queries,
    )
    if any(item.generation_strategy != "B" for item in planned_items):
        raise RuntimeError("Strategy B Gemini Batch path only supports B query items.")

    asset_cache_hits: Counter[str] = Counter()
    asset_created: Counter[str] = Counter()
    query_type_counter: Counter[str] = Counter()
    answerability_counter: Counter[str] = Counter()
    generated_ids: list[str] = []
    batch_observability: list[dict[str, Any]] = []

    unique_plans_by_chunk: dict[str, PlannedQueryItem] = {}
    for item in planned_items:
        unique_plans_by_chunk.setdefault(item.chunk.chunk_id, item)

    full_translation_prompt_version = _translation_full_prompt_version(prompts.translate_asset.version)
    segment_translation_prompt_version = _translation_segment_prompt_version(prompts.translate_asset.version)
    translation_by_chunk: dict[str, BTranslationAssetState] = {}
    translation_batch_items: list[GeminiBatchRequestItem] = []
    translation_item_plan: dict[str, tuple[PlannedQueryItem, TranslationSegment]] = {}
    translation_segments_by_chunk: dict[str, list[TranslationSegment]] = {}
    translated_segment_text_by_chunk: dict[str, dict[int, str]] = defaultdict(dict)
    translated_segment_meta_by_chunk: dict[str, list[dict[str, Any]]] = defaultdict(list)
    translated_segment_asset_by_item: dict[str, str] = {}
    for plan in unique_plans_by_chunk.values():
        cached = _find_existing_asset(
            connection,
            chunk_id=plan.chunk.chunk_id,
            asset_type="KO_TRANSLATED_CHUNK",
            llm_provider=translate_client.config.provider,
            llm_model=translate_client.config.model,
            prompt_template_version=full_translation_prompt_version,
            source_fingerprint=plan.source_fingerprint,
        )
        if cached:
            translation_by_chunk[plan.chunk.chunk_id] = BTranslationAssetState(
                asset_id=cached[0],
                translated_chunk_ko=cached[1],
                cached=True,
            )
            asset_cache_hits["KO_TRANSLATED_CHUNK"] += 1
            continue
        segments = _build_translation_segments(plan.chunk.chunk_text)
        translation_segments_by_chunk[plan.chunk.chunk_id] = segments
        for segment in segments:
            if segment.kind in {"code", "blank"}:
                translated_segment_text_by_chunk[plan.chunk.chunk_id][segment.index] = segment.text
                translated_segment_meta_by_chunk[plan.chunk.chunk_id].append(
                    {
                        "index": segment.index,
                        "kind": segment.kind,
                        "start_offset": segment.start_offset,
                        "end_offset": segment.end_offset,
                        "source_hash": segment.source_hash,
                        "translation_source": "verbatim",
                    }
                )
                continue
            segment_source_fingerprint = _translation_segment_source_fingerprint(
                source_fingerprint=plan.source_fingerprint,
                segment=segment,
            )
            cached_segment = _find_existing_asset(
                connection,
                chunk_id=plan.chunk.chunk_id,
                asset_type="KO_TRANSLATED_CHUNK",
                llm_provider=translate_client.config.provider,
                llm_model=translate_client.config.model,
                prompt_template_version=segment_translation_prompt_version,
                source_fingerprint=segment_source_fingerprint,
            )
            if cached_segment:
                translated_segment_text_by_chunk[plan.chunk.chunk_id][segment.index] = cached_segment[1]
                translated_segment_meta_by_chunk[plan.chunk.chunk_id].append(
                    {
                        "index": segment.index,
                        "kind": segment.kind,
                        "start_offset": segment.start_offset,
                        "end_offset": segment.end_offset,
                        "source_hash": segment.source_hash,
                        "segment_asset_id": cached_segment[0],
                        "cached": True,
                    }
                )
                asset_cache_hits["KO_TRANSLATED_CHUNK_SEGMENT"] += 1
                continue

            item_key = f"translate:{plan.chunk.chunk_id}:{segment.index}"
            translation_payload = {
                "chunk_id": plan.chunk.chunk_id,
                "title": plan.chunk.title,
                "translation_mode": "segmented_full",
                "segmentation_version": TRANSLATION_SEGMENTATION_VERSION,
                "segment_index": segment.index,
                "segment_count": len(segments),
                "segment_kind": segment.kind,
                "chunk_text_en": segment.text,
            }
            translation_batch_items.append(
                GeminiBatchRequestItem(
                    key=item_key,
                    request=build_gemini_generate_content_request(
                        translate_client.config,
                        system_prompt=prompts.translate_text,
                        user_prompt=json.dumps(translation_payload, ensure_ascii=False, indent=2),
                        response_schema=TRANSLATION_RESPONSE_SCHEMA,
                    ),
                    metadata={
                        "purpose": "translate_chunk_en_to_ko_segment",
                        "chunk_id": plan.chunk.chunk_id,
                        "segment_index": segment.index,
                        "segment_count": len(segments),
                        "trace_id": f"chunk:{plan.chunk.chunk_id}:segment:{segment.index}",
                    },
                )
            )
            translation_item_plan[item_key] = (plan, segment)

    translation_execution = _execute_gemini_batch_json_requests(
        adapter=_create_gemini_batch_adapter(translate_client.config),
        stage_config=translate_client.config,
        items=translation_batch_items,
        response_schema=TRANSLATION_RESPONSE_SCHEMA,
        display_name=f"{config.experiment_key}_b_translation",
        input_mode=input_mode,
        work_dir=work_dir,
        poll_interval_seconds=poll_interval_seconds,
        timeout_seconds=timeout_seconds,
        request_purpose="translate_chunk_en_to_ko",
    )
    batch_observability.append(_batch_observability("b_translation", translation_execution))
    for item_key, response in translation_execution.responses_by_key.items():
        plan, segment = translation_item_plan[item_key]
        translated = str(response.get("translated_chunk_ko") or "").strip()
        if not translated:
            raise RuntimeError(f"empty translated_chunk_ko for chunk={plan.chunk.chunk_id} segment={segment.index} from Gemini batch")
        llm_meta = response.get("_llm_meta") if isinstance(response.get("_llm_meta"), dict) else {}
        segment_source_fingerprint = _translation_segment_source_fingerprint(
            source_fingerprint=plan.source_fingerprint,
            segment=segment,
        )
        asset_id = _create_asset(
            connection,
            chunk=plan.chunk,
            asset_type="KO_TRANSLATED_CHUNK",
            text_content=translated,
            llm_provider=translate_client.config.provider,
            llm_model=translate_client.config.model,
            prompt_template_version=segment_translation_prompt_version,
            source_fingerprint=segment_source_fingerprint,
            metadata={
                "translation_mode": "segmented_full",
                "segment_role": "segment",
                "segmentation_version": TRANSLATION_SEGMENTATION_VERSION,
                "segment_index": segment.index,
                "segment_count": len(translation_segments_by_chunk[plan.chunk.chunk_id]),
                "segment_kind": segment.kind,
                "source_hash": segment.source_hash,
                "start_offset": segment.start_offset,
                "end_offset": segment.end_offset,
                "source_chunk_fingerprint": plan.source_fingerprint,
                "llm_execution_mode": "gemini_batch",
                "gemini_batch": llm_meta.get("gemini_batch"),
                "usage": llm_meta.get("usage"),
                "usage_metadata": llm_meta.get("usage_metadata"),
            },
        )
        translated_segment_asset_by_item[item_key] = asset_id
        translated_segment_text_by_chunk[plan.chunk.chunk_id][segment.index] = translated
        translated_segment_meta_by_chunk[plan.chunk.chunk_id].append(
            {
                "index": segment.index,
                "kind": segment.kind,
                "start_offset": segment.start_offset,
                "end_offset": segment.end_offset,
                "source_hash": segment.source_hash,
                "segment_asset_id": asset_id,
                "cached": False,
            }
        )
        asset_created["KO_TRANSLATED_CHUNK_SEGMENT"] += 1
    if translated_segment_asset_by_item:
        _commit_if_supported(connection)

    for plan in unique_plans_by_chunk.values():
        if plan.chunk.chunk_id in translation_by_chunk:
            continue
        segments = translation_segments_by_chunk.get(plan.chunk.chunk_id)
        if not segments:
            raise RuntimeError(f"missing translation segments for chunk={plan.chunk.chunk_id}")
        segment_text_by_index = translated_segment_text_by_chunk.get(plan.chunk.chunk_id, {})
        missing_segments = [segment.index for segment in segments if segment.index not in segment_text_by_index]
        if missing_segments:
            raise RuntimeError(f"missing translated segments for chunk={plan.chunk.chunk_id}: {missing_segments}")
        translated = "".join(segment_text_by_index[segment.index] for segment in segments).strip()
        if not translated:
            raise RuntimeError(f"empty segmented translated_chunk_ko for chunk={plan.chunk.chunk_id} from Gemini batch")
        segment_meta = sorted(
            translated_segment_meta_by_chunk.get(plan.chunk.chunk_id, []),
            key=lambda item: int(item.get("index", 0)),
        )
        asset_id = _create_asset(
            connection,
            chunk=plan.chunk,
            asset_type="KO_TRANSLATED_CHUNK",
            text_content=translated,
            llm_provider=translate_client.config.provider,
            llm_model=translate_client.config.model,
            prompt_template_version=full_translation_prompt_version,
            source_fingerprint=plan.source_fingerprint,
            metadata={
                "source": "en_chunk",
                "translation_mode": "segmented_full",
                "segmentation_version": TRANSLATION_SEGMENTATION_VERSION,
                "segment_count": len(segments),
                "segment_cache_hits": sum(1 for item in segment_meta if item.get("cached") is True),
                "segment_created": sum(1 for item in segment_meta if item.get("cached") is False),
                "source_checksum": _sha256_text(plan.chunk.chunk_text),
                "source_fingerprint": plan.source_fingerprint,
                "segments": segment_meta,
                "reconstruction_checksum": _sha256_text(translated),
                "llm_execution_mode": "gemini_batch",
            },
        )
        translation_by_chunk[plan.chunk.chunk_id] = BTranslationAssetState(
            asset_id=asset_id,
            translated_chunk_ko=translated,
            cached=False,
        )
        asset_created["KO_TRANSLATED_CHUNK"] += 1

    summary_by_chunk: dict[str, BSummaryAssetState] = {}
    for plan in unique_plans_by_chunk.values():
        translation = translation_by_chunk.get(plan.chunk.chunk_id)
        if translation is None:
            raise RuntimeError(f"missing translation asset for chunk={plan.chunk.chunk_id}")
        b_summary_source_fingerprint = _stable_id([plan.source_fingerprint, translation.asset_id])
        summary_ko_asset_id, summary_ko, summary_ko_cached = _resolve_or_create_extractive_summary_ko(
            connection,
            chunk=plan.chunk,
            source_fingerprint=b_summary_source_fingerprint,
            prompt_asset=prompts.summary_ko_asset,
            prompt_version_suffix="B",
            source_text_ko=translation.translated_chunk_ko,
            max_chars=b_summary_max_chars,
            metadata={
                "source_translation_asset_id": translation.asset_id,
                "source_translation_prompt_version": prompts.translate_asset.version,
            },
        )
        if summary_ko_cached:
            asset_cache_hits["KO_SUMMARY"] += 1
        else:
            asset_created["KO_SUMMARY"] += 1
        summary_by_chunk[plan.chunk.chunk_id] = BSummaryAssetState(
            asset_id=summary_ko_asset_id,
            summary_ko=summary_ko,
            cached=summary_ko_cached,
        )

    generated_count = max(0, initial_generated_count)
    new_generated_count = 0
    reused_count = 0
    reserved_new_count = 0
    query_batch_items: list[GeminiBatchRequestItem] = []
    pending_by_key: dict[str, PendingBatchQueryRow] = {}
    for plan in planned_items:
        if max_total_queries is not None and generated_count + reserved_new_count >= max_total_queries:
            break
        translation = translation_by_chunk[plan.chunk.chunk_id]
        summary = summary_by_chunk[plan.chunk.chunk_id]
        generation_asset_ids = [translation.asset_id, summary.asset_id]
        if _find_cached_query(
            connection,
            table_name=plan.raw_table_name,
            synthetic_query_id=plan.stable_query_id,
            source_fingerprint=plan.source_fingerprint,
            prompt_template_version=plan.query_prompt_asset.version,
        ):
            _attach_cached_query(
                connection,
                table_name=plan.raw_table_name,
                synthetic_query_id=plan.stable_query_id,
                generation_method_id=plan.generation_method_id,
                generation_batch_id=generation_batch_id,
                llm_provider=query_client.config.provider,
                llm_model=query_client.config.model,
                generation_asset_ids=generation_asset_ids,
            )
            _insert_source_links_for_targets(
                connection,
                synthetic_query_id=plan.stable_query_id,
                primary_chunk=plan.chunk,
                target_chunk_ids=plan.target_chunk_ids,
                chunks_by_id=chunks_by_id,
            )
            generated_count = _refresh_generation_count(
                connection,
                generation_batch_id,
                generated_count,
            )
            reused_count += 1
            continue

        query_payload = _build_query_payload(
            chunk=plan.chunk,
            generation_strategy=plan.generation_strategy,
            original_chunk_ko=plan.chunk.chunk_text,
            related_chunks_ko=[],
            extractive_summary_en="",
            translated_chunk_ko=translation.translated_chunk_ko,
            extractive_summary_ko=summary.summary_ko,
            glossary_terms_keep_english=plan.glossary_terms_keep_english,
            query_type=plan.query_type,
            answerability_type=plan.answerability_type,
            target_chunk_ids=plan.target_chunk_ids,
            b_payload_limits=b_payload_limits,
        )
        item_key = f"query:{plan.stable_query_id}"
        query_batch_items.append(
            GeminiBatchRequestItem(
                key=item_key,
                request=build_gemini_generate_content_request(
                    query_client.config,
                    system_prompt=plan.query_prompt_text,
                    user_prompt=json.dumps(query_payload, ensure_ascii=False, indent=2),
                    response_schema=_query_response_schema_for_strategy("B"),
                ),
                metadata={
                    "purpose": "generate_query",
                    "chunk_id": plan.chunk.chunk_id,
                    "query_id": plan.stable_query_id,
                    "query_type": plan.query_type,
                    "answerability_type": plan.answerability_type,
                    "trace_id": f"query:{plan.stable_query_id}",
                },
            )
        )
        pending_by_key[item_key] = PendingBatchQueryRow(
            plan=plan,
            query_payload=query_payload,
            generation_asset_ids=generation_asset_ids,
            translated_chunk_ko=translation.translated_chunk_ko,
            summary_ko=summary.summary_ko,
            related_chunks_ko=[],
        )
        reserved_new_count += 1

    query_execution = _execute_gemini_batch_json_requests(
        adapter=_create_gemini_batch_adapter(query_client.config),
        stage_config=query_client.config,
        items=query_batch_items,
        response_schema=_query_response_schema_for_strategy("B"),
        display_name=f"{config.experiment_key}_b_query",
        input_mode=input_mode,
        work_dir=work_dir,
        poll_interval_seconds=poll_interval_seconds,
        timeout_seconds=timeout_seconds,
        request_purpose="generate_query",
    )
    batch_observability.append(_batch_observability("b_query", query_execution))

    row_payloads: list[tuple[str, PlannedQueryItem, dict[str, Any]]] = []
    empty_failures: list[dict[str, Any]] = []
    for item_key, pending in pending_by_key.items():
        query_response = query_execution.responses_by_key[item_key]
        query_text, extra_trace = _extract_query_text(
            generation_strategy=pending.plan.generation_strategy,
            query_type=pending.plan.query_type,
            response=query_response,
        )
        if not query_text:
            empty_failures.append(
                _batch_failure(
                    item_key=item_key,
                    metadata={
                        "purpose": "generate_query",
                        "chunk_id": pending.plan.chunk.chunk_id,
                        "query_id": pending.plan.stable_query_id,
                        "query_type": pending.plan.query_type,
                        "answerability_type": pending.plan.answerability_type,
                    },
                    category="empty_query_text",
                    message="Gemini batch query response did not contain query text.",
                    raw=query_response,
                )
            )
            continue
        payload = _build_query_row_payload(
            canonical_mapping_connection=canonical_mapping_connection,
            glossary_term_candidates=(glossary_term_candidates_by_doc or {}).get(
                pending.plan.chunk.document_id,
                [],
            ),
            synthetic_query_id=pending.plan.stable_query_id,
            run_context_id=run_context_id,
            generation_method_id=pending.plan.generation_method_id,
            generation_batch_id=generation_batch_id,
            chunk=pending.plan.chunk,
            generation_strategy=pending.plan.generation_strategy,
            query_prompt_asset=pending.plan.query_prompt_asset,
            source_fingerprint=pending.plan.source_fingerprint,
            target_chunk_ids=pending.plan.target_chunk_ids,
            answerability_type=pending.plan.answerability_type,
            query_text=query_text,
            query_type=pending.plan.query_type,
            generation_asset_ids=pending.generation_asset_ids,
            query_response=query_response,
            extra_trace=extra_trace,
            chunk_glossary_terms=pending.plan.glossary_terms_keep_english,
            en_summary="",
            summary_ko=pending.summary_ko,
            translated_chunk_ko=pending.translated_chunk_ko,
            query_payload=pending.query_payload,
            b_payload_limits=b_payload_limits,
            fg_summary_mode="llm",
            related_chunks_ko=pending.related_chunks_ko,
            llm_provider=query_client.config.provider,
            llm_model=query_client.config.model,
            execution_mode="gemini_batch",
        )
        row_payloads.append((pending.plan.raw_table_name, pending.plan, payload))
    if empty_failures:
        raise GeminiBatchExecutionError(
            f"Gemini batch query outputs were empty. failures={len(empty_failures)}",
            failures=empty_failures,
        )

    skipped_empty_count = 0
    for raw_table_name, plan, payload in row_payloads:
        _insert_query_row(connection, table_name=raw_table_name, payload=payload)
        _insert_source_links_for_targets(
            connection,
            synthetic_query_id=plan.stable_query_id,
            primary_chunk=plan.chunk,
            target_chunk_ids=plan.target_chunk_ids,
            chunks_by_id=chunks_by_id,
        )
        generated_count = _refresh_generation_count(
            connection,
            generation_batch_id,
            generated_count,
        )
        new_generated_count += 1
        query_type_counter[plan.query_type] += 1
        answerability_counter[plan.answerability_type] += 1
        if len(generated_ids) < 20:
            generated_ids.append(plan.stable_query_id)

    return {
        "planned_queries": len(planned_items),
        "initial_generated_queries": initial_generated_count,
        "new_generated_queries": new_generated_count,
        "generated_queries": generated_count,
        "reused_queries": reused_count,
        "skipped_empty_queries": skipped_empty_count,
        "query_type_distribution": dict(query_type_counter),
        "answerability_distribution": dict(answerability_counter),
        "asset_created": dict(asset_created),
        "asset_cache_hits": dict(asset_cache_hits),
        "preview_query_ids": generated_ids,
        "gemini_batch": batch_observability,
    }


def run_generation(
    *,
    experiment: str,
    experiment_root: Path = Path("configs/experiments"),
    prompt_root: Path = Path("configs/prompts"),
    database_url: str | None = None,
    db_host: str = "localhost",
    db_port: int = 5432,
    db_name: str = "query_forge",
    db_user: str = "query_forge",
    db_password: str = "query_forge",
) -> dict[str, Any]:
    config = load_experiment_config(experiment, experiment_root=experiment_root)
    strategy = config.generation_strategy.strip().upper()
    b_summary_max_chars = _b_summary_max_chars(config.raw) if strategy == "B" else B_DEFAULT_SUMMARY_MAX_CHARS
    b_payload_limits = _b_query_payload_limits(config.raw) if strategy == "B" else None
    llm_execution_mode = _llm_execution_mode(config.raw)
    if llm_execution_mode == "gemini_batch" and strategy != "B":
        raise ValueError("llm_execution_mode=gemini_batch is currently supported only for Strategy B")
    fg_summary_mode = _fg_summary_mode(config.raw) if strategy in {"F", "G"} else "llm"
    fg_summary_max_chars = (
        _fg_summary_max_chars(config.raw)
        if strategy in {"F", "G"}
        else FG_DEFAULT_SUMMARY_MAX_CHARS
    )
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
        sync_shadow_tables(connection)
        recorder = ExperimentRunRecorder(connection)
        run_context = recorder.start_run(
            experiment_key=config.experiment_key,
            category=config.category,
            description=config.description,
            config_path=str(config.config_path),
            config_hash=config.config_hash,
            parameters={
                "stage": "generate-queries",
                "generation_strategy": config.generation_strategy,
                "enable_code_mixed": config.enable_code_mixed,
                "avg_queries_per_chunk": config.avg_queries_per_chunk,
                "max_total_queries": config.raw.get("max_total_queries"),
                "source_id": config.raw.get("source_id"),
                "source_ids": config.raw.get("source_ids"),
                "source_document_id": config.raw.get("source_document_id"),
                "random_chunk_sampling": bool(config.raw.get("random_chunk_sampling", False)),
                "llm_execution_mode": llm_execution_mode,
                "gemini_batch_input_mode": (
                    _gemini_batch_input_mode(config.raw)
                    if llm_execution_mode == "gemini_batch"
                    else None
                ),
                "b_summary_mode": "extractive" if strategy == "B" else None,
                "b_summary_max_chars": b_summary_max_chars if strategy == "B" else None,
                "b_query_payload_limits": (
                    _b_query_payload_limits_dict(b_payload_limits)
                    if strategy == "B"
                    else None
                ),
                "fg_summary_mode": fg_summary_mode if strategy in {"F", "G"} else None,
                "fg_summary_max_chars": fg_summary_max_chars if strategy in {"F", "G"} else None,
            },
            run_label="generate-queries",
        )

        prompts = _resolve_prompt_bundle(connection, config=config, prompt_root=prompt_root)
        summary_stage_config = load_stage_config(stage="summary", raw_config=config.raw)
        summary_client = LlmClient(summary_stage_config)
        summary_client_for_ko_long = summary_client
        summary_max_tokens = _summary_max_tokens_for_strategy(
            generation_strategy=config.generation_strategy,
            base_max_tokens=summary_stage_config.max_tokens,
        )
        if summary_max_tokens != summary_stage_config.max_tokens:
            summary_client_for_ko_long = LlmClient(
                replace(summary_stage_config, max_tokens=summary_max_tokens)
            )
        query_client = LlmClient(load_stage_config(stage="query", raw_config=config.raw))
        translate_client = LlmClient(load_stage_config(stage="translation", raw_config=config.raw))

        source_document_id = str(config.raw.get("source_document_id") or "").strip() or None
        source_id = str(config.raw.get("source_id") or "").strip() or None
        source_ids = _normalize_source_ids(config.raw.get("source_ids"))
        random_chunk_sampling = bool(config.raw.get("random_chunk_sampling", False))
        chunks = _load_chunks(
            connection,
            limit=config.limit_chunks,
            source_document_id=source_document_id,
            source_id=source_id,
            source_ids=source_ids,
            random_chunk_sampling=random_chunk_sampling,
            random_seed=config.random_seed if random_chunk_sampling else None,
        )
        if not chunks:
            LOGGER.warning(
                "No chunks found for source_document_id=%s source_id=%s source_ids=%s limit_chunks=%s",
                source_document_id,
                source_id,
                source_ids,
                config.limit_chunks,
            )
        chunks_by_id = {chunk.chunk_id: chunk for chunk in chunks}
        selected_chunk_ids = set(chunks_by_id)
        selected_document_ids = {chunk.document_id for chunk in chunks}
        relations = _load_relations(connection, chunk_ids=selected_chunk_ids)
        glossary_by_doc = _load_glossary(connection, document_ids=selected_document_ids)
        glossary_term_candidates_by_doc = _load_glossary_term_candidates(
            connection,
            document_ids=selected_document_ids,
        )
        canonical_mapping_connection = connection if _canonical_mapping_table_available(connection) else None
        rng = random.Random(config.random_seed)

        generation_batch_id = str(config.raw.get("generation_batch_id") or "").strip() or None
        if generation_batch_id and not _batch_exists(connection, generation_batch_id):
            LOGGER.warning(
                "generation_batch_id=%s not found. storing queries with NULL batch id and deferring linkage by experiment_run_id",
                generation_batch_id,
            )
            generation_batch_id = None

        method_id_cache: dict[str, str | None] = {
            strategy: _resolve_generation_method_id(connection, strategy),
            "D": _resolve_generation_method_id(connection, "D"),
        }
        generated_count = 0
        initial_generated_count = 0
        new_generated_count = 0
        reused_count = 0
        skipped_empty_count = 0
        query_type_counter: Counter[str] = Counter()
        answerability_counter: Counter[str] = Counter()
        generated_ids: list[str] = []
        asset_cache_hits: Counter[str] = Counter()
        asset_created: Counter[str] = Counter()
        llm_batch_size = int(config.raw.get("llm_batch_size") or 20)
        llm_batch_size = max(1, min(llm_batch_size, 20))
        max_total_queries = config.raw.get("max_total_queries")
        max_total_queries = int(max_total_queries) if max_total_queries is not None else None
        if max_total_queries is not None and max_total_queries <= 0:
            max_total_queries = None
        if generation_batch_id:
            generated_count = _count_queries_for_generation_batch(connection, generation_batch_id)
            initial_generated_count = generated_count

        if llm_execution_mode == "gemini_batch":
            if b_payload_limits is None:
                raise RuntimeError("Strategy B batch execution requires B payload limits.")
            batch_result = _run_strategy_b_gemini_batch(
                connection=connection,
                config=config,
                run_context_id=run_context.experiment_run_id,
                prompts=prompts,
                chunks=chunks,
                chunks_by_id=chunks_by_id,
                relations=relations,
                glossary_by_doc=glossary_by_doc,
                glossary_term_candidates_by_doc=glossary_term_candidates_by_doc,
                canonical_mapping_connection=canonical_mapping_connection,
                generation_batch_id=generation_batch_id,
                method_id_cache=method_id_cache,
                max_total_queries=max_total_queries,
                initial_generated_count=initial_generated_count,
                b_summary_max_chars=b_summary_max_chars,
                b_payload_limits=b_payload_limits,
                query_client=query_client,
                translate_client=translate_client,
                input_mode=_gemini_batch_input_mode(config.raw),
                poll_interval_seconds=_gemini_batch_poll_interval_seconds(config.raw),
                timeout_seconds=_gemini_batch_timeout_seconds(config.raw),
                work_dir=_gemini_batch_work_dir(config.raw),
            )
            summary = {
                "experiment_key": config.experiment_key,
                "experiment_run_id": run_context.experiment_run_id,
                "generation_strategy": strategy,
                "source_id": source_id,
                "source_ids": source_ids,
                "source_document_id": source_document_id,
                "max_total_queries": max_total_queries,
                "random_chunk_sampling": random_chunk_sampling,
                "relation_source_chunks_loaded": len(relations),
                "glossary_documents_loaded": len(glossary_by_doc),
                "llm_execution_mode": llm_execution_mode,
                "gemini_batch_input_mode": _gemini_batch_input_mode(config.raw),
                "b_summary_mode": "extractive",
                "b_summary_max_chars": b_summary_max_chars,
                "b_query_payload_limits": _b_query_payload_limits_dict(b_payload_limits),
                "fg_summary_mode": fg_summary_mode,
                "fg_summary_max_chars": fg_summary_max_chars,
                "llm": {
                    "summary": {
                        "provider": summary_client.config.provider,
                        "model": summary_client.config.model,
                    },
                    "query": {
                        "provider": query_client.config.provider,
                        "model": query_client.config.model,
                    },
                    "translation": {
                        "provider": translate_client.config.provider,
                        "model": translate_client.config.model,
                    },
                },
                "prompt_assets": {
                    "summary_en": {
                        "id": prompts.summary_en_asset.prompt_name,
                        "version": prompts.summary_en_asset.version,
                        "hash": prompts.summary_en_asset.content_hash,
                        "asset_id": prompts.summary_en_asset.prompt_asset_id,
                    },
                    "summary_ko": {
                        "id": prompts.summary_ko_asset.prompt_name,
                        "version": prompts.summary_ko_asset.version,
                        "hash": prompts.summary_ko_asset.content_hash,
                        "asset_id": prompts.summary_ko_asset.prompt_asset_id,
                    },
                    "translate": {
                        "id": prompts.translate_asset.prompt_name,
                        "version": prompts.translate_asset.version,
                        "hash": prompts.translate_asset.content_hash,
                        "asset_id": prompts.translate_asset.prompt_asset_id,
                    },
                },
                "chunks_processed": len(chunks),
                **batch_result,
            }
            recorder.finish_run(run_context, status="completed", metrics=summary)
            connection.commit()
            return summary

        for chunk_index, chunk in enumerate(chunks):
            if max_total_queries is not None and generated_count >= max_total_queries:
                break
            chunk_glossary_terms = glossary_by_doc.get(chunk.document_id, [])[:12]
            chunk_glossary_term_candidates = glossary_term_candidates_by_doc.get(chunk.document_id, [])
            base_count = max(1, int(round(config.avg_queries_per_chunk + rng.uniform(-0.9, 0.9))))
            source_fingerprint = _source_fingerprint(chunk)
            for query_index in range(base_count):
                if max_total_queries is not None and generated_count >= max_total_queries:
                    break
                query_type = _weighted_choice(rng, config.query_type_distribution)
                answerability_type = _weighted_choice(rng, config.answerability_distribution)
                answerability_type, target_chunk_ids = _select_answerability_target(
                    chunk,
                    answerability_type,
                    relations,
                    rng,
                )
                generation_strategy = _generation_strategy_for_query_type(
                    strategy,
                    query_type,
                    config.enable_code_mixed,
                )
                raw_table_name = _raw_table_for_strategy(generation_strategy)
                generation_method_id = method_id_cache.get(generation_strategy)
                query_prompt_asset = prompts.query_assets[generation_strategy]
                query_prompt_text = prompts.query_texts[generation_strategy]
                stable_query_id = _stable_id(
                    [
                        generation_strategy,
                        chunk.chunk_id,
                        source_fingerprint,
                        query_prompt_asset.version,
                        query_type,
                        answerability_type,
                        str(query_index),
                    ]
                )

                generation_asset_ids: list[str] = []
                en_summary = ""
                original_chunk_ko = (
                    _primary_chunk_text(chunk.chunk_text)
                    if generation_strategy in {"F", "G"}
                    else chunk.chunk_text
                )
                if _requires_en_summary_asset(generation_strategy):
                    en_summary_asset_id, en_summary, en_summary_cached = _resolve_or_create_summary_en(
                        connection,
                        chunk=chunk,
                        source_fingerprint=source_fingerprint,
                        prompt_asset=prompts.summary_en_asset,
                        prompt_text=prompts.summary_en_text,
                        client=summary_client,
                    )
                    if en_summary_cached:
                        asset_cache_hits["EN_EXTRACTIVE_SUMMARY"] += 1
                    else:
                        asset_created["EN_EXTRACTIVE_SUMMARY"] += 1
                    generation_asset_ids.append(en_summary_asset_id)

                translated_chunk_ko = ""
                summary_ko = ""

                if generation_strategy == "B":
                    translate_asset_id, translated_chunk_ko, translated_cached = _resolve_or_create_translated_chunk(
                        connection,
                        chunk=chunk,
                        source_fingerprint=source_fingerprint,
                        prompt_asset=prompts.translate_asset,
                        prompt_text=prompts.translate_text,
                        client=translate_client,
                    )
                    if translated_cached:
                        asset_cache_hits["KO_TRANSLATED_CHUNK"] += 1
                    else:
                        asset_created["KO_TRANSLATED_CHUNK"] += 1
                    generation_asset_ids.append(translate_asset_id)

                    b_summary_source_fingerprint = _stable_id([source_fingerprint, translate_asset_id])
                    summary_ko_asset_id, summary_ko, summary_ko_cached = _resolve_or_create_extractive_summary_ko(
                        connection,
                        chunk=chunk,
                        source_fingerprint=b_summary_source_fingerprint,
                        prompt_asset=prompts.summary_ko_asset,
                        prompt_version_suffix="B",
                        source_text_ko=translated_chunk_ko,
                        max_chars=b_summary_max_chars,
                        metadata={
                            "source_translation_asset_id": translate_asset_id,
                            "source_translation_prompt_version": prompts.translate_asset.version,
                        },
                    )
                    if summary_ko_cached:
                        asset_cache_hits["KO_SUMMARY"] += 1
                    else:
                        asset_created["KO_SUMMARY"] += 1
                    generation_asset_ids.append(summary_ko_asset_id)
                elif generation_strategy in {"C", "D"}:
                    summary_ko_asset_id, summary_ko, summary_ko_cached = _resolve_or_create_summary_ko(
                        connection,
                        chunk=chunk,
                        source_fingerprint=source_fingerprint,
                        prompt_asset=prompts.summary_ko_asset,
                        prompt_text=prompts.summary_ko_text,
                        prompt_version_suffix=generation_strategy,
                        source_text_ko=en_summary,
                        client=summary_client,
                    )
                    if summary_ko_cached:
                        asset_cache_hits["KO_SUMMARY"] += 1
                    else:
                        asset_created["KO_SUMMARY"] += 1
                    generation_asset_ids.append(summary_ko_asset_id)
                elif generation_strategy in {"F", "G"} and fg_summary_mode == "extractive":
                    summary_ko_asset_id, summary_ko, summary_ko_cached = _resolve_or_create_extractive_summary_ko(
                        connection,
                        chunk=chunk,
                        source_fingerprint=source_fingerprint,
                        prompt_asset=prompts.summary_ko_asset,
                        prompt_version_suffix=generation_strategy,
                        source_text_ko=original_chunk_ko,
                        max_chars=fg_summary_max_chars,
                    )
                    if summary_ko_cached:
                        asset_cache_hits["KO_SUMMARY"] += 1
                    else:
                        asset_created["KO_SUMMARY"] += 1
                    generation_asset_ids.append(summary_ko_asset_id)
                elif generation_strategy in {"F", "G"}:
                    summary_ko_asset_id, summary_ko, summary_ko_cached = _resolve_or_create_summary_ko(
                        connection,
                        chunk=chunk,
                        source_fingerprint=source_fingerprint,
                        prompt_asset=prompts.summary_ko_asset,
                        prompt_text=prompts.summary_ko_text,
                        prompt_version_suffix=generation_strategy,
                        source_text_ko=original_chunk_ko,
                        client=summary_client_for_ko_long,
                    )
                    if summary_ko_cached:
                        asset_cache_hits["KO_SUMMARY"] += 1
                    else:
                        asset_created["KO_SUMMARY"] += 1
                    generation_asset_ids.append(summary_ko_asset_id)

                if _find_cached_query(
                    connection,
                    table_name=raw_table_name,
                    synthetic_query_id=stable_query_id,
                    source_fingerprint=source_fingerprint,
                    prompt_template_version=query_prompt_asset.version,
                ):
                    _attach_cached_query(
                        connection,
                        table_name=raw_table_name,
                        synthetic_query_id=stable_query_id,
                        generation_method_id=generation_method_id,
                        generation_batch_id=generation_batch_id,
                        llm_provider=query_client.config.provider,
                        llm_model=query_client.config.model,
                        generation_asset_ids=generation_asset_ids,
                    )
                    _insert_source_links_for_targets(
                        connection,
                        synthetic_query_id=stable_query_id,
                        primary_chunk=chunk,
                        target_chunk_ids=target_chunk_ids,
                        chunks_by_id=chunks_by_id,
                    )
                    generated_count = _refresh_generation_count(
                        connection,
                        generation_batch_id,
                        generated_count,
                    )
                    reused_count += 1
                    continue

                related_chunks_ko = (
                    _related_chunks_ko_payload(
                        primary_chunk_id=chunk.chunk_id,
                        target_chunk_ids=target_chunk_ids,
                        chunks_by_id=chunks_by_id,
                    )
                    if generation_strategy in {"F", "G"}
                    else []
                )
                query_payload = _build_query_payload(
                    chunk=chunk,
                    generation_strategy=generation_strategy,
                    original_chunk_ko=original_chunk_ko,
                    related_chunks_ko=related_chunks_ko,
                    extractive_summary_en=en_summary,
                    translated_chunk_ko=translated_chunk_ko,
                    extractive_summary_ko=summary_ko,
                    glossary_terms_keep_english=chunk_glossary_terms,
                    query_type=query_type,
                    answerability_type=answerability_type,
                    target_chunk_ids=target_chunk_ids,
                    b_payload_limits=b_payload_limits if generation_strategy == "B" else None,
                )
                query_response_schema = _query_response_schema_for_strategy(generation_strategy)
                query_response = _llm_query_json(
                    query_client,
                    prompt_text=query_prompt_text,
                    payload=query_payload,
                    response_schema=query_response_schema,
                    request_purpose="generate_query",
                    trace_id=f"query:{stable_query_id}",
                )
                query_text, extra_trace = _extract_query_text(
                    generation_strategy=generation_strategy,
                    query_type=query_type,
                    response=query_response,
                )
                if not query_text:
                    query_response = _llm_query_json(
                        query_client,
                        prompt_text=query_prompt_text,
                        payload={**query_payload, "retry_hint": "query_text_must_not_be_empty"},
                        response_schema=query_response_schema,
                        request_purpose="generate_query_retry",
                        trace_id=f"query:{stable_query_id}",
                    )
                    query_text, extra_trace = _extract_query_text(
                        generation_strategy=generation_strategy,
                        query_type=query_type,
                        response=query_response,
                    )
                if not query_text:
                    skipped_empty_count += 1
                    LOGGER.warning(
                        "Skip empty query output. chunk=%s strategy=%s query_type=%s",
                        chunk.chunk_id,
                        generation_strategy,
                        query_type,
                    )
                    continue

                normalized_query_text = _normalize_query_text(query_text)
                language_profile = _language_profile(generation_strategy, query_type)
                query_language = "en" if generation_strategy in {"E", "F"} else "ko"
                payload = {
                    "synthetic_query_id": stable_query_id,
                    "experiment_run_id": run_context.experiment_run_id,
                    "generation_method_id": generation_method_id,
                    "generation_batch_id": generation_batch_id,
                    "chunk_id_source": chunk.chunk_id,
                    "source_chunk_group_id": None,
                    "target_doc_id": chunk.document_id,
                    "target_chunk_ids": Jsonb(target_chunk_ids),
                    "answerability_type": answerability_type,
                    "query_text": query_text,
                    "normalized_query_text": normalized_query_text,
                    "query_language": query_language,
                    "language_profile": language_profile,
                    "query_type": query_type,
                    "generation_strategy": generation_strategy,
                    "prompt_asset_id": query_prompt_asset.prompt_asset_id,
                    "prompt_template_version": query_prompt_asset.version,
                    "prompt_version": query_prompt_asset.version,
                    "prompt_hash": query_prompt_asset.content_hash,
                    "source_summary": summary_ko if summary_ko else en_summary,
                    "source_fingerprint": source_fingerprint,
                    "source_chunk_ids": Jsonb(target_chunk_ids),
                    "glossary_terms": Jsonb(chunk_glossary_terms),
                    "llm_provider": query_client.config.provider,
                    "llm_model": query_client.config.model,
                    "generation_asset_ids": Jsonb(generation_asset_ids),
                    "llm_output": Jsonb(
                        {
                            "schema_version": "v1",
                            "response": query_response,
                            "query_type": query_type,
                            "answerability_type": answerability_type,
                            "trace": {
                                "en_summary": en_summary,
                                "ko_summary": summary_ko,
                                "b_summary_mode": "extractive" if generation_strategy == "B" else None,
                                "b_query_payload_limits": (
                                    _b_query_payload_limits_dict(b_payload_limits)
                                    if generation_strategy == "B"
                                    else None
                                ),
                                "b_query_payload_chars": (
                                    {
                                        "original_chunk_en": len(query_payload.get("original_chunk_en") or ""),
                                        "original_chunk_ko": len(query_payload.get("original_chunk_ko") or ""),
                                        "extractive_summary_en": len(query_payload.get("extractive_summary_en") or ""),
                                        "translated_chunk_ko": len(query_payload.get("translated_chunk_ko") or ""),
                                        "extractive_summary_ko": len(query_payload.get("extractive_summary_ko") or ""),
                                        "translated_chunk_ko_asset": len(translated_chunk_ko),
                                        "extractive_summary_ko_asset": len(summary_ko),
                                    }
                                    if generation_strategy == "B"
                                    else None
                                ),
                                "fg_summary_mode": fg_summary_mode if generation_strategy in {"F", "G"} else None,
                                "related_chunks_ko_count": len(related_chunks_ko),
                                "translated_chunk_excerpt": translated_chunk_ko[:320],
                                **extra_trace,
                            },
                        }
                    ),
                    "metadata": Jsonb(
                        _with_canonical_anchor_metadata(
                            {
                                "query_type_label": QUERY_TYPE_LABELS_KO.get(query_type, query_type),
                                "title": chunk.title,
                                "product_name": chunk.product_name,
                                "version_label": chunk.version_label,
                                "generation_batch_id": generation_batch_id,
                                "source_fingerprint": source_fingerprint,
                            },
                            connection=canonical_mapping_connection,
                            synthetic_query_id=stable_query_id,
                            query_language=query_language,
                            language_profile=language_profile,
                            generation_strategy=generation_strategy,
                            glossary_terms=chunk_glossary_terms,
                            glossary_term_candidates=chunk_glossary_term_candidates,
                        )
                    ),
                }
                _insert_query_row(connection, table_name=raw_table_name, payload=payload)
                _insert_source_links_for_targets(
                    connection,
                    synthetic_query_id=stable_query_id,
                    primary_chunk=chunk,
                    target_chunk_ids=target_chunk_ids,
                    chunks_by_id=chunks_by_id,
                )
                generated_count += 1
                if generation_batch_id:
                    generated_count = _count_queries_for_generation_batch(connection, generation_batch_id)
                new_generated_count += 1
                query_type_counter[query_type] += 1
                answerability_counter[answerability_type] += 1
                if len(generated_ids) < 20:
                    generated_ids.append(stable_query_id)

            if (chunk_index + 1) % llm_batch_size == 0:
                connection.commit()

        summary = {
            "experiment_key": config.experiment_key,
            "experiment_run_id": run_context.experiment_run_id,
            "generation_strategy": strategy,
            "source_id": source_id,
            "source_ids": source_ids,
            "source_document_id": source_document_id,
            "max_total_queries": max_total_queries,
            "random_chunk_sampling": random_chunk_sampling,
            "relation_source_chunks_loaded": len(relations),
            "glossary_documents_loaded": len(glossary_by_doc),
            "llm_execution_mode": llm_execution_mode,
            "b_summary_mode": "extractive" if strategy == "B" else None,
            "b_summary_max_chars": b_summary_max_chars if strategy == "B" else None,
            "b_query_payload_limits": _b_query_payload_limits_dict(b_payload_limits) if strategy == "B" else None,
            "fg_summary_mode": fg_summary_mode,
            "fg_summary_max_chars": fg_summary_max_chars,
            "llm": {
                "summary": {
                    "provider": summary_client.config.provider,
                    "model": summary_client.config.model,
                },
                "query": {
                    "provider": query_client.config.provider,
                    "model": query_client.config.model,
                },
                "translation": {
                    "provider": translate_client.config.provider,
                    "model": translate_client.config.model,
                },
            },
            "prompt_assets": {
                "summary_en": {
                    "id": prompts.summary_en_asset.prompt_name,
                    "version": prompts.summary_en_asset.version,
                    "hash": prompts.summary_en_asset.content_hash,
                    "asset_id": prompts.summary_en_asset.prompt_asset_id,
                },
                "summary_ko": {
                    "id": prompts.summary_ko_asset.prompt_name,
                    "version": prompts.summary_ko_asset.version,
                    "hash": prompts.summary_ko_asset.content_hash,
                    "asset_id": prompts.summary_ko_asset.prompt_asset_id,
                },
                "translate": {
                    "id": prompts.translate_asset.prompt_name,
                    "version": prompts.translate_asset.version,
                    "hash": prompts.translate_asset.content_hash,
                    "asset_id": prompts.translate_asset.prompt_asset_id,
                },
            },
            "chunks_processed": len(chunks),
            "initial_generated_queries": initial_generated_count,
            "new_generated_queries": new_generated_count,
            "generated_queries": generated_count,
            "reused_queries": reused_count,
            "skipped_empty_queries": skipped_empty_count,
            "query_type_distribution": dict(query_type_counter),
            "answerability_distribution": dict(answerability_counter),
            "asset_created": dict(asset_created),
            "asset_cache_hits": dict(asset_cache_hits),
            "preview_query_ids": generated_ids,
        }
        recorder.finish_run(run_context, status="completed", metrics=summary)
        connection.commit()
        return summary
    except Exception as exception:  # noqa: BLE001
        connection.rollback()
        LOGGER.exception("Synthetic query generation failed.")
        raise exception
    finally:
        connection.close()


def run_generation_from_env(experiment: str) -> dict[str, Any]:
    defaults = default_database_args()
    return run_generation(
        experiment=experiment,
        database_url=defaults["database_url"],
        db_host=defaults["db_host"],
        db_port=defaults["db_port"],
        db_name=defaults["db_name"],
        db_user=defaults["db_user"],
        db_password=defaults["db_password"],
    )
