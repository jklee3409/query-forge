from __future__ import annotations

import hashlib
import json
import math
import os
import re
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import psycopg

try:
    from common.anchor_quality import (
        extract_technical_tokens,
        has_technical_marker,
        is_valid_anchor_phrase,
        normalize_anchor_text,
    )
    from common.cohere_reranker import CohereReranker, load_cohere_rerank_config
    from common.embeddings import embed_text
    from common.experiment_config import resolve_rewrite_adoption_policy
    from common.local_retriever import RetrieverConfig, build_retriever_config, get_local_text_retriever, local_retriever_name
    from common.llm_client import LlmClient, load_stage_config
except ModuleNotFoundError:  # pragma: no cover
    from pipeline.common.anchor_quality import (
        extract_technical_tokens,
        has_technical_marker,
        is_valid_anchor_phrase,
        normalize_anchor_text,
    )
    from pipeline.common.cohere_reranker import CohereReranker, load_cohere_rerank_config
    from pipeline.common.embeddings import embed_text
    from pipeline.common.experiment_config import resolve_rewrite_adoption_policy
    from pipeline.common.local_retriever import RetrieverConfig, build_retriever_config, get_local_text_retriever, local_retriever_name
    from pipeline.common.llm_client import LlmClient, load_stage_config


@dataclass(slots=True)
class EvalSample:
    sample_id: str
    split: str
    query_text: str
    query_language: str
    dialog_context: dict[str, Any]
    expected_doc_ids: list[str]
    expected_chunk_ids: list[str]
    expected_answer_key_points: list[str]
    query_category: str
    single_or_multi_chunk: str | None
    source_product: str | None = None
    source_version_if_available: str | None = None


@dataclass(slots=True)
class ChunkItem:
    chunk_id: str
    document_id: str
    text: str
    embedding: list[float]


@dataclass(slots=True)
class MemoryItem:
    memory_id: str
    query_text: str
    target_doc_id: str
    target_chunk_ids: list[str]
    gating_preset: str
    generation_strategy: str
    source_gate_run_id: str | None
    embedding: list[float]
    product: str | None = None
    glossary_terms: list[str] = field(default_factory=list)


@dataclass(slots=True)
class RetrievalCandidate:
    chunk_id: str
    document_id: str
    score: float
    text: str


@dataclass(slots=True)
class RewriteOutcome:
    final_query: str
    rewrite_applied: bool
    rewrite_reason: str
    raw_confidence: float
    best_candidate_confidence: float
    memory_top_n: list[dict[str, Any]]
    candidates: list[dict[str, Any]]
    rewrite_llm_attempted: bool = False
    rewrite_llm_succeeded: bool = False
    rewrite_heuristic_fallback_used: bool = False
    final_rewrite_latency_ms: float | None = None
    pure_rewrite_latency_ms: float | None = None


_REWRITE_CLIENT: LlmClient | None = None
_REWRITE_PROMPT_TEXT: str | None = None
_RERANKER: CohereReranker | None = None
_RUNTIME_RETRIEVER_CACHE_LOCK = threading.Lock()
_RUNTIME_CACHE_MAX_ENTRIES = 32
_RUNTIME_CHUNK_RETRIEVER_CACHE: dict[
    tuple[int, str],
    tuple[list[ChunkItem], Any],
] = {}
_RUNTIME_MEMORY_RETRIEVER_CACHE: dict[
    tuple[int, str, str, str, str],
    tuple[list[MemoryItem], list[MemoryItem], Any],
] = {}
REWRITE_RETRIEVAL_STRATEGIES = {"replace", "interleave", "max_score"}
REWRITE_FAILURE_POLICIES = {"fail_run", "skip_to_raw", "heuristic_fallback"}
DEFAULT_REWRITE_TERMINOLOGY_HINTS_MAX = 12
TROUBLESHOOTING_HINT_TOKENS = (
    "error",
    "exception",
    "fail",
    "failure",
    "trace",
    "debug",
    "오류",
    "에러",
    "실패",
)

CONTENT_TOKEN_RE = re.compile(r"[@A-Za-z0-9_./:$-]+|[\uac00-\ud7a3]{2,}")
GENERIC_CONTENT_TOKENS = {
    "spring",
    "boot",
    "security",
    "framework",
    "data",
    "java",
    "kotlin",
    "practical",
    "practice",
    "usage",
    "use",
    "overview",
    "summary",
    "point",
    "points",
    "difference",
    "differences",
    "guide",
    "latest",
    "stable",
    "version",
    "\uc2e4\ud589",
    "\uc0ac\uc6a9",
    "\uc0ac\uc6a9\ubc95",
    "\uc694\uc57d",
    "\ud3ec\uc778\ud2b8",
    "\ucc28\uc774",
    "\ubc29\ubc95",
    "\uc608\uc2dc",
    "\uc2e4\ubb34",
    "\ubcf4\ud1b5",
    "\uad00\ub828",
    "\uac00\uc774\ub4dc",
    "\uc815\ub9ac",
    "\ud575\uc2ec",
    "\ube60\ub974\uac8c",
    "\uac19\uc774",
}

REWRITE_RESPONSE_SCHEMA: dict[str, Any] = {
    "type": "object",
    "required": ["candidates"],
    "properties": {
        "candidates": {
            "type": "array",
            "minItems": 1,
            "items": {
                "type": "object",
                "required": ["label", "query"],
                "properties": {
                    "label": {"type": "string"},
                    "query": {"type": "string"},
                },
                "additionalProperties": True,
            },
        },
    },
    "additionalProperties": True,
}

PRODUCT_SCOPE_SUFFIXES = (
    "-reference",
    "_reference",
    "-docs",
    "_docs",
    "-doc",
    "_doc",
)

def _normalize_anchor_token(token: str) -> str:
    return normalize_anchor_text(token)


def _looks_technical_anchor(token: str) -> bool:
    return has_technical_marker(token)


def _extract_anchor_tokens(text: str, *, language_hint: str, max_items: int) -> list[str]:
    return extract_technical_tokens(
        text,
        language_hint=language_hint,
        max_items=max_items,
    )


def _clamp_count(value: Any, *, default: int, max_value: int) -> int:
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        parsed = default
    return max(1, min(parsed, max_value))


def _build_rewrite_anchor_candidates(
    *,
    raw_query: str,
    query_language: str,
    memory_items: list[dict[str, Any]],
    max_anchors: int = 8,
) -> dict[str, Any]:
    anchors: list[dict[str, Any]] = []
    seen: set[str] = set()

    def add_anchor(anchor: str, source: str, memory_row: dict[str, Any] | None = None) -> None:
        normalized = _normalize_anchor_token(anchor)
        if not normalized:
            return
        if not is_valid_anchor_phrase(normalized, language_hint=query_language):
            return
        key = normalized.lower()
        if key in seen:
            return
        seen.add(key)
        payload: dict[str, Any] = {"anchor": normalized, "source": source}
        if memory_row:
            payload["memory_id"] = memory_row.get("memory_id")
            payload["generation_strategy"] = memory_row.get("generation_strategy")
            payload["product"] = memory_row.get("product")
        anchors.append(payload)

    for token in _extract_anchor_tokens(raw_query, language_hint=query_language, max_items=4):
        add_anchor(token, "raw_query")
        if len(anchors) >= max_anchors:
            break

    for memory_row in memory_items[:5]:
        glossary_terms = memory_row.get("glossary_terms")
        if isinstance(glossary_terms, list):
            for term in glossary_terms:
                if not isinstance(term, str):
                    continue
                if not _looks_technical_anchor(term):
                    continue
                add_anchor(term, "memory_glossary", memory_row)
                if len(anchors) >= max_anchors:
                    break
        if len(anchors) >= max_anchors:
            break
        for token in _extract_anchor_tokens(
            str(memory_row.get("query_text") or ""),
            language_hint=query_language,
            max_items=3,
        ):
            add_anchor(token, "memory_query", memory_row)
            if len(anchors) >= max_anchors:
                break
        if len(anchors) >= max_anchors:
            break

    return {
        "query_language": query_language,
        "anchors": anchors,
        "anchor_terms": [item["anchor"] for item in anchors],
    }


def _build_rewrite_terminology_hints(
    *,
    raw_query: str,
    query_language: str,
    memory_items: list[dict[str, Any]],
    max_terms: int = DEFAULT_REWRITE_TERMINOLOGY_HINTS_MAX,
) -> dict[str, Any]:
    bounded_max_terms = _clamp_count(
        max_terms,
        default=DEFAULT_REWRITE_TERMINOLOGY_HINTS_MAX,
        max_value=24,
    )
    terms: list[str] = []
    source_terms: dict[str, list[str]] = {
        "raw_query": [],
        "memory_glossary": [],
        "memory_query": [],
    }
    seen: set[str] = set()

    def add_term(term: str, source: str) -> bool:
        if len(terms) >= bounded_max_terms:
            return False
        normalized = _normalize_anchor_token(term)
        if not normalized:
            return True
        if not _looks_technical_anchor(normalized):
            return True
        if not is_valid_anchor_phrase(normalized, language_hint=query_language):
            return True
        dedup_key = normalized.casefold()
        if dedup_key in seen:
            return True
        seen.add(dedup_key)
        terms.append(normalized)
        source_terms[source].append(normalized)
        return True

    for token in _extract_anchor_tokens(
        raw_query,
        language_hint=query_language,
        max_items=min(6, bounded_max_terms),
    ):
        if not add_term(token, "raw_query"):
            break

    for memory_row in memory_items[:5]:
        glossary_terms = memory_row.get("glossary_terms")
        if isinstance(glossary_terms, list):
            for term in glossary_terms:
                if not isinstance(term, str):
                    continue
                if not add_term(term, "memory_glossary"):
                    break
        if len(terms) >= bounded_max_terms:
            break

        for token in _extract_anchor_tokens(
            str(memory_row.get("query_text") or ""),
            language_hint=query_language,
            max_items=4,
        ):
            if not add_term(token, "memory_query"):
                break
        if len(terms) >= bounded_max_terms:
            break

    compact_source_terms = {key: value for key, value in source_terms.items() if value}
    return {
        "terms": terms,
        "source_terms": compact_source_terms,
    }


def build_memory_guided_query(
    raw_query: str,
    memory_items: list[dict[str, Any]],
    *,
    query_language: str = "ko",
    max_hint_tokens: int = 2,
) -> str:
    normalized_raw_query = str(raw_query or "").strip()
    if not normalized_raw_query:
        return normalized_raw_query
    if not memory_items:
        return normalized_raw_query

    bounded_hint_count = _clamp_count(max_hint_tokens, default=2, max_value=6)
    raw_query_folded = normalized_raw_query.casefold()
    raw_anchor_set = {
        token.casefold()
        for token in _extract_anchor_tokens(
            normalized_raw_query,
            language_hint=query_language,
            max_items=16,
        )
        if token.strip()
    }
    product_scores: dict[str, float] = {}
    hint_scores: dict[str, float] = {}

    def _product_hint_phrase(product_value: str) -> str:
        normalized = product_value.strip().replace("_", "-")
        if not normalized:
            return ""
        tokens = [item for item in normalized.split("-") if item]
        if not tokens:
            return ""
        if len(tokens) > 4:
            tokens = tokens[:4]
        return " ".join(token.capitalize() for token in tokens)

    def _accept_hint(token: str) -> str | None:
        normalized = _normalize_anchor_token(token)
        if not normalized:
            return None
        if not _looks_technical_anchor(normalized):
            return None
        if not is_valid_anchor_phrase(normalized, language_hint=query_language):
            return None
        folded = normalized.casefold()
        if folded in raw_anchor_set:
            return None
        if len(normalized) > 32:
            return None
        if any(marker in normalized for marker in ("{", "}", "(", ")", ";", "=", ".", "$", "/")):
            return None
        return normalized

    for index, memory in enumerate(memory_items[:5]):
        rank_weight = 1.0 / float(index + 1)
        product_phrase = _product_hint_phrase(str(memory.get("product") or ""))
        if product_phrase and product_phrase.casefold() not in raw_query_folded:
            product_scores[product_phrase] = product_scores.get(product_phrase, 0.0) + (2.0 * rank_weight)

        query_text = str(memory.get("query_text") or "")
        for token in _extract_anchor_tokens(
            query_text,
            language_hint=query_language,
            max_items=5,
        ):
            accepted = _accept_hint(token)
            if not accepted:
                continue
            hint_scores[accepted] = hint_scores.get(accepted, 0.0) + rank_weight

    if not hint_scores:
        if product_scores:
            ranked_products = sorted(
                product_scores.items(),
                key=lambda item: (-item[1], item[0].casefold()),
            )
            selected_products = [token for token, _score in ranked_products[:bounded_hint_count]]
            if selected_products:
                return f"{normalized_raw_query} {' '.join(selected_products)}".strip()
        return normalized_raw_query

    if product_scores:
        ranked_products = sorted(
            product_scores.items(),
            key=lambda item: (-item[1], item[0].casefold()),
        )
        product_hints = [token for token, _score in ranked_products[:bounded_hint_count]]
        if product_hints:
            return f"{normalized_raw_query} {' '.join(product_hints)}".strip()

    ranked_hints = sorted(
        hint_scores.items(),
        key=lambda item: (-item[1], len(item[0]), item[0].casefold()),
    )
    selected_hints = [token for token, _score in ranked_hints[:bounded_hint_count]]
    if not selected_hints:
        return normalized_raw_query

    return f"{normalized_raw_query} {' '.join(selected_hints)}".strip()


def load_eval_samples(
    connection: psycopg.Connection[Any],
    *,
    dataset_id: str | None = None,
    query_language: str = "ko",
) -> list[EvalSample]:
    normalized_language = "en" if str(query_language).strip().lower() == "en" else "ko"
    with connection.cursor() as cursor:
        if dataset_id:
            cursor.execute(
                """
                SELECT es.sample_id,
                       es.split,
                       es.user_query_ko,
                       es.user_query_en,
                       COALESCE(NULLIF(es.query_language, ''), COALESCE(ed.metadata ->> 'query_language', 'ko')) AS query_language,
                       es.dialog_context,
                       es.expected_doc_ids,
                       es.expected_chunk_ids,
                       es.expected_answer_key_points,
                       es.query_category,
                       es.single_or_multi_chunk,
                       es.source_product,
                       es.source_version_if_available
                FROM eval_samples es
                JOIN eval_dataset_item edi
                  ON edi.sample_id = es.sample_id
                 AND edi.dataset_id = %s
                 AND edi.active = TRUE
                JOIN eval_dataset ed
                  ON ed.dataset_id = edi.dataset_id
                ORDER BY es.split, es.sample_id
                """,
                (dataset_id,),
            )
        else:
            cursor.execute(
                """
                SELECT sample_id,
                       split,
                       user_query_ko,
                       user_query_en,
                       COALESCE(NULLIF(query_language, ''), 'ko') AS query_language,
                       dialog_context,
                       expected_doc_ids,
                       expected_chunk_ids,
                       expected_answer_key_points,
                       query_category,
                       single_or_multi_chunk,
                       source_product,
                       source_version_if_available
                FROM eval_samples
                WHERE split IN ('dev', 'test')
                ORDER BY split, sample_id
                """
            )
        rows = cursor.fetchall()
    return [
        EvalSample(
            sample_id=str(row["sample_id"]),
            split=str(row["split"]),
            query_text=str(
                (row["user_query_en"] if normalized_language == "en" and row["user_query_en"] else row["user_query_ko"])
                or row["user_query_en"]
                or row["user_query_ko"]
                or ""
            ),
            query_language=normalized_language,
            dialog_context=dict(row["dialog_context"] or {}),
            expected_doc_ids=list(row["expected_doc_ids"] or []),
            expected_chunk_ids=list(row["expected_chunk_ids"] or []),
            expected_answer_key_points=list(row["expected_answer_key_points"] or []),
            query_category=str(row["query_category"]),
            single_or_multi_chunk=row["single_or_multi_chunk"],
            source_product=str(row["source_product"]).strip() if row["source_product"] else None,
            source_version_if_available=(
                str(row["source_version_if_available"]).strip()
                if row["source_version_if_available"]
                else None
            ),
        )
        for row in rows
    ]


def _normalize_product_scope_key(value: str) -> str:
    return value.strip().lower().replace("_", "-")


def _expand_source_product_aliases(source_product: str) -> set[str]:
    normalized = _normalize_product_scope_key(source_product)
    if not normalized:
        return set()
    aliases = {normalized}
    for suffix in PRODUCT_SCOPE_SUFFIXES:
        if normalized.endswith(suffix) and len(normalized) > len(suffix):
            aliases.add(normalized[: -len(suffix)])
    return {item for item in aliases if item}


def derive_eval_corpus_scope(samples: list[EvalSample]) -> dict[str, set[str]]:
    raw_source_products: set[str] = set()
    product_filters: set[str] = set()
    expected_doc_ids: set[str] = set()

    for sample in samples:
        for doc_id in sample.expected_doc_ids:
            normalized_doc_id = str(doc_id).strip()
            if normalized_doc_id:
                expected_doc_ids.add(normalized_doc_id)
        source_product = str(sample.source_product or "").strip()
        if not source_product:
            continue
        raw_source_products.add(source_product)
        product_filters.update(_expand_source_product_aliases(source_product))

    return {
        "source_products": raw_source_products,
        "product_filters": product_filters,
        "expected_doc_ids": expected_doc_ids,
    }


def load_chunk_items(
    connection: psycopg.Connection[Any],
    *,
    allowed_products: set[str] | None = None,
    include_document_ids: set[str] | None = None,
) -> list[ChunkItem]:
    normalized_products = sorted(
        {
            _normalize_product_scope_key(item)
            for item in (allowed_products or set())
            if str(item).strip()
        }
    )
    normalized_doc_ids = sorted(
        {
            str(item).strip()
            for item in (include_document_ids or set())
            if str(item).strip()
        }
    )
    where_clauses: list[str] = []
    parameters: list[Any] = []
    if normalized_products and normalized_doc_ids:
        where_clauses.append(
            "(LOWER(COALESCE(d.product_name, '')) = ANY(%s) OR c.document_id = ANY(%s))"
        )
        parameters.extend([normalized_products, normalized_doc_ids])
    elif normalized_products:
        where_clauses.append("LOWER(COALESCE(d.product_name, '')) = ANY(%s)")
        parameters.append(normalized_products)
    elif normalized_doc_ids:
        where_clauses.append("c.document_id = ANY(%s)")
        parameters.append(normalized_doc_ids)
    where_sql = f"WHERE {' AND '.join(where_clauses)}" if where_clauses else ""
    with connection.cursor() as cursor:
        cursor.execute(
            f"""
            SELECT c.chunk_id, c.document_id, c.chunk_text
            FROM corpus_chunks c
            JOIN corpus_documents d ON d.document_id = c.document_id
            {where_sql}
            ORDER BY c.document_id, c.chunk_index_in_document
            """,
            parameters,
        )
        rows = cursor.fetchall()
    return [
        ChunkItem(
            chunk_id=str(row["chunk_id"]),
            document_id=str(row["document_id"]),
            text=str(row["chunk_text"]),
            embedding=embed_text(str(row["chunk_text"])),
        )
        for row in rows
    ]


def load_memory_items(
    connection: psycopg.Connection[Any],
    *,
    memory_experiment_key: str | None = None,
) -> list[MemoryItem]:
    where_clause = ""
    parameters: list[Any] = []
    if memory_experiment_key:
        where_clause = "WHERE m.metadata ->> 'memory_experiment_key' = %s"
        parameters.append(memory_experiment_key)
    with connection.cursor() as cursor:
        cursor.execute(
            f"""
            SELECT m.memory_id,
                   m.query_text,
                   m.target_doc_id,
                   m.target_chunk_ids,
                   COALESCE(NULLIF(m.metadata ->> 'gating_preset', ''), g.gating_preset, 'full_gating') AS gating_preset,
                   m.generation_strategy,
                   m.metadata ->> 'source_gate_run_id' AS source_gate_run_id,
                   m.product,
                   m.glossary_terms
            FROM memory_entries m
            LEFT JOIN synthetic_queries_gated g ON g.gated_query_id = m.source_gated_query_id
            {where_clause}
            ORDER BY m.created_at DESC
            """,
            parameters,
        )
        rows = cursor.fetchall()
    return [
        MemoryItem(
            memory_id=str(row["memory_id"]),
            query_text=str(row["query_text"]),
            target_doc_id=str(row["target_doc_id"]),
            target_chunk_ids=list(row["target_chunk_ids"] or []),
            gating_preset=str(row["gating_preset"]),
            generation_strategy=str(row["generation_strategy"]),
            source_gate_run_id=str(row["source_gate_run_id"]) if row["source_gate_run_id"] else None,
            embedding=embed_text(str(row["query_text"])),
            product=str(row["product"]) if row["product"] else None,
            glossary_terms=[str(item) for item in (row["glossary_terms"] or []) if str(item).strip()],
        )
        for row in rows
    ]


def _trim_runtime_cache_if_needed(cache: dict[Any, Any]) -> None:
    if len(cache) >= _RUNTIME_CACHE_MAX_ENTRIES:
        cache.clear()


def _chunk_retriever_cache_key(
    *,
    chunks: list[ChunkItem],
    config: RetrieverConfig,
) -> tuple[int, str]:
    return (id(chunks), config.cache_signature())


def _memory_retriever_cache_key(
    *,
    memories: list[MemoryItem],
    preset_filter: str | None,
    source_gate_run_id: str | None,
    strategy_set: set[str],
    config: RetrieverConfig,
) -> tuple[int, str, str, str, str]:
    strategy_signature = ",".join(sorted(strategy_set))
    return (
        id(memories),
        str(preset_filter or ""),
        str(source_gate_run_id or ""),
        strategy_signature,
        config.cache_signature(),
    )


def _select_eligible_memories(
    *,
    memories: list[MemoryItem],
    preset_filter: str | None,
    source_gate_run_id: str | None,
    strategy_set: set[str],
) -> list[MemoryItem]:
    eligible: list[MemoryItem] = []
    for memory in memories:
        if preset_filter and memory.gating_preset != preset_filter:
            continue
        if source_gate_run_id and memory.source_gate_run_id != source_gate_run_id:
            continue
        if strategy_set and memory.generation_strategy.upper() not in strategy_set:
            continue
        eligible.append(memory)
    return eligible


def _build_chunk_retriever(
    *,
    chunks: list[ChunkItem],
    config: RetrieverConfig,
) -> Any:
    return get_local_text_retriever(
        namespace="eval-chunks",
        item_ids=[chunk.chunk_id for chunk in chunks],
        texts=[chunk.text for chunk in chunks],
        fallback_embeddings=[chunk.embedding for chunk in chunks],
        retriever_config=config,
    )


def _get_chunk_retriever(
    *,
    chunks: list[ChunkItem],
    config: RetrieverConfig,
) -> Any:
    cache_key = _chunk_retriever_cache_key(chunks=chunks, config=config)
    with _RUNTIME_RETRIEVER_CACHE_LOCK:
        cached = _RUNTIME_CHUNK_RETRIEVER_CACHE.get(cache_key)
        if cached is not None and cached[0] is chunks:
            return cached[1]
    retriever = _build_chunk_retriever(chunks=chunks, config=config)
    with _RUNTIME_RETRIEVER_CACHE_LOCK:
        _trim_runtime_cache_if_needed(_RUNTIME_CHUNK_RETRIEVER_CACHE)
        _RUNTIME_CHUNK_RETRIEVER_CACHE[cache_key] = (chunks, retriever)
    return retriever


def _build_memory_retriever(
    *,
    eligible: list[MemoryItem],
    config: RetrieverConfig,
) -> Any:
    return get_local_text_retriever(
        namespace="eval-memory",
        item_ids=[memory.memory_id for memory in eligible],
        texts=[memory.query_text for memory in eligible],
        fallback_embeddings=[memory.embedding for memory in eligible],
        retriever_config=config,
    )


def _get_memory_eligible_and_retriever(
    *,
    memories: list[MemoryItem],
    preset_filter: str | None,
    source_gate_run_id: str | None,
    strategy_set: set[str],
    config: RetrieverConfig,
) -> tuple[list[MemoryItem], Any] | tuple[list[MemoryItem], None]:
    cache_key = _memory_retriever_cache_key(
        memories=memories,
        preset_filter=preset_filter,
        source_gate_run_id=source_gate_run_id,
        strategy_set=strategy_set,
        config=config,
    )
    with _RUNTIME_RETRIEVER_CACHE_LOCK:
        cached = _RUNTIME_MEMORY_RETRIEVER_CACHE.get(cache_key)
        if cached is not None and cached[0] is memories:
            return cached[1], cached[2]

    eligible = _select_eligible_memories(
        memories=memories,
        preset_filter=preset_filter,
        source_gate_run_id=source_gate_run_id,
        strategy_set=strategy_set,
    )
    if not eligible:
        return [], None
    retriever = _build_memory_retriever(eligible=eligible, config=config)
    with _RUNTIME_RETRIEVER_CACHE_LOCK:
        _trim_runtime_cache_if_needed(_RUNTIME_MEMORY_RETRIEVER_CACHE)
        _RUNTIME_MEMORY_RETRIEVER_CACHE[cache_key] = (memories, eligible, retriever)
    return eligible, retriever


def retrieve_top_k(
    query_text: str,
    chunks: list[ChunkItem],
    *,
    top_k: int,
    retriever_config: RetrieverConfig | None = None,
) -> list[RetrievalCandidate]:
    if not chunks:
        return []
    config = retriever_config or build_retriever_config({})
    candidate_pool_k = max(top_k, min(config.candidate_pool_k, max(top_k, len(chunks))))
    retriever = _get_chunk_retriever(chunks=chunks, config=config)
    ranked_pool = retriever.rank(query_text, top_k=candidate_pool_k)
    reduced = [
        RetrievalCandidate(
            chunk_id=chunks[ranked.index].chunk_id,
            document_id=chunks[ranked.index].document_id,
            score=ranked.score,
            text=chunks[ranked.index].text,
        )
        for ranked in ranked_pool
    ]
    if not reduced:
        return []

    if not config.rerank_enabled:
        return reduced[:top_k]

    reranker = _cohere_reranker()
    if not reranker.available:
        return reduced[:top_k]

    rerank_rows = reranker.rerank(
        query=query_text,
        documents=[item.text for item in reduced],
        top_n=top_k,
    )
    if not rerank_rows:
        return reduced[:top_k]

    reranked: list[RetrievalCandidate] = []
    for index, score in rerank_rows:
        if 0 <= index < len(reduced):
            row = reduced[index]
            reranked.append(
                RetrievalCandidate(
                    chunk_id=row.chunk_id,
                    document_id=row.document_id,
                    score=(float(score) * 2.0) - 1.0,
                    text=row.text,
                )
            )
    return reranked if reranked else reduced[:top_k]


def local_retriever_label(retriever_config: RetrieverConfig | None = None) -> str:
    return local_retriever_name(retriever_config or build_retriever_config({}))


def rerank_retrieval_candidates(
    query_text: str,
    candidates: list[RetrievalCandidate],
    *,
    top_n: int,
    retriever_config: RetrieverConfig | None = None,
) -> list[RetrievalCandidate]:
    if not candidates or top_n <= 0:
        return []
    limited_top_n = max(1, min(top_n, len(candidates)))
    config = retriever_config or build_retriever_config({})
    if not config.rerank_enabled:
        return candidates[:limited_top_n]
    reranker = _cohere_reranker()
    if not reranker.available:
        return candidates[:limited_top_n]
    rerank_rows = reranker.rerank(
        query=query_text,
        documents=[candidate.text for candidate in candidates],
        top_n=limited_top_n,
    )
    if not rerank_rows:
        return candidates[:limited_top_n]
    reranked: list[RetrievalCandidate] = []
    for index, score in rerank_rows:
        if 0 <= index < len(candidates):
            row = candidates[index]
            reranked.append(
                RetrievalCandidate(
                    chunk_id=row.chunk_id,
                    document_id=row.document_id,
                    score=(float(score) * 2.0) - 1.0,
                    text=row.text,
                )
            )
    return reranked if reranked else candidates[:limited_top_n]


def memory_top_n(
    query_text: str,
    memories: list[MemoryItem],
    *,
    top_n: int,
    preset_filter: str | None = None,
    source_gate_run_id: str | None = None,
    strategy_filters: list[str] | None = None,
    retriever_config: RetrieverConfig | None = None,
) -> list[dict[str, Any]]:
    strategy_set = {item.upper() for item in strategy_filters or [] if str(item).strip()}
    config = retriever_config or build_retriever_config({})
    eligible, retriever = _get_memory_eligible_and_retriever(
        memories=memories,
        preset_filter=preset_filter,
        source_gate_run_id=source_gate_run_id,
        strategy_set=strategy_set,
        config=config,
    )
    if not eligible or retriever is None:
        return []
    scored = []
    for ranked in retriever.rank(query_text, top_k=top_n):
        memory = eligible[ranked.index]
        scored.append(
            {
                "memory_id": memory.memory_id,
                "query_text": memory.query_text,
                "target_doc_id": memory.target_doc_id,
                "target_chunk_ids": memory.target_chunk_ids,
                "generation_strategy": memory.generation_strategy,
                "product": memory.product,
                "glossary_terms": memory.glossary_terms,
                "similarity": ranked.score,
                "dense_similarity": ranked.dense_score,
                "bm25_score": ranked.bm25_score,
                "technical_token_overlap": ranked.technical_score,
                "retriever": retriever.retriever_name,
            }
        )
    return scored


def _rewrite_prompt_text() -> str:
    global _REWRITE_PROMPT_TEXT
    if _REWRITE_PROMPT_TEXT is not None:
        return _REWRITE_PROMPT_TEXT
    root = Path(os.getenv("PROMPT_ROOT") or "configs/prompts")
    candidates = [
        root / "rewrite" / "selective_rewrite_v2.md",
        root / "rewrite" / "selective_rewrite_v1.md",
        Path("configs/prompts/rewrite/selective_rewrite_v2.md"),
        Path("configs/prompts/rewrite/selective_rewrite_v1.md"),
        Path("../configs/prompts/rewrite/selective_rewrite_v2.md"),
        Path("../configs/prompts/rewrite/selective_rewrite_v1.md"),
    ]
    for path in candidates:
        if path.exists():
            _REWRITE_PROMPT_TEXT = path.read_text(encoding="utf-8")
            return _REWRITE_PROMPT_TEXT
    raise FileNotFoundError("rewrite prompt file not found: selective_rewrite_v2.md or selective_rewrite_v1.md")


def _rewrite_client() -> LlmClient:
    global _REWRITE_CLIENT
    if _REWRITE_CLIENT is None:
        _REWRITE_CLIENT = LlmClient(load_stage_config(stage="rewrite", raw_config={}))
    return _REWRITE_CLIENT


def _cohere_reranker() -> CohereReranker:
    global _RERANKER
    if _RERANKER is None:
        _RERANKER = CohereReranker(load_cohere_rerank_config({}))
    return _RERANKER


def _heuristic_rewrite_candidates(
    raw_query: str,
    memory_items: list[dict[str, Any]],
    *,
    session_context: dict[str, Any],
    candidate_count: int,
) -> list[dict[str, str]]:
    top_memory_query = memory_items[0]["query_text"] if memory_items else raw_query
    previous_entity = str(session_context.get("previous_assistant_summary") or "").strip()
    previous_question = str(session_context.get("previous_user_question") or "").strip()

    templates = [
        {
            "label": "explicit_standalone",
            "query": f"{raw_query}를 스프링 기술 문서 기준으로 독립 질문 형태로 상세히 설명해 주세요.",
        },
        {
            "label": "memory_anchored",
            "query": f"{raw_query} 관련하여 {top_memory_query}",
        },
        {
            "label": "task_or_error_focused",
            "query": f"{raw_query}가 실패하거나 오류가 날 때 점검 순서를 알려주세요.",
        },
    ]
    if previous_entity or previous_question:
        templates[0]["query"] = f"{previous_question} 이후 맥락에서 {raw_query}를 독립 질문으로 재작성해 주세요. {previous_entity}".strip()
    return templates[:candidate_count]


def build_rewrite_candidates(
    raw_query: str,
    memory_items: list[dict[str, Any]],
    *,
    session_context: dict[str, Any],
    candidate_count: int,
    rewrite_anchor_injection_enabled: bool = True,
) -> list[dict[str, str]]:
    trace_id = f"rewrite:{hashlib.sha1(raw_query.encode('utf-8')).hexdigest()[:12]}"
    payload: dict[str, Any] = {
        "raw_query": raw_query,
        "session_context": session_context,
        "top_memory_candidates": memory_items[:5],
        "candidate_count": candidate_count,
    }
    if rewrite_anchor_injection_enabled:
        anchor_context = _build_rewrite_anchor_candidates(
            raw_query=raw_query,
            query_language="ko",
            memory_items=memory_items,
        )
        payload["anchor_candidates"] = anchor_context["anchors"]
        payload["anchor_terms"] = anchor_context["anchor_terms"]
    response = _rewrite_client().chat_json(
        system_prompt=_rewrite_prompt_text(),
        user_prompt=json.dumps(
            payload,
            ensure_ascii=False,
            indent=2,
        ),
        response_schema=REWRITE_RESPONSE_SCHEMA,
        request_purpose="selective_rewrite",
        trace_id=trace_id,
    )
    candidate_rows = response.get("candidates")
    if not isinstance(candidate_rows, list):
        fallback_allowed = str(os.getenv("QUERY_FORGE_ALLOW_HEURISTIC_REWRITE_FALLBACK") or "").lower() == "true"
        if fallback_allowed:
            return _heuristic_rewrite_candidates(
                raw_query,
                memory_items,
                session_context=session_context,
                candidate_count=candidate_count,
            )
        raise RuntimeError("LLM rewrite response must contain `candidates` list.")
    normalized: list[dict[str, str]] = []
    for item in candidate_rows:
        if not isinstance(item, dict):
            continue
        query = str(item.get("query") or "").strip()
        if not query:
            continue
        label = str(item.get("label") or f"candidate_{len(normalized) + 1}").strip()
        normalized.append({"label": label, "query": query})
        if len(normalized) >= candidate_count:
            break
    if normalized:
        return normalized
    fallback_allowed = str(os.getenv("QUERY_FORGE_ALLOW_HEURISTIC_REWRITE_FALLBACK") or "").lower() == "true"
    if fallback_allowed:
        return _heuristic_rewrite_candidates(
            raw_query,
            memory_items,
            session_context=session_context,
            candidate_count=candidate_count,
        )
    raise RuntimeError("LLM rewrite candidate response was empty.")


def _heuristic_rewrite_candidates_v2(
    raw_query: str,
    memory_items: list[dict[str, Any]],
    *,
    session_context: dict[str, Any],
    candidate_count: int,
    query_language: str,
) -> list[dict[str, str]]:
    top_memory_query = memory_items[0]["query_text"] if memory_items else raw_query
    previous_entity = str(session_context.get("previous_assistant_summary") or "").strip()
    previous_question = str(session_context.get("previous_user_question") or "").strip()

    if query_language == "en":
        templates = [
            {"label": "explicit_standalone", "query": raw_query},
            {"label": "memory_anchored", "query": f"{raw_query} {top_memory_query}".strip()},
            {"label": "task_or_error_focused", "query": f"{raw_query} troubleshooting example".strip()},
        ]
        if previous_entity or previous_question:
            templates[0]["query"] = f"{previous_question} {raw_query} {previous_entity}".strip()
        return templates[:candidate_count]

    return _heuristic_rewrite_candidates(
        raw_query,
        memory_items,
        session_context=session_context,
        candidate_count=candidate_count,
    )


def _normalize_rewrite_failure_policy(value: str | None) -> str:
    normalized = str(value or "").strip().lower().replace("-", "_")
    if not normalized:
        return "fail_run"
    if normalized not in REWRITE_FAILURE_POLICIES:
        return "fail_run"
    return normalized


def _bump_rewrite_runtime_stat(runtime_stats: dict[str, int] | None, key: str) -> None:
    if runtime_stats is None:
        return
    runtime_stats[key] = int(runtime_stats.get(key, 0)) + 1


def build_rewrite_candidates_v2(
    raw_query: str,
    memory_items: list[dict[str, Any]],
    *,
    session_context: dict[str, Any],
    candidate_count: int,
    query_language: str,
    rewrite_anchor_injection_enabled: bool = True,
    rewrite_terminology_hints_max_count: int = DEFAULT_REWRITE_TERMINOLOGY_HINTS_MAX,
    rewrite_failure_policy: str | None = None,
    rewrite_runtime_stats: dict[str, int] | None = None,
) -> list[dict[str, str]]:
    trace_id = f"rewrite:{hashlib.sha1(raw_query.encode('utf-8')).hexdigest()[:12]}"
    failure_policy = _normalize_rewrite_failure_policy(rewrite_failure_policy)
    fallback_allowed = failure_policy == "heuristic_fallback"
    _bump_rewrite_runtime_stat(rewrite_runtime_stats, "llm_attempted_count")

    def _handle_failure(error: Exception) -> list[dict[str, str]]:
        _bump_rewrite_runtime_stat(rewrite_runtime_stats, "llm_failure_count")
        if fallback_allowed:
            _bump_rewrite_runtime_stat(rewrite_runtime_stats, "heuristic_fallback_count")
            return _heuristic_rewrite_candidates_v2(
                raw_query,
                memory_items,
                session_context=session_context,
                candidate_count=candidate_count,
                query_language=query_language,
            )
        if failure_policy == "skip_to_raw":
            return []
        raise error

    payload: dict[str, Any] = {
        "raw_query": raw_query,
        "query_language": query_language,
        "session_context": session_context,
        "top_memory_candidates": memory_items[:5],
        "candidate_count": candidate_count,
    }
    if rewrite_anchor_injection_enabled:
        anchor_context = _build_rewrite_anchor_candidates(
            raw_query=raw_query,
            query_language=query_language,
            memory_items=memory_items,
        )
        payload["anchor_candidates"] = anchor_context["anchors"]
        payload["anchor_terms"] = anchor_context["anchor_terms"]
        payload["terminology_hints"] = _build_rewrite_terminology_hints(
            raw_query=raw_query,
            query_language=query_language,
            memory_items=memory_items,
            max_terms=rewrite_terminology_hints_max_count,
        )
    try:
        llm_started = time.perf_counter()
        response = _rewrite_client().chat_json(
            system_prompt=_rewrite_prompt_text(),
            user_prompt=json.dumps(
                payload,
                ensure_ascii=False,
                indent=2,
            ),
            response_schema=REWRITE_RESPONSE_SCHEMA,
            request_purpose="selective_rewrite",
            trace_id=trace_id,
        )
        if rewrite_runtime_stats is not None:
            rewrite_runtime_stats["pure_rewrite_latency_ms"] = (time.perf_counter() - llm_started) * 1000.0
    except Exception as exception:
        if rewrite_runtime_stats is not None:
            rewrite_runtime_stats["pure_rewrite_latency_ms"] = (time.perf_counter() - llm_started) * 1000.0
        return _handle_failure(exception)
    candidate_rows = response.get("candidates")
    if not isinstance(candidate_rows, list):
        return _handle_failure(RuntimeError("LLM rewrite response must contain `candidates` list."))
    normalized: list[dict[str, str]] = []
    for item in candidate_rows:
        if not isinstance(item, dict):
            continue
        query = str(item.get("query") or "").strip()
        if not query:
            continue
        label = str(item.get("label") or f"candidate_{len(normalized) + 1}").strip()
        normalized.append({"label": label, "query": query})
        if len(normalized) >= candidate_count:
            break
    if normalized:
        _bump_rewrite_runtime_stat(rewrite_runtime_stats, "llm_success_count")
        return normalized
    return _handle_failure(RuntimeError("LLM rewrite candidate response was empty."))


def confidence_score(
    retrieval: list[RetrievalCandidate],
    memory_affinity_score: float,
) -> float:
    if not retrieval:
        return 0.0
    top1 = max(0.0, min(1.0, (retrieval[0].score + 1.0) / 2.0))
    top3_items = retrieval[:3]
    top3 = sum(
        max(0.0, min(1.0, (item.score + 1.0) / 2.0))
        for item in top3_items
    ) / max(1, len(top3_items))
    memory_affinity = max(0.0, min(1.0, (memory_affinity_score + 1.0) / 2.0))
    return (0.45 * top1) + (0.20 * top3) + (0.35 * memory_affinity)


def _clamp01(value: float) -> float:
    return max(0.0, min(1.0, float(value)))


def _safe_ratio(numerator: float, denominator: float, *, default: float = 0.0) -> float:
    if denominator <= 0:
        return default
    return numerator / denominator


def _deep_merge_dict(base: dict[str, Any], override: dict[str, Any]) -> dict[str, Any]:
    merged: dict[str, Any] = {}
    for key, value in base.items():
        if isinstance(value, dict):
            merged[key] = _deep_merge_dict(value, {})
        elif isinstance(value, list):
            merged[key] = list(value)
        else:
            merged[key] = value
    for key, value in override.items():
        if isinstance(value, dict) and isinstance(merged.get(key), dict):
            merged[key] = _deep_merge_dict(dict(merged[key]), value)
        elif isinstance(value, list):
            merged[key] = list(value)
        else:
            merged[key] = value
    return merged


def _float_value(value: Any, default: float) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return float(default)


def _normalize_query_profile(
    query_category: str | None,
    *,
    raw_query: str,
) -> str | None:
    category = str(query_category or "").strip().lower()
    if "short_user" in category:
        return "short_user"
    if "code_mixed" in category:
        return "code_mixed"
    if any(token in category for token in ("troubleshooting", "error", "debug")):
        return "troubleshooting"
    query_lower = str(raw_query or "").strip().lower()
    if any(token in query_lower for token in TROUBLESHOOTING_HINT_TOKENS):
        return "troubleshooting"
    return None


def _resolve_rewrite_adoption_policy(
    policy: dict[str, Any] | None,
    *,
    query_category: str | None,
    raw_query: str,
) -> dict[str, Any]:
    source_policy = policy if isinstance(policy, dict) else {}
    looks_like_policy = any(
        key in source_policy
        for key in (
            "weights",
            "thresholds",
            "penalties",
            "bonuses",
            "shift_bonus_weight",
            "category_overrides",
        )
    )
    resolved_input = (
        {"rewrite_adoption_policy": source_policy}
        if looks_like_policy
        else source_policy
    )
    merged = _deep_merge_dict(
        resolve_rewrite_adoption_policy(resolved_input),
        {},
    )
    profile = _normalize_query_profile(
        query_category,
        raw_query=raw_query,
    )
    overrides = merged.get("category_overrides")
    if profile and isinstance(overrides, dict):
        profile_override = overrides.get(profile)
        if isinstance(profile_override, dict):
            merged = _deep_merge_dict(merged, profile_override)
    merged["query_profile"] = profile
    return merged


def _technical_token_set(
    text: str,
    *,
    query_language: str,
    max_items: int = 24,
) -> set[str]:
    return {
        token.casefold()
        for token in _extract_anchor_tokens(
            text,
            language_hint=query_language,
            max_items=max_items,
        )
    }


def _content_token_set(
    text: str,
    *,
    max_items: int = 24,
    include_generic: bool = False,
) -> set[str]:
    value = str(text or "")
    if not value:
        return set()
    collected: set[str] = set()
    for raw_token in CONTENT_TOKEN_RE.findall(value):
        token = normalize_anchor_text(raw_token)
        if not token:
            continue
        lowered = token.casefold()
        if not include_generic and lowered in GENERIC_CONTENT_TOKENS:
            continue
        if lowered.isdigit():
            continue
        if len(token) < 2:
            continue
        if len(token) < 3 and not has_technical_marker(token):
            continue
        collected.add(lowered)
        if len(collected) >= max_items:
            break
    return collected


def _memory_target_metrics(
    *,
    raw_query: str,
    candidate_query: str,
    memory_items: list[dict[str, Any]],
    query_profile: str | None,
    raw_memory_norm: float,
    underspecified_memory_norm_cutoff: float,
    memory_target_presence_bonus_weight: float,
    memory_target_missing_penalty_weight: float,
) -> dict[str, Any]:
    if not memory_items:
        return {
            "memory_target_tokens": [],
            "raw_target_overlap_count": 0,
            "candidate_target_overlap_count": 0,
            "raw_is_underspecified": False,
            "missing_memory_target": False,
            "memory_target_presence_bonus": 0.0,
            "memory_target_missing_penalty": 0.0,
        }

    top_memory = memory_items[0]
    memory_target_tokens = _content_token_set(
        str(top_memory.get("query_text") or ""),
        max_items=12,
    )
    for term in top_memory.get("glossary_terms") or []:
        memory_target_tokens |= _content_token_set(str(term), max_items=8)
        if len(memory_target_tokens) >= 16:
            break

    product_tokens = _content_token_set(
        str(top_memory.get("product") or "").replace("-", " ").replace("_", " "),
        max_items=4,
        include_generic=True,
    )
    memory_target_tokens -= product_tokens

    raw_content_tokens = _content_token_set(raw_query, max_items=12)
    candidate_content_tokens = _content_token_set(candidate_query, max_items=12)
    raw_target_overlap = raw_content_tokens & memory_target_tokens
    candidate_target_overlap = candidate_content_tokens & memory_target_tokens

    raw_is_underspecified = bool(
        str(query_profile or "").strip().lower() == "short_user"
        and raw_memory_norm >= underspecified_memory_norm_cutoff
        and memory_target_tokens
        and not raw_target_overlap
    )
    missing_memory_target = raw_is_underspecified and not candidate_target_overlap

    return {
        "memory_target_tokens": sorted(memory_target_tokens),
        "raw_target_overlap_count": len(raw_target_overlap),
        "candidate_target_overlap_count": len(candidate_target_overlap),
        "raw_is_underspecified": raw_is_underspecified,
        "missing_memory_target": missing_memory_target,
        "memory_target_presence_bonus": (
            memory_target_presence_bonus_weight
            if len(candidate_target_overlap) > len(raw_target_overlap)
            else 0.0
        ),
        "memory_target_missing_penalty": (
            memory_target_missing_penalty_weight
            if missing_memory_target
            else 0.0
        ),
    }


def _terminology_preservation_metrics(
    *,
    raw_query: str,
    candidate_query: str,
    query_language: str,
    raw_anchor_terms: list[str],
) -> dict[str, float]:
    raw_tokens = _technical_token_set(
        raw_query,
        query_language=query_language,
    )
    candidate_tokens = _technical_token_set(
        candidate_query,
        query_language=query_language,
    )
    preserved_count = len(raw_tokens & candidate_tokens)
    raw_token_count = len(raw_tokens)
    technical_preservation_ratio = (
        _safe_ratio(preserved_count, raw_token_count, default=1.0)
        if raw_token_count > 0
        else 1.0
    )

    raw_anchor_set = {str(item).casefold() for item in raw_anchor_terms if str(item).strip()}
    anchor_overlap_ratio = (
        _safe_ratio(len(raw_anchor_set & candidate_tokens), len(raw_anchor_set), default=1.0)
        if raw_anchor_set
        else technical_preservation_ratio
    )
    terminology_preservation_score = _clamp01((0.70 * technical_preservation_ratio) + (0.30 * anchor_overlap_ratio))
    return {
        "raw_technical_token_count": float(raw_token_count),
        "preserved_technical_token_count": float(preserved_count),
        "technical_preservation_ratio": technical_preservation_ratio,
        "anchor_overlap_ratio": anchor_overlap_ratio,
        "terminology_preservation_score": terminology_preservation_score,
    }


def _length_ratio_without_spaces(raw_query: str, candidate_query: str) -> float:
    raw_len = max(1, len("".join(str(raw_query or "").split())))
    candidate_len = max(1, len("".join(str(candidate_query or "").split())))
    return _safe_ratio(float(candidate_len), float(raw_len), default=1.0)


def _memory_alignment_score(
    *,
    raw_memory_similarity: float,
    candidate_memory_similarity: float,
) -> dict[str, float]:
    raw_norm = _clamp01((raw_memory_similarity + 1.0) / 2.0)
    candidate_norm = _clamp01((candidate_memory_similarity + 1.0) / 2.0)
    delta_norm = _clamp01(((candidate_memory_similarity - raw_memory_similarity) + 1.0) / 2.0)
    score = _clamp01((0.70 * candidate_norm) + (0.30 * delta_norm))
    return {
        "raw_memory_norm": raw_norm,
        "candidate_memory_norm": candidate_norm,
        "memory_alignment_score": score,
    }


def _weighted_candidate_score(
    *,
    retrieval_gain_score: float,
    terminology_preservation_score: float,
    memory_alignment_score: float,
    weights: dict[str, float],
) -> float:
    retrieval_weight = max(0.0, _float_value(weights.get("retrieval_gain"), 0.0))
    terminology_weight = max(0.0, _float_value(weights.get("terminology_preservation"), 0.0))
    memory_weight = max(0.0, _float_value(weights.get("memory_alignment"), 0.0))
    denominator = retrieval_weight + terminology_weight + memory_weight
    if denominator <= 0.0:
        return _clamp01(retrieval_gain_score)
    return _clamp01(
        (
            (retrieval_weight * retrieval_gain_score)
            + (terminology_weight * terminology_preservation_score)
            + (memory_weight * memory_alignment_score)
        )
        / denominator
    )


def _top_chunk_ids(retrieval: list[RetrievalCandidate], *, top_n: int = 5) -> list[str]:
    return [item.chunk_id for item in retrieval[:top_n] if item.chunk_id]


def _retrieval_shift_score(
    raw_retrieval: list[RetrievalCandidate],
    candidate_retrieval: list[RetrievalCandidate],
) -> float:
    raw_ids = _top_chunk_ids(raw_retrieval)
    candidate_ids = _top_chunk_ids(candidate_retrieval)
    if not raw_ids and not candidate_ids:
        return 0.0
    raw_set = set(raw_ids)
    candidate_set = set(candidate_ids)
    union = raw_set | candidate_set
    overlap = raw_set & candidate_set
    jaccard_distance = 1.0 - (len(overlap) / max(1, len(union)))
    top1_changed = 1.0 if raw_ids and candidate_ids and raw_ids[0] != candidate_ids[0] else 0.0
    return (0.7 * jaccard_distance) + (0.3 * top1_changed)


def _normalize_rewrite_retrieval_strategy(value: str | None) -> str:
    normalized = str(value or "").strip().lower()
    return normalized if normalized in REWRITE_RETRIEVAL_STRATEGIES else "replace"


def _dedup_candidates(
    rows: list[RetrievalCandidate],
    *,
    top_k: int,
) -> list[RetrievalCandidate]:
    seen: set[str] = set()
    merged: list[RetrievalCandidate] = []
    for row in rows:
        chunk_id = str(row.chunk_id or "").strip()
        if not chunk_id or chunk_id in seen:
            continue
        seen.add(chunk_id)
        merged.append(row)
        if len(merged) >= top_k:
            break
    return merged


def _merge_raw_and_rewrite_retrieval(
    *,
    strategy: str,
    raw_retrieval: list[RetrievalCandidate],
    rewrite_retrieval: list[RetrievalCandidate],
    top_k: int,
) -> list[RetrievalCandidate]:
    if strategy == "replace":
        return rewrite_retrieval
    if strategy == "interleave":
        interleaved: list[RetrievalCandidate] = []
        max_len = max(len(raw_retrieval), len(rewrite_retrieval))
        for index in range(max_len):
            if index < len(raw_retrieval):
                interleaved.append(raw_retrieval[index])
            if index < len(rewrite_retrieval):
                interleaved.append(rewrite_retrieval[index])
        return _dedup_candidates(interleaved, top_k=top_k)

    by_chunk: dict[str, RetrievalCandidate] = {}
    for row in [*raw_retrieval, *rewrite_retrieval]:
        chunk_id = str(row.chunk_id or "").strip()
        if not chunk_id:
            continue
        existing = by_chunk.get(chunk_id)
        if existing is None or row.score > existing.score:
            by_chunk[chunk_id] = row
    ranked = sorted(by_chunk.values(), key=lambda item: item.score, reverse=True)
    return ranked[:top_k]


def run_selective_rewrite(
    *,
    raw_query: str,
    query_language: str,
    session_context: dict[str, Any],
    chunks: list[ChunkItem],
    memories: list[MemoryItem],
    memory_top_n_value: int,
    candidate_count: int,
    threshold: float,
    retrieval_top_k: int,
    query_category: str | None = None,
    preset_filter: str | None = None,
    source_gate_run_id: str | None = None,
    strategy_filters: list[str] | None = None,
    force_rewrite: bool = False,
    rewrite_retrieval_strategy: str = "replace",
    rewrite_anchor_injection_enabled: bool = True,
    rewrite_terminology_hints_max_count: int = DEFAULT_REWRITE_TERMINOLOGY_HINTS_MAX,
    rewrite_failure_policy: str | None = None,
    rewrite_adoption_policy: dict[str, Any] | None = None,
    retriever_config: RetrieverConfig | None = None,
) -> tuple[RewriteOutcome, list[RetrievalCandidate]]:
    # Staged selective-rewrite scoring:
    # 1) retrieval gain (confidence + shift)
    # 2) terminology preservation / anchor overlap
    # 3) memory alignment
    # 4) verbosity + preservation penalties
    # Final adoption is gated by configurable thresholds/floors (category-aware).
    #
    # final_rewrite_latency_ms:
    #   measured from rewrite-stage entry through candidate validation/scoring and
    #   final rewrite-query adoption decision, excluding downstream answer scoring.
    config = retriever_config or build_retriever_config({})
    rewrite_started = time.perf_counter()
    memory_items = memory_top_n(
        raw_query,
        memories,
        top_n=memory_top_n_value,
        preset_filter=preset_filter,
        source_gate_run_id=source_gate_run_id,
        strategy_filters=strategy_filters,
        retriever_config=config,
    )
    raw_retrieval = retrieve_top_k(raw_query, chunks, top_k=retrieval_top_k, retriever_config=config)
    raw_memory_affinity = memory_items[0]["similarity"] if memory_items else 0.0
    raw_confidence_base = confidence_score(raw_retrieval, raw_memory_affinity)

    policy = _resolve_rewrite_adoption_policy(
        rewrite_adoption_policy,
        query_category=query_category,
        raw_query=raw_query,
    )
    weights = policy.get("weights") if isinstance(policy.get("weights"), dict) else {}
    thresholds = policy.get("thresholds") if isinstance(policy.get("thresholds"), dict) else {}
    penalties = policy.get("penalties") if isinstance(policy.get("penalties"), dict) else {}
    shift_bonus_weight = max(0.0, _float_value(policy.get("shift_bonus_weight"), 0.0))
    min_improvement = max(0.0, _float_value(thresholds.get("min_improvement"), 0.0))
    preservation_floor = _clamp01(_float_value(thresholds.get("preservation_floor"), 0.0))
    max_length_ratio = max(1.0, _float_value(thresholds.get("max_length_ratio"), 1.0))
    low_memory_similarity_cutoff = _clamp01(_float_value(thresholds.get("low_memory_similarity_cutoff"), 0.0))
    low_memory_extra_threshold = max(0.0, _float_value(thresholds.get("low_memory_extra_threshold"), 0.0))
    min_retrieval_gain_score = _clamp01(_float_value(thresholds.get("min_retrieval_gain_score"), 0.0))
    underspecified_memory_norm_cutoff = _clamp01(
        _float_value(thresholds.get("underspecified_memory_norm_cutoff"), 0.0)
    )

    verbosity_per_extra_ratio = max(0.0, _float_value(penalties.get("verbosity_per_extra_ratio"), 0.0))
    critical_token_drop_weight = max(0.0, _float_value(penalties.get("critical_token_drop"), 0.0))
    anchor_overlap_drop_weight = max(0.0, _float_value(penalties.get("anchor_overlap_drop"), 0.0))
    memory_target_missing_penalty_weight = max(
        0.0,
        _float_value(penalties.get("memory_target_missing"), 0.0),
    )
    bonuses = policy.get("bonuses") if isinstance(policy.get("bonuses"), dict) else {}
    memory_target_presence_bonus_weight = max(
        0.0,
        _float_value(bonuses.get("memory_target_presence"), 0.0),
    )
    query_profile = str(policy.get("query_profile") or "").strip().lower()

    raw_memory = _memory_alignment_score(
        raw_memory_similarity=raw_memory_affinity,
        candidate_memory_similarity=raw_memory_affinity,
    )
    raw_reference_score = _weighted_candidate_score(
        retrieval_gain_score=raw_confidence_base,
        terminology_preservation_score=1.0,
        memory_alignment_score=raw_memory["memory_alignment_score"],
        weights=weights,
    )
    raw_confidence = raw_reference_score

    normalized_strategy = _normalize_rewrite_retrieval_strategy(rewrite_retrieval_strategy)
    rewrite_runtime_stats: dict[str, int] = {}
    candidate_templates = build_rewrite_candidates_v2(
        raw_query,
        memory_items,
        session_context=session_context,
        candidate_count=candidate_count,
        query_language=query_language,
        rewrite_anchor_injection_enabled=rewrite_anchor_injection_enabled,
        rewrite_terminology_hints_max_count=rewrite_terminology_hints_max_count,
        rewrite_failure_policy=rewrite_failure_policy,
        rewrite_runtime_stats=rewrite_runtime_stats,
    )
    rewrite_llm_attempted = rewrite_runtime_stats.get("llm_attempted_count", 0) > 0
    rewrite_llm_succeeded = rewrite_runtime_stats.get("llm_success_count", 0) > 0
    rewrite_heuristic_fallback_used = rewrite_runtime_stats.get("heuristic_fallback_count", 0) > 0
    pure_rewrite_latency_ms = (
        float(rewrite_runtime_stats["pure_rewrite_latency_ms"])
        if "pure_rewrite_latency_ms" in rewrite_runtime_stats
        else None
    )
    anchor_context = _build_rewrite_anchor_candidates(
        raw_query=raw_query,
        query_language=query_language,
        memory_items=memory_items,
    )
    raw_anchor_terms = [str(item) for item in anchor_context.get("anchor_terms") or [] if str(item).strip()]
    candidates: list[dict[str, Any]] = []
    best_candidate: dict[str, Any] | None = None
    best_eligible_candidate: dict[str, Any] | None = None
    for template in candidate_templates:
        retrieved = retrieve_top_k(template["query"], chunks, top_k=retrieval_top_k, retriever_config=config)
        candidate_memory_items = memory_top_n(
            template["query"],
            memories,
            top_n=memory_top_n_value,
            preset_filter=preset_filter,
            source_gate_run_id=source_gate_run_id,
            strategy_filters=strategy_filters,
            retriever_config=config,
        )
        candidate_memory_affinity = (
            candidate_memory_items[0]["similarity"]
            if candidate_memory_items
            else 0.0
        )
        base_confidence = confidence_score(retrieved, candidate_memory_affinity)
        retrieval_shift = _retrieval_shift_score(raw_retrieval, retrieved)
        shift_bonus = shift_bonus_weight * retrieval_shift if base_confidence >= raw_confidence_base else 0.0
        retrieval_gain_score = _clamp01(base_confidence + shift_bonus)

        terminology_metrics = _terminology_preservation_metrics(
            raw_query=raw_query,
            candidate_query=template["query"],
            query_language=query_language,
            raw_anchor_terms=raw_anchor_terms,
        )
        terminology_preservation_score = terminology_metrics["terminology_preservation_score"]
        technical_preservation_ratio = terminology_metrics["technical_preservation_ratio"]
        anchor_overlap_ratio = terminology_metrics["anchor_overlap_ratio"]

        memory_metrics = _memory_alignment_score(
            raw_memory_similarity=raw_memory_affinity,
            candidate_memory_similarity=candidate_memory_affinity,
        )
        memory_alignment_score = memory_metrics["memory_alignment_score"]
        memory_target_metrics = _memory_target_metrics(
            raw_query=raw_query,
            candidate_query=template["query"],
            memory_items=memory_items,
            query_profile=query_profile,
            raw_memory_norm=raw_memory["raw_memory_norm"],
            underspecified_memory_norm_cutoff=underspecified_memory_norm_cutoff,
            memory_target_presence_bonus_weight=memory_target_presence_bonus_weight,
            memory_target_missing_penalty_weight=memory_target_missing_penalty_weight,
        )

        length_ratio = _length_ratio_without_spaces(raw_query, template["query"])
        verbosity_penalty = max(0.0, length_ratio - 1.0) * verbosity_per_extra_ratio
        critical_token_drop_penalty = max(0.0, 1.0 - technical_preservation_ratio) * critical_token_drop_weight
        anchor_overlap_penalty = max(0.0, 1.0 - anchor_overlap_ratio) * anchor_overlap_drop_weight
        preservation_penalty = critical_token_drop_penalty + anchor_overlap_penalty

        weighted_score = _weighted_candidate_score(
            retrieval_gain_score=retrieval_gain_score,
            terminology_preservation_score=terminology_preservation_score,
            memory_alignment_score=memory_alignment_score,
            weights=weights,
        )
        final_candidate_score = _clamp01(
            weighted_score
            + memory_target_metrics["memory_target_presence_bonus"]
            - verbosity_penalty
            - preservation_penalty
            - memory_target_metrics["memory_target_missing_penalty"]
        )

        low_memory_strict_threshold = (
            low_memory_extra_threshold
            if raw_memory["raw_memory_norm"] < low_memory_similarity_cutoff
            else 0.0
        )
        effective_threshold = max(float(threshold), min_improvement) + low_memory_strict_threshold
        adoption_margin = final_candidate_score - raw_reference_score
        normalized_raw_query = " ".join(raw_query.split()).strip().lower()
        normalized_candidate_query = " ".join(str(template["query"]).split()).strip().lower()
        same_query = normalized_raw_query == normalized_candidate_query

        rejection_reason = ""
        if same_query:
            rejection_reason = "candidate_same_as_raw"
        elif length_ratio > max_length_ratio:
            rejection_reason = "verbosity_exceeds_limit"
        elif terminology_preservation_score < preservation_floor:
            rejection_reason = "preservation_below_floor"
        elif retrieval_gain_score < min_retrieval_gain_score:
            rejection_reason = "retrieval_gain_below_floor"
        elif memory_target_metrics["missing_memory_target"]:
            rejection_reason = "missing_memory_target"
        elif adoption_margin < effective_threshold:
            rejection_reason = "delta_below_threshold"

        eligible = not rejection_reason
        candidate = {
            "label": template["label"],
            "query": template["query"],
            "base_confidence": base_confidence,
            "raw_memory_similarity": raw_memory_affinity,
            "candidate_memory_similarity": candidate_memory_affinity,
            "memory_similarity_delta": candidate_memory_affinity - raw_memory_affinity,
            "retrieval_shift_score": retrieval_shift,
            "retrieval_shift_bonus": shift_bonus,
            "retrieval_gain_score": retrieval_gain_score,
            "terminology_preservation_score": terminology_preservation_score,
            "memory_alignment_score": memory_alignment_score,
            "technical_preservation_ratio": technical_preservation_ratio,
            "anchor_overlap_ratio": anchor_overlap_ratio,
            "verbosity_penalty": verbosity_penalty,
            "preservation_penalty": preservation_penalty,
            "memory_target_presence_bonus": memory_target_metrics["memory_target_presence_bonus"],
            "memory_target_missing_penalty": memory_target_metrics["memory_target_missing_penalty"],
            "memory_target_tokens": memory_target_metrics["memory_target_tokens"],
            "raw_target_overlap_count": memory_target_metrics["raw_target_overlap_count"],
            "candidate_target_overlap_count": memory_target_metrics["candidate_target_overlap_count"],
            "raw_is_underspecified": memory_target_metrics["raw_is_underspecified"],
            "final_candidate_score": final_candidate_score,
            "adoption_margin": adoption_margin,
            "effective_threshold": effective_threshold,
            "preservation_floor": preservation_floor,
            "max_length_ratio": max_length_ratio,
            "length_ratio": length_ratio,
            "eligible": eligible,
            "rejection_reason": rejection_reason,
            "confidence": final_candidate_score,
            "retrieval": retrieved,
        }
        candidates.append(candidate)
        if best_candidate is None or final_candidate_score > float(best_candidate.get("confidence", 0.0)):
            best_candidate = candidate
        if eligible and (best_eligible_candidate is None or final_candidate_score > float(best_eligible_candidate.get("confidence", 0.0))):
            best_eligible_candidate = candidate

    if best_candidate is None:
        rewrite_elapsed_ms = (time.perf_counter() - rewrite_started) * 1000.0
        return (
            RewriteOutcome(
                final_query=raw_query,
                rewrite_applied=False,
                rewrite_reason="no_candidate",
                raw_confidence=raw_confidence,
                best_candidate_confidence=raw_confidence,
                memory_top_n=memory_items,
                candidates=[],
                rewrite_llm_attempted=rewrite_llm_attempted,
                rewrite_llm_succeeded=rewrite_llm_succeeded,
                rewrite_heuristic_fallback_used=rewrite_heuristic_fallback_used,
                final_rewrite_latency_ms=None,
                pure_rewrite_latency_ms=pure_rewrite_latency_ms,
            ),
            raw_retrieval,
        )

    selected_candidate = best_eligible_candidate or best_candidate
    normalized_raw_query = " ".join(raw_query.split()).strip().lower()
    normalized_best_query = " ".join(str(selected_candidate["query"]).split()).strip().lower()
    same_query = normalized_raw_query == normalized_best_query
    selected_rejection_reason = str(selected_candidate.get("rejection_reason") or "").strip()
    should_apply = bool(
        selected_candidate.get("eligible")
        and (not same_query)
    )
    final_query = selected_candidate["query"] if should_apply else raw_query
    if should_apply:
        final_retrieval = _merge_raw_and_rewrite_retrieval(
            strategy=normalized_strategy,
            raw_retrieval=raw_retrieval,
            rewrite_retrieval=selected_candidate["retrieval"],
            top_k=retrieval_top_k,
        )
    else:
        final_retrieval = raw_retrieval
    reason = (
        "forced"
        if should_apply and force_rewrite
        else "delta_above_threshold"
        if should_apply
        else "candidate_same_as_raw"
        if same_query
        else selected_rejection_reason
        if selected_rejection_reason
        else "delta_below_threshold"
    )
    rewrite_elapsed_ms = (time.perf_counter() - rewrite_started) * 1000.0

    return (
        RewriteOutcome(
            final_query=final_query,
            rewrite_applied=should_apply,
            rewrite_reason=reason,
            raw_confidence=raw_confidence,
            best_candidate_confidence=float(selected_candidate.get("confidence", 0.0)),
            memory_top_n=memory_items,
            candidates=[
                {
                    "label": candidate["label"],
                    "query": candidate["query"],
                    "base_confidence": candidate.get("base_confidence", candidate["confidence"]),
                    "raw_memory_similarity": candidate.get("raw_memory_similarity", 0.0),
                    "candidate_memory_similarity": candidate.get("candidate_memory_similarity", 0.0),
                    "memory_similarity_delta": candidate.get("memory_similarity_delta", 0.0),
                    "retrieval_shift_score": candidate.get("retrieval_shift_score", 0.0),
                    "retrieval_shift_bonus": candidate.get("retrieval_shift_bonus", 0.0),
                    "retrieval_gain_score": candidate.get("retrieval_gain_score", 0.0),
                    "terminology_preservation_score": candidate.get("terminology_preservation_score", 0.0),
                    "memory_alignment_score": candidate.get("memory_alignment_score", 0.0),
                    "technical_preservation_ratio": candidate.get("technical_preservation_ratio", 0.0),
                    "anchor_overlap_ratio": candidate.get("anchor_overlap_ratio", 0.0),
                    "verbosity_penalty": candidate.get("verbosity_penalty", 0.0),
                    "preservation_penalty": candidate.get("preservation_penalty", 0.0),
                    "memory_target_presence_bonus": candidate.get("memory_target_presence_bonus", 0.0),
                    "memory_target_missing_penalty": candidate.get("memory_target_missing_penalty", 0.0),
                    "memory_target_tokens": candidate.get("memory_target_tokens", []),
                    "raw_target_overlap_count": candidate.get("raw_target_overlap_count", 0),
                    "candidate_target_overlap_count": candidate.get("candidate_target_overlap_count", 0),
                    "raw_is_underspecified": candidate.get("raw_is_underspecified", False),
                    "final_candidate_score": candidate.get("final_candidate_score", candidate["confidence"]),
                    "adoption_margin": candidate.get("adoption_margin", 0.0),
                    "effective_threshold": candidate.get("effective_threshold", 0.0),
                    "preservation_floor": candidate.get("preservation_floor", 0.0),
                    "max_length_ratio": candidate.get("max_length_ratio", 0.0),
                    "length_ratio": candidate.get("length_ratio", 0.0),
                    "eligible": bool(candidate.get("eligible")),
                    "rejection_reason": candidate.get("rejection_reason", ""),
                    "confidence": candidate["confidence"],
                }
                for candidate in candidates
            ],
            rewrite_llm_attempted=rewrite_llm_attempted,
            rewrite_llm_succeeded=rewrite_llm_succeeded,
            rewrite_heuristic_fallback_used=rewrite_heuristic_fallback_used,
            final_rewrite_latency_ms=rewrite_elapsed_ms if should_apply else None,
            pure_rewrite_latency_ms=pure_rewrite_latency_ms,
        ),
        final_retrieval,
    )


def retrieval_metrics(
    *,
    expected_chunk_ids: list[str],
    expected_doc_ids: list[str],
    retrieved: list[RetrievalCandidate],
) -> dict[str, float]:
    expected_chunk_set = {str(chunk_id) for chunk_id in expected_chunk_ids if str(chunk_id).strip()}
    expected_doc_set = {str(doc_id) for doc_id in expected_doc_ids if str(doc_id).strip()}
    ranks = {candidate.chunk_id: index + 1 for index, candidate in enumerate(retrieved)}
    doc_ranks: dict[str, int] = {}
    for index, candidate in enumerate(retrieved, start=1):
        if candidate.document_id not in doc_ranks:
            doc_ranks[candidate.document_id] = index

    if expected_chunk_set:
        hits = sum(1 for chunk_id in expected_chunk_set if ranks.get(chunk_id, 9999) <= 5)
        recall_at_5 = hits / max(1, len(expected_chunk_set))
        first_rank = min([ranks.get(chunk_id, 9999) for chunk_id in expected_chunk_set] or [9999])
    else:
        hits = sum(1 for doc_id in expected_doc_set if doc_ranks.get(doc_id, 9999) <= 5)
        recall_at_5 = hits / max(1, len(expected_doc_set))
        first_rank = min([doc_ranks.get(doc_id, 9999) for doc_id in expected_doc_set] or [9999])
    hit_at_5 = 1.0 if hits > 0 else 0.0
    mrr_at_10 = 1.0 / first_rank if first_rank <= 10 else 0.0

    dcg = 0.0
    if expected_chunk_set:
        seen_chunks: set[str] = set()
        for index, candidate in enumerate(retrieved[:10], start=1):
            if candidate.chunk_id in expected_chunk_set and candidate.chunk_id not in seen_chunks:
                seen_chunks.add(candidate.chunk_id)
                dcg += 1.0 / math.log2(index + 1)
        ideal_count = min(10, len(expected_chunk_set))
    else:
        seen_docs: set[str] = set()
        for index, candidate in enumerate(retrieved[:10], start=1):
            if candidate.document_id in expected_doc_set and candidate.document_id not in seen_docs:
                seen_docs.add(candidate.document_id)
                dcg += 1.0 / math.log2(index + 1)
        ideal_count = min(10, len(expected_doc_set))

    idcg = 0.0
    for index in range(1, ideal_count + 1):
        idcg += 1.0 / math.log2(index + 1)
    ndcg_at_10 = max(0.0, min(1.0, dcg / idcg)) if idcg > 0 else 0.0

    return {
        "recall@5": recall_at_5,
        "hit@5": hit_at_5,
        "mrr@10": mrr_at_10,
        "ndcg@10": ndcg_at_10,
    }
