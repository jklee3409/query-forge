from __future__ import annotations

import hashlib
import json
import math
import os
import re
import threading
import time
import unicodedata
from collections.abc import Mapping
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
    from common.embeddings import embed_text, embedding_to_halfvec_literal
    from common.experiment_config import resolve_rewrite_adoption_policy
    from common.local_retriever import (
        RETRIEVAL_MODE_BM25_ONLY,
        RETRIEVAL_MODE_DENSE_ONLY,
        RETRIEVAL_MODE_HYBRID,
        RetrieverConfig,
        build_canonical_lexical_text,
        build_retriever_config,
        embed_query_with_retriever_config,
        get_local_text_retriever,
        local_retriever_name,
        rank_with_precomputed_embeddings,
    )
    from common.llm_client import LlmClient, load_stage_config
except ModuleNotFoundError:  # pragma: no cover
    from pipeline.common.anchor_quality import (
        extract_technical_tokens,
        has_technical_marker,
        is_valid_anchor_phrase,
        normalize_anchor_text,
    )
    from pipeline.common.cohere_reranker import CohereReranker, load_cohere_rerank_config
    from pipeline.common.embeddings import embed_text, embedding_to_halfvec_literal
    from pipeline.common.experiment_config import resolve_rewrite_adoption_policy
    from pipeline.common.local_retriever import (
        RETRIEVAL_MODE_BM25_ONLY,
        RETRIEVAL_MODE_DENSE_ONLY,
        RETRIEVAL_MODE_HYBRID,
        RetrieverConfig,
        build_canonical_lexical_text,
        build_retriever_config,
        embed_query_with_retriever_config,
        get_local_text_retriever,
        local_retriever_name,
        rank_with_precomputed_embeddings,
    )
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
    canonical_anchors: dict[str, Any] | None = None
    target_title: str | None = None
    target_section_path: str | None = None
    target_chunk_preview: str | None = None
    utility_score: float | None = None
    final_score: float | None = None


@dataclass(slots=True)
class MultiSourceAnchorIndex:
    relation_version: str
    alias_to_canonical_ids: dict[str, list[str]]
    terms_by_id: dict[str, dict[str, Any]]
    relations_by_anchor_id: dict[str, list[dict[str, Any]]]


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
    selected_rewrite: dict[str, Any] | None = None
    anchor_candidates: list[dict[str, Any]] = field(default_factory=list)
    terminology_hints: dict[str, Any] | None = None
    canonical_anchor_hints: dict[str, Any] | None = None
    rewrite_llm_attempted: bool = False
    rewrite_llm_succeeded: bool = False
    rewrite_heuristic_fallback_used: bool = False
    final_rewrite_latency_ms: float | None = None
    pure_rewrite_latency_ms: float | None = None
    multi_source_anchor_hints: dict[str, Any] | None = None
    memory_hint_query: str | None = None
    memory_hint_retrieval_applied: bool = False


@dataclass(slots=True)
class AgenticSubquery:
    index: int
    query: str
    intent: str = "subquery"
    weight: float = 1.0


@dataclass(slots=True)
class AgenticQueryPlan:
    original_query: str
    subqueries: list[AgenticSubquery]
    fallback_applied: bool
    fallback_reason: str | None = None
    planner_model: str | None = None
    planner_latency_ms: float = 0.0
    metadata: dict[str, Any] = field(default_factory=dict)


RETRIEVAL_BACKEND_LOCAL = "local"
RETRIEVAL_BACKEND_DB_ANN = "db_ann"


def retrieval_candidates_to_payload(retrieval: list[RetrievalCandidate]) -> list[dict[str, Any]]:
    return [
        {
            "chunk_id": candidate.chunk_id,
            "document_id": candidate.document_id,
            "score": candidate.score,
            "text": candidate.text,
        }
        for candidate in retrieval
    ]


def retrieval_candidates_from_payload(payload: Any) -> list[RetrievalCandidate]:
    if not isinstance(payload, list):
        return []
    candidates: list[RetrievalCandidate] = []
    for row in payload:
        if not isinstance(row, Mapping):
            continue
        chunk_id = str(row.get("chunk_id") or "").strip()
        document_id = str(row.get("document_id") or "").strip()
        if not chunk_id or not document_id:
            continue
        try:
            score = float(row.get("score") or 0.0)
        except (TypeError, ValueError):
            score = 0.0
        candidates.append(
            RetrievalCandidate(
                chunk_id=chunk_id,
                document_id=document_id,
                score=score,
                text=str(row.get("text") or ""),
            )
        )
    return candidates


def normalize_retrieval_backend(value: str | None) -> str:
    normalized = str(value or RETRIEVAL_BACKEND_LOCAL).strip().lower().replace("-", "_")
    return RETRIEVAL_BACKEND_DB_ANN if normalized == RETRIEVAL_BACKEND_DB_ANN else RETRIEVAL_BACKEND_LOCAL


def _parse_halfvec_literal(raw: str) -> list[float]:
    text = str(raw or "").strip()
    if not text:
        return []
    if text.startswith("[") and text.endswith("]"):
        text = text[1:-1]
    if not text.strip():
        return []
    return [float(part.strip()) for part in text.split(",") if part.strip()]


def _json_mapping_or_none(value: Any) -> dict[str, Any] | None:
    if isinstance(value, Mapping):
        return dict(value)
    if isinstance(value, str) and value.strip():
        try:
            parsed = json.loads(value)
        except json.JSONDecodeError:
            return None
        if isinstance(parsed, Mapping):
            return dict(parsed)
    return None


class DbAnnRuntimeRetrievalAdapter:
    def __init__(
        self,
        connection: psycopg.Connection[Any],
        *,
        allowed_products: list[str] | None,
        include_document_ids: list[str] | None,
        memory_experiment_key: str | None,
        retriever_config: RetrieverConfig | None = None,
    ) -> None:
        self.connection = connection
        self.allowed_products = [str(item) for item in (allowed_products or []) if str(item).strip()]
        self.include_document_ids = [str(item) for item in (include_document_ids or []) if str(item).strip()]
        self.memory_experiment_key = str(memory_experiment_key).strip() if memory_experiment_key else None
        self.config = retriever_config or build_retriever_config({})
        if self.config.mode not in {RETRIEVAL_MODE_DENSE_ONLY, RETRIEVAL_MODE_HYBRID}:
            raise RuntimeError("db-ann retrieval backend requires retriever_mode=dense_only or hybrid")
        self.embedding_model = str(self.config.dense_embedding_model).strip()
        if not self.embedding_model:
            raise RuntimeError("db-ann retrieval backend requires dense_embedding_model")
        if bool(self.config.dense_fallback_enabled):
            raise RuntimeError("db-ann retrieval backend must not enable dense_fallback_enabled")
        self.vector_store = "postgresql-pgvector"
        self.backend = RETRIEVAL_BACKEND_DB_ANN
        self.fallback_used = False
        self._query_embedding_cache: dict[str, list[float]] = {}

    @property
    def retriever_name(self) -> str:
        return f"db-ann:{self.config.mode}:{self.embedding_model}"

    def metadata(self) -> dict[str, Any]:
        return {
            "retrieval_backend": self.backend,
            "embedding_model": self.embedding_model,
            "vector_store": self.vector_store,
            "fallback_used": self.fallback_used,
            "retriever_name": self.retriever_name,
            "retriever_config": self.config.to_metadata(),
        }

    def retrieve_top_k(
        self,
        query_text: str,
        *,
        top_k: int,
        query_canonical_anchors: Any | None = None,
    ) -> list[RetrievalCandidate]:
        if top_k <= 0:
            return []
        query_embedding = self._query_embedding(query_text)
        candidate_pool_k = top_k if self.config.mode == RETRIEVAL_MODE_DENSE_ONLY else max(top_k, self.config.candidate_pool_k)
        rows = self._fetch_chunk_ann_pool(query_embedding, limit=candidate_pool_k)
        lexical_query_text = _canonical_lexical_query_text(query_text, query_canonical_anchors)
        if self.config.mode == RETRIEVAL_MODE_HYBRID:
            rows = self._merge_candidate_rows(
                [
                    rows,
                    self._fetch_chunk_lexical_pool(lexical_query_text, limit=candidate_pool_k),
                    self._fetch_chunk_technical_pool(lexical_query_text, limit=candidate_pool_k),
                ],
                key="chunk_id",
            )
        if not rows:
            return []
        ranked = rank_with_precomputed_embeddings(
            query_text,
            item_ids=[row["chunk_id"] for row in rows],
            texts=[row["chunk_text"] for row in rows],
            passage_embeddings=[row["embedding"] for row in rows],
            query_embedding=query_embedding,
            retriever_config=self.config,
            lexical_query_text=lexical_query_text,
            top_k=max(top_k, len(rows)),
        )
        reduced = [
            RetrievalCandidate(
                chunk_id=rows[item.index]["chunk_id"],
                document_id=rows[item.index]["document_id"],
                score=item.score,
                text=rows[item.index]["chunk_text"],
            )
            for item in ranked
        ]
        return rerank_retrieval_candidates(
            query_text,
            reduced,
            top_n=top_k,
            retriever_config=self.config,
        )

    def memory_top_n(
        self,
        query_text: str,
        *,
        top_n: int,
        preset_filter: str | None = None,
        source_gate_run_id: str | None = None,
        strategy_filters: list[str] | None = None,
    ) -> list[dict[str, Any]]:
        if top_n <= 0:
            return []
        if not self.memory_experiment_key:
            return []
        query_embedding = self._query_embedding(query_text)
        strategy_values = [str(item).upper() for item in (strategy_filters or []) if str(item).strip()]
        candidate_pool_k = top_n if self.config.mode == RETRIEVAL_MODE_DENSE_ONLY else max(top_n, self.config.candidate_pool_k)
        rows = self._fetch_memory_ann_pool(
            query_embedding,
            limit=candidate_pool_k,
            preset_filter=preset_filter,
            source_gate_run_id=source_gate_run_id,
            strategy_filters=strategy_values,
        )
        lexical_query_text = _canonical_lexical_query_text(
            query_text,
            [row.get("canonical_anchors") for row in rows],
        )
        if self.config.mode == RETRIEVAL_MODE_HYBRID:
            rows = self._merge_candidate_rows(
                [
                    rows,
                    self._fetch_memory_lexical_pool(
                        lexical_query_text,
                        limit=candidate_pool_k,
                        preset_filter=preset_filter,
                        source_gate_run_id=source_gate_run_id,
                        strategy_filters=strategy_values,
                    ),
                    self._fetch_memory_technical_pool(
                        lexical_query_text,
                        limit=candidate_pool_k,
                        preset_filter=preset_filter,
                        source_gate_run_id=source_gate_run_id,
                        strategy_filters=strategy_values,
                    ),
                ],
                key="memory_id",
            )
            lexical_query_text = _canonical_lexical_query_text(
                query_text,
                [row.get("canonical_anchors") for row in rows],
            )
        if not rows:
            return []
        lexical_texts = [
            build_canonical_lexical_text(row["query_text"], row.get("canonical_anchors"))
            for row in rows
        ]
        ranked = rank_with_precomputed_embeddings(
            query_text,
            item_ids=[row["memory_id"] for row in rows],
            texts=[row["query_text"] for row in rows],
            passage_embeddings=[row["embedding"] for row in rows],
            query_embedding=query_embedding,
            retriever_config=self.config,
            lexical_query_text=lexical_query_text,
            lexical_texts=lexical_texts,
            top_k=max(top_n, len(rows)),
        )
        scored: list[dict[str, Any]] = []
        for item in ranked[:top_n]:
            row = rows[item.index]
            scored.append(
                {
                    "memory_id": row["memory_id"],
                    "query_text": row["query_text"],
                    "target_doc_id": row["target_doc_id"],
                    "target_chunk_ids": row["target_chunk_ids"],
                    "generation_strategy": row["generation_strategy"],
                    "product": row["product"],
                    "glossary_terms": row["glossary_terms"],
                    "canonical_anchors": row.get("canonical_anchors"),
                    "target_title": row.get("target_title"),
                    "target_section_path": row.get("target_section_path"),
                    "target_chunk_preview": row.get("target_chunk_preview"),
                    "utility_score": row.get("utility_score"),
                    "final_score": row.get("final_score"),
                    "similarity": item.score,
                    "dense_similarity": item.dense_score,
                    "bm25_score": item.bm25_score,
                    "technical_token_overlap": item.technical_score,
                    "retriever": self.retriever_name,
                }
            )
        return scored

    @staticmethod
    def _merge_candidate_rows(row_groups: list[list[dict[str, Any]]], *, key: str) -> list[dict[str, Any]]:
        merged: dict[str, dict[str, Any]] = {}
        for rows in row_groups:
            for row in rows:
                row_key = str(row.get(key) or "").strip()
                if row_key and row_key not in merged:
                    merged[row_key] = row
        return list(merged.values())

    def _chunk_scope_filters(self) -> tuple[list[str], list[Any]]:
        where_clauses = ["ce.embedding_model = %s"]
        parameters: list[Any] = [self.embedding_model]
        normalized_products = [
            _normalize_product_scope_key(item)
            for item in self.allowed_products
            if str(item).strip()
        ]
        if normalized_products and self.include_document_ids:
            where_clauses.append(
                "(LOWER(COALESCE(d.product_name, '')) = ANY(%s) OR c.document_id = ANY(%s))"
            )
            parameters.extend([normalized_products, self.include_document_ids])
        elif self.include_document_ids:
            where_clauses.append("c.document_id = ANY(%s)")
            parameters.append(self.include_document_ids)
        elif normalized_products:
            where_clauses.append("LOWER(COALESCE(d.product_name, '')) = ANY(%s)")
            parameters.append(normalized_products)
        return where_clauses, parameters

    @staticmethod
    def _chunk_row(row: Mapping[str, Any]) -> dict[str, Any]:
        return {
            "chunk_id": str(row["chunk_id"]),
            "document_id": str(row["document_id"]),
            "chunk_text": str(row["chunk_text"] or ""),
            "embedding": _parse_halfvec_literal(str(row["embedding_literal"] or "")),
            "ann_score": float(row.get("ann_score") or 0.0),
        }

    @staticmethod
    def _memory_row(row: Mapping[str, Any]) -> dict[str, Any]:
        return {
            "memory_id": str(row["memory_id"]),
            "query_text": str(row["query_text"] or ""),
            "target_doc_id": str(row["target_doc_id"] or ""),
            "target_chunk_ids": list(row["target_chunk_ids"] or []),
            "generation_strategy": str(row["generation_strategy"] or ""),
            "product": str(row["product"]) if row.get("product") else None,
            "glossary_terms": [str(item) for item in (row["glossary_terms"] or []) if str(item).strip()],
            "canonical_anchors": _json_mapping_or_none(row.get("canonical_anchors")),
            "target_title": str(row.get("target_title") or "") or None,
            "target_section_path": str(row.get("target_section_path_text") or "") or None,
            "target_chunk_preview": str(row.get("target_chunk_preview") or "") or None,
            "utility_score": float(row["utility_score"]) if row.get("utility_score") is not None else None,
            "final_score": float(row["final_score"]) if row.get("final_score") is not None else None,
            "embedding": _parse_halfvec_literal(str(row["embedding_literal"] or "")),
            "ann_score": float(row.get("ann_score") or 0.0),
        }

    @staticmethod
    def _technical_patterns(query_text: str) -> list[str]:
        tokens = extract_technical_tokens(query_text, max_items=12)
        patterns: list[str] = []
        seen: set[str] = set()
        for token in tokens:
            value = str(token or "").strip()
            if not value:
                continue
            key = value.casefold()
            if key in seen:
                continue
            seen.add(key)
            patterns.append(f"%{value}%")
        return patterns

    @staticmethod
    def _lexical_patterns(query_text: str) -> list[str]:
        raw_tokens = re.findall(r"[@A-Za-z0-9_./:$-]{2,}|[\uac00-\ud7a3]{2,}", str(query_text or ""))
        patterns: list[str] = []
        seen: set[str] = set()
        for raw_token in raw_tokens:
            token = normalize_anchor_text(raw_token)
            if len(token) < 2:
                continue
            key = token.casefold()
            if key in seen:
                continue
            seen.add(key)
            patterns.append(f"%{token}%")
            if len(patterns) >= 16:
                break
        return patterns

    def _query_embedding(self, query_text: str) -> list[float]:
        cached = self._query_embedding_cache.get(query_text)
        if cached is not None:
            return cached
        embedding, model_name, fallback_used = embed_query_with_retriever_config(
            query_text,
            retriever_config=self.config,
            require_real_dense=True,
        )
        if fallback_used:
            raise RuntimeError("db-ann retrieval backend must not fall back to hash-embedding-v1")
        if str(model_name).strip() != self.embedding_model:
            raise RuntimeError(
                f"query embedding model mismatch: expected={self.embedding_model}, actual={model_name}"
            )
        self._query_embedding_cache[query_text] = embedding
        return embedding

    def _fetch_chunk_ann_pool(self, query_embedding: list[float], *, limit: int) -> list[dict[str, Any]]:
        where_clauses, parameters = self._chunk_scope_filters()
        embedding_literal = embedding_to_halfvec_literal(query_embedding)
        sql_parameters = [embedding_literal, *parameters, embedding_literal, max(1, int(limit))]
        with self.connection.cursor() as cursor:
            cursor.execute(
                f"""
                SELECT c.chunk_id,
                       c.document_id,
                       c.chunk_text,
                       ce.embedding::text AS embedding_literal,
                       1 - (ce.embedding <=> CAST(%s AS halfvec)) AS ann_score
                FROM chunk_embeddings ce
                JOIN corpus_chunks c ON c.chunk_id = ce.chunk_id
                JOIN corpus_documents d ON d.document_id = c.document_id
                WHERE {' AND '.join(where_clauses)}
                ORDER BY ce.embedding <=> CAST(%s AS halfvec), c.chunk_id
                LIMIT %s
                """,
                sql_parameters,
            )
            rows = cursor.fetchall()
        return [self._chunk_row(row) for row in rows]

    def _fetch_chunk_lexical_pool(self, query_text: str, *, limit: int) -> list[dict[str, Any]]:
        lexical_query = str(query_text or "").strip()
        if not lexical_query:
            return []
        patterns = self._lexical_patterns(lexical_query)
        if not patterns:
            return []
        where_clauses, parameters = self._chunk_scope_filters()
        sql_parameters: list[Any] = [
            lexical_query,
            lexical_query,
            lexical_query,
            *parameters,
            patterns,
            patterns,
            patterns,
            max(1, int(limit)),
        ]
        with self.connection.cursor() as cursor:
            cursor.execute(
                f"""
                SELECT c.chunk_id,
                       c.document_id,
                       c.chunk_text,
                       ce.embedding::text AS embedding_literal,
                       GREATEST(
                           similarity(c.chunk_text, %s),
                           similarity(COALESCE(c.section_path_text, ''), %s),
                           similarity(COALESCE(d.title, ''), %s)
                       ) AS lexical_score
                FROM chunk_embeddings ce
                JOIN corpus_chunks c ON c.chunk_id = ce.chunk_id
                JOIN corpus_documents d ON d.document_id = c.document_id
                WHERE {' AND '.join(where_clauses)}
                  AND (
                      c.chunk_text ILIKE ANY(%s)
                      OR COALESCE(c.section_path_text, '') ILIKE ANY(%s)
                      OR COALESCE(d.title, '') ILIKE ANY(%s)
                  )
                ORDER BY lexical_score DESC, c.chunk_id
                LIMIT %s
                """,
                sql_parameters,
            )
            rows = cursor.fetchall()
        return [self._chunk_row(row) for row in rows]

    def _fetch_chunk_technical_pool(self, query_text: str, *, limit: int) -> list[dict[str, Any]]:
        patterns = self._technical_patterns(query_text)
        if not patterns:
            return []
        where_clauses, parameters = self._chunk_scope_filters()
        sql_parameters: list[Any] = [
            patterns,
            *parameters,
            patterns,
            patterns,
            patterns,
            max(1, int(limit)),
        ]
        with self.connection.cursor() as cursor:
            cursor.execute(
                f"""
                SELECT c.chunk_id,
                       c.document_id,
                       c.chunk_text,
                       ce.embedding::text AS embedding_literal,
                       (
                           SELECT COUNT(*)
                           FROM unnest(%s::text[]) AS pattern(value)
                           WHERE c.chunk_text ILIKE pattern.value
                              OR COALESCE(c.section_path_text, '') ILIKE pattern.value
                              OR COALESCE(d.title, '') ILIKE pattern.value
                       ) AS technical_match_score
                FROM chunk_embeddings ce
                JOIN corpus_chunks c ON c.chunk_id = ce.chunk_id
                JOIN corpus_documents d ON d.document_id = c.document_id
                WHERE {' AND '.join(where_clauses)}
                  AND (
                      c.chunk_text ILIKE ANY(%s)
                      OR COALESCE(c.section_path_text, '') ILIKE ANY(%s)
                      OR COALESCE(d.title, '') ILIKE ANY(%s)
                  )
                ORDER BY technical_match_score DESC, c.chunk_id
                LIMIT %s
                """,
                sql_parameters,
            )
            rows = cursor.fetchall()
        return [self._chunk_row(row) for row in rows]

    def _memory_scope_filters(
        self,
        *,
        preset_filter: str | None,
        source_gate_run_id: str | None,
        strategy_filters: list[str],
    ) -> tuple[list[str], list[Any]]:
        where_clauses = [
            "m.query_embedding IS NOT NULL",
            "m.metadata ->> 'memory_experiment_key' = %s",
            "m.metadata ->> 'embedding_model' = %s",
        ]
        parameters: list[Any] = [self.memory_experiment_key, self.embedding_model]
        normalized_preset = str(preset_filter).strip() if preset_filter else None
        if normalized_preset:
            where_clauses.append(
                "COALESCE(NULLIF(m.metadata ->> 'gating_preset', ''), g.gating_preset, 'full_gating') = %s"
            )
            parameters.append(normalized_preset)
        normalized_source_run_id = str(source_gate_run_id).strip() if source_gate_run_id else None
        if normalized_source_run_id:
            where_clauses.append("m.metadata ->> 'source_gate_run_id' = %s")
            parameters.append(normalized_source_run_id)
        if strategy_filters:
            where_clauses.append("UPPER(m.generation_strategy) = ANY(%s)")
            parameters.append(strategy_filters)
        return where_clauses, parameters

    def _fetch_memory_ann_pool(
        self,
        query_embedding: list[float],
        *,
        limit: int,
        preset_filter: str | None,
        source_gate_run_id: str | None,
        strategy_filters: list[str],
    ) -> list[dict[str, Any]]:
        where_clauses, parameters = self._memory_scope_filters(
            preset_filter=preset_filter,
            source_gate_run_id=source_gate_run_id,
            strategy_filters=strategy_filters,
        )
        embedding_literal = embedding_to_halfvec_literal(query_embedding)
        parameters.extend([embedding_literal, max(1, int(limit))])
        with self.connection.cursor() as cursor:
            cursor.execute(
                f"""
                SELECT m.memory_id,
                       m.query_text,
                       m.target_doc_id,
                       m.target_chunk_ids,
                       m.generation_strategy,
                       m.product,
                       m.glossary_terms,
                       m.metadata -> 'canonical_anchors' AS canonical_anchors,
                       m.utility_score,
                       m.final_score,
                       (
                           SELECT d.title
                           FROM corpus_chunks c
                           JOIN corpus_documents d ON d.document_id = c.document_id
                           WHERE c.chunk_id = COALESCE(m.target_chunk_ids ->> 0, '')
                           LIMIT 1
                       ) AS target_title,
                       (
                           SELECT c.section_path_text
                           FROM corpus_chunks c
                           WHERE c.chunk_id = COALESCE(m.target_chunk_ids ->> 0, '')
                           LIMIT 1
                       ) AS target_section_path_text,
                       (
                           SELECT LEFT(c.chunk_text, 700)
                           FROM corpus_chunks c
                           WHERE c.chunk_id = COALESCE(m.target_chunk_ids ->> 0, '')
                           LIMIT 1
                       ) AS target_chunk_preview,
                       m.query_embedding::text AS embedding_literal,
                       1 - (m.query_embedding <=> CAST(%s AS halfvec)) AS ann_score
                FROM memory_entries m
                LEFT JOIN synthetic_queries_gated g ON g.gated_query_id = m.source_gated_query_id
                WHERE {' AND '.join(where_clauses)}
                ORDER BY m.query_embedding <=> CAST(%s AS halfvec), m.memory_id
                LIMIT %s
                """,
                [embedding_literal, *parameters],
            )
            rows = cursor.fetchall()
        return [self._memory_row(row) for row in rows]

    def _fetch_memory_lexical_pool(
        self,
        query_text: str,
        *,
        limit: int,
        preset_filter: str | None,
        source_gate_run_id: str | None,
        strategy_filters: list[str],
    ) -> list[dict[str, Any]]:
        lexical_query = str(query_text or "").strip()
        if not lexical_query:
            return []
        patterns = self._lexical_patterns(lexical_query)
        if not patterns:
            return []
        where_clauses, parameters = self._memory_scope_filters(
            preset_filter=preset_filter,
            source_gate_run_id=source_gate_run_id,
            strategy_filters=strategy_filters,
        )
        sql_parameters: list[Any] = [
            lexical_query,
            lexical_query,
            *parameters,
            patterns,
            patterns,
            max(1, int(limit)),
        ]
        with self.connection.cursor() as cursor:
            cursor.execute(
                f"""
                SELECT m.memory_id,
                       m.query_text,
                       m.target_doc_id,
                       m.target_chunk_ids,
                       m.generation_strategy,
                       m.product,
                       m.glossary_terms,
                       m.metadata -> 'canonical_anchors' AS canonical_anchors,
                       m.utility_score,
                       m.final_score,
                       (
                           SELECT d.title
                           FROM corpus_chunks c
                           JOIN corpus_documents d ON d.document_id = c.document_id
                           WHERE c.chunk_id = COALESCE(m.target_chunk_ids ->> 0, '')
                           LIMIT 1
                       ) AS target_title,
                       (
                           SELECT c.section_path_text
                           FROM corpus_chunks c
                           WHERE c.chunk_id = COALESCE(m.target_chunk_ids ->> 0, '')
                           LIMIT 1
                       ) AS target_section_path_text,
                       (
                           SELECT LEFT(c.chunk_text, 700)
                           FROM corpus_chunks c
                           WHERE c.chunk_id = COALESCE(m.target_chunk_ids ->> 0, '')
                           LIMIT 1
                       ) AS target_chunk_preview,
                       m.query_embedding::text AS embedding_literal,
                       GREATEST(
                           similarity(m.query_text, %s),
                           similarity(COALESCE(m.glossary_terms::text, ''), %s)
                       ) AS lexical_score
                FROM memory_entries m
                LEFT JOIN synthetic_queries_gated g ON g.gated_query_id = m.source_gated_query_id
                WHERE {' AND '.join(where_clauses)}
                  AND (
                      m.query_text ILIKE ANY(%s)
                      OR COALESCE(m.glossary_terms::text, '') ILIKE ANY(%s)
                  )
                ORDER BY lexical_score DESC, m.memory_id
                LIMIT %s
                """,
                sql_parameters,
            )
            rows = cursor.fetchall()
        return [self._memory_row(row) for row in rows]

    def _fetch_memory_technical_pool(
        self,
        query_text: str,
        *,
        limit: int,
        preset_filter: str | None,
        source_gate_run_id: str | None,
        strategy_filters: list[str],
    ) -> list[dict[str, Any]]:
        patterns = self._technical_patterns(query_text)
        if not patterns:
            return []
        where_clauses, parameters = self._memory_scope_filters(
            preset_filter=preset_filter,
            source_gate_run_id=source_gate_run_id,
            strategy_filters=strategy_filters,
        )
        sql_parameters: list[Any] = [
            patterns,
            *parameters,
            patterns,
            patterns,
            patterns,
            max(1, int(limit)),
        ]
        with self.connection.cursor() as cursor:
            cursor.execute(
                f"""
                SELECT m.memory_id,
                       m.query_text,
                       m.target_doc_id,
                       m.target_chunk_ids,
                       m.generation_strategy,
                       m.product,
                       m.glossary_terms,
                       m.metadata -> 'canonical_anchors' AS canonical_anchors,
                       m.utility_score,
                       m.final_score,
                       (
                           SELECT d.title
                           FROM corpus_chunks c
                           JOIN corpus_documents d ON d.document_id = c.document_id
                           WHERE c.chunk_id = COALESCE(m.target_chunk_ids ->> 0, '')
                           LIMIT 1
                       ) AS target_title,
                       (
                           SELECT c.section_path_text
                           FROM corpus_chunks c
                           WHERE c.chunk_id = COALESCE(m.target_chunk_ids ->> 0, '')
                           LIMIT 1
                       ) AS target_section_path_text,
                       (
                           SELECT LEFT(c.chunk_text, 700)
                           FROM corpus_chunks c
                           WHERE c.chunk_id = COALESCE(m.target_chunk_ids ->> 0, '')
                           LIMIT 1
                       ) AS target_chunk_preview,
                       m.query_embedding::text AS embedding_literal,
                       (
                           SELECT COUNT(*)
                           FROM unnest(%s::text[]) AS pattern(value)
                           WHERE m.query_text ILIKE pattern.value
                              OR COALESCE(m.glossary_terms::text, '') ILIKE pattern.value
                              OR COALESCE((m.metadata -> 'canonical_anchors')::text, '') ILIKE pattern.value
                       ) AS technical_match_score
                FROM memory_entries m
                LEFT JOIN synthetic_queries_gated g ON g.gated_query_id = m.source_gated_query_id
                WHERE {' AND '.join(where_clauses)}
                  AND (
                      m.query_text ILIKE ANY(%s)
                      OR COALESCE(m.glossary_terms::text, '') ILIKE ANY(%s)
                      OR COALESCE((m.metadata -> 'canonical_anchors')::text, '') ILIKE ANY(%s)
                  )
                ORDER BY technical_match_score DESC, m.memory_id
                LIMIT %s
                """,
                sql_parameters,
            )
            rows = cursor.fetchall()
        return [self._memory_row(row) for row in rows]


_REWRITE_CLIENT: LlmClient | None = None
_REWRITE_PROMPT_TEXT: str | None = None
_REWRITE_PROMPT_TEXTS: dict[str, str] = {}
REWRITE_QUERY_PROFILE_COMPACT_ANCHOR = "compact_anchor"
REWRITE_QUERY_PROFILE_DETAILED_INTENT = "detailed_intent"
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
DEFAULT_MULTI_SOURCE_ANCHOR_RELATION_VERSION = "multi-source-anchor-v1"
DEFAULT_MULTI_SOURCE_ANCHOR_TYPES = (
    "canonical_alias",
    "synthetic_query_cooccurrence",
    "chunk_cooccurrence",
)
MULTI_SOURCE_ANCHOR_TERM_TYPE_PRIORITY = {
    "product": 100,
    "artifact": 92,
    "class": 90,
    "interface": 88,
    "annotation": 86,
    "config_key": 84,
    "property": 82,
    "api": 80,
    "cli": 78,
    "concept": 35,
}
MULTI_SOURCE_ANCHOR_SEED_PRIORITY = {
    "raw_query": 100,
    "memory_canonical": 72,
    "memory_glossary": 58,
    "memory_query": 46,
}
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
            "maxItems": 2,
            "items": {
                "type": "object",
                "required": [
                    "label",
                    "query",
                ],
                "properties": {
                    "label": {"type": "string"},
                    "query": {"type": "string"},
                    "preserved_raw_terms": {
                        "type": "array",
                        "items": {"type": "string"},
                    },
                    "added_anchors": {
                        "type": "array",
                        "items": {"type": "string"},
                    },
                    "source_memory_index": {"type": "integer"},
                    "intent_risk": {
                        "type": "string",
                        "enum": ["low", "medium", "high"],
                    },
                },
                "additionalProperties": True,
            },
        },
    },
    "additionalProperties": True,
}

AGENTIC_QUERY_PLAN_RESPONSE_SCHEMA: dict[str, Any] = {
    "type": "object",
    "required": ["subqueries"],
    "properties": {
        "subqueries": {
            "type": "array",
            "minItems": 1,
            "maxItems": 4,
            "items": {
                "type": "object",
                "required": ["query"],
                "properties": {
                    "query": {"type": "string"},
                    "intent": {"type": "string"},
                    "weight": {"type": "number"},
                },
                "additionalProperties": True,
            },
        },
        "planner_notes": {"type": "string"},
    },
    "additionalProperties": True,
}
AGENTIC_QUERY_PLANNER_SYSTEM_PROMPT = (
    "You are a domain-scoped technical documentation retrieval query planner. "
    "Decompose the user's query into focused retrieval subqueries, but stay strictly "
    "inside the current technical-document domain supplied in domain_context. "
    "Return JSON only. Generate 2 to max_subqueries subqueries when the question is "
    "composite; keep each subquery concise and retrieval-oriented. Do not route to, "
    "mention, or depend on other technical domains unless the current domain_context "
    "already allows them."
)

PRODUCT_SCOPE_SUFFIXES = (
    "-reference",
    "_reference",
    "-docs",
    "_docs",
    "-doc",
    "_doc",
)
MEMORY_HINT_TOKEN_RE = re.compile(r"@[A-Za-z][A-Za-z0-9_]+|[A-Za-z][A-Za-z0-9_.:/+-]{2,}")
GENERIC_MEMORY_HINT_TOKENS = {
    "spring",
    "security",
    "framework",
    "reference",
    "docs",
    "doc",
    "guide",
    "example",
    "examples",
    "setup",
    "config",
    "configuration",
    "usage",
    "role",
    "reason",
    "difference",
    "default",
    "basic",
}

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


def _extract_memory_hint_tokens(text: str, *, language_hint: str, max_items: int) -> list[str]:
    collected: list[str] = []
    seen: set[str] = set()
    candidates = [
        *_extract_anchor_tokens(text, language_hint=language_hint, max_items=max_items * 2),
        *MEMORY_HINT_TOKEN_RE.findall(str(text or "")),
    ]
    for raw_token in candidates:
        token = _normalize_anchor_token(raw_token)
        if not token:
            continue
        folded = token.casefold()
        if folded in seen or folded in GENERIC_MEMORY_HINT_TOKENS:
            continue
        if not _is_probable_memory_hint_anchor(token):
            continue
        if not is_valid_anchor_phrase(token, language_hint=language_hint):
            continue
        seen.add(folded)
        collected.append(token)
        if len(collected) >= max_items:
            break
    return collected[:max_items]


def _is_probable_memory_hint_anchor(token: str) -> bool:
    value = _normalize_anchor_token(token)
    if not value:
        return False
    folded = value.casefold()
    if folded in GENERIC_MEMORY_HINT_TOKENS:
        return False
    if value.startswith("@"):
        return True
    if any(separator in value for separator in (".", "_", "-", "/", ":")):
        return True
    if any(char.isdigit() for char in value):
        return True
    if value.isupper() and any(char.isalpha() for char in value) and len(value) >= 4:
        return True
    return any(char.isupper() for char in value[1:]) and any(char.islower() for char in value)


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


def _is_prompt_eligible_canonical_anchor(anchor: Mapping[str, Any]) -> bool:
    if anchor.get("used_for_scoring") is not True:
        return False
    resolution_status = str(anchor.get("resolution_status") or "").strip().lower()
    if resolution_status not in {"mapped", "self_fallback"}:
        return False
    review_status = str(anchor.get("review_status") or "").strip().lower()
    if resolution_status == "mapped" and review_status != "approved":
        return False
    if resolution_status == "self_fallback" and review_status not in {"", "approved"}:
        return False
    if anchor.get("pending_candidates"):
        return False
    if not str(anchor.get("canonical_form") or "").strip():
        return False
    return True


def _canonical_hint_key(value: str) -> str:
    return unicodedata.normalize("NFKC", value).casefold()


def _build_rewrite_canonical_anchor_hints(
    *,
    memory_items: list[dict[str, Any]],
    query_language: str,
    max_terms: int = DEFAULT_REWRITE_TERMINOLOGY_HINTS_MAX,
) -> dict[str, Any]:
    bounded_max_terms = _clamp_count(
        max_terms,
        default=DEFAULT_REWRITE_TERMINOLOGY_HINTS_MAX,
        max_value=24,
    )
    terms: list[str] = []
    source_terms: list[dict[str, str]] = []
    seen: set[str] = set()

    def add_term(term: Any, *, alias: str | None, canonical_form: str | None) -> bool:
        if len(terms) >= bounded_max_terms:
            return False
        value = str(term or "").strip()
        if not value:
            return True
        if not is_valid_anchor_phrase(value, language_hint=query_language):
            return True
        dedup_key = _canonical_hint_key(value)
        if dedup_key in seen:
            return True
        seen.add(dedup_key)
        terms.append(value)
        source_term: dict[str, str] = {
            "term": value,
            "source": "canonical_anchor",
        }
        if alias:
            source_term["alias"] = alias
        if canonical_form and canonical_form != value:
            source_term["canonical_form"] = canonical_form
        source_terms.append(source_term)
        return True

    for memory_row in memory_items[:5]:
        for anchor in _iter_scoring_canonical_anchors(memory_row.get("canonical_anchors")):
            if not _is_prompt_eligible_canonical_anchor(anchor):
                continue
            display_alias = str(anchor.get("display_alias") or "").strip() or None
            canonical_form = str(anchor.get("canonical_form") or "").strip() or None
            for field_name in (
                "canonical_form",
                "display_alias",
                "normalized_alias",
                "canonical_normalized_form",
            ):
                if not add_term(
                    anchor.get(field_name),
                    alias=display_alias,
                    canonical_form=canonical_form,
                ):
                    return {
                        "terms": terms,
                        "source_terms": source_terms,
                    }

    return {
        "terms": terms,
        "source_terms": source_terms,
    }


def _normalize_relation_type_allowlist(value: Any) -> list[str]:
    if isinstance(value, str):
        raw_items = [item.strip() for item in value.split(",")]
    elif isinstance(value, (list, tuple, set)):
        raw_items = [str(item).strip() for item in value]
    else:
        raw_items = list(DEFAULT_MULTI_SOURCE_ANCHOR_TYPES)
    normalized = []
    for item in raw_items:
        relation_type = item.lower().replace("-", "_")
        if relation_type and relation_type not in normalized:
            normalized.append(relation_type)
    return normalized or list(DEFAULT_MULTI_SOURCE_ANCHOR_TYPES)


def _multi_source_term_priority(term_type: Any) -> int:
    return MULTI_SOURCE_ANCHOR_TERM_TYPE_PRIORITY.get(str(term_type or "").strip().lower(), 50)


def _build_multi_source_anchor_hints(
    *,
    raw_query: str,
    query_language: str,
    memory_items: list[dict[str, Any]],
    anchor_index: MultiSourceAnchorIndex | None,
    relation_type_allowlist: list[str] | tuple[str, ...] | None = None,
    min_relation_score: float = 0.72,
    max_per_seed: int = 2,
    max_total: int = 8,
) -> dict[str, Any]:
    allowed_relation_types = set(_normalize_relation_type_allowlist(relation_type_allowlist))
    try:
        score_floor = float(min_relation_score)
    except (TypeError, ValueError):
        score_floor = 0.72
    score_floor = max(0.0, min(1.0, score_floor))
    bounded_max_per_seed = _clamp_count(max_per_seed, default=2, max_value=8)
    bounded_max_total = _clamp_count(max_total, default=8, max_value=24)
    diagnostics: dict[str, Any] = {
        "enabled": bool(anchor_index),
        "relation_version": anchor_index.relation_version if anchor_index else DEFAULT_MULTI_SOURCE_ANCHOR_RELATION_VERSION,
        "seed_anchor_count": 0,
        "candidate_expanded_anchor_count": 0,
        "accepted_expanded_anchor_count": 0,
        "anchor_dedup_rate": 0.0,
        "relation_source_distribution": {},
        "relation_type_distribution": {},
        "filtered": {
            "missing_index": 0,
            "score_below_threshold": 0,
            "relation_type_blocked": 0,
            "duplicate": 0,
            "raw_query_overlap": 0,
            "invalid_phrase": 0,
            "concept_low_score": 0,
        },
    }
    if not anchor_index:
        diagnostics["filtered"]["missing_index"] = 1
        return {
            "priority": "low",
            "policy": "Expanded anchors are optional hints and must never override raw query intent.",
            "terms": [],
            "anchors": [],
            "diagnostics": diagnostics,
        }

    raw_query_keys = _anchor_lookup_keys(raw_query)
    for token in _extract_anchor_tokens(raw_query, language_hint=query_language, max_items=16):
        raw_query_keys.update(_anchor_lookup_keys(token))
    seed_rows: list[dict[str, Any]] = []
    seed_ids: set[str] = set()
    raw_seed_ids: set[str] = set()

    def add_seed_by_id(canonical_id: Any, source: str, display: Any = None) -> None:
        seed_id = str(canonical_id or "").strip()
        if not seed_id:
            return
        if seed_id not in anchor_index.terms_by_id and seed_id not in anchor_index.relations_by_anchor_id:
            return
        key = f"{source}:{seed_id}"
        if any(row["key"] == key for row in seed_rows):
            return
        seed_rows.append(
            {
                "key": key,
                "canonical_anchor_id": seed_id,
                "source": source,
                "display": str(display or "").strip(),
                "priority": MULTI_SOURCE_ANCHOR_SEED_PRIORITY.get(source, 40),
            }
        )
        seed_ids.add(seed_id)
        if source == "raw_query":
            raw_seed_ids.add(seed_id)

    def add_seed_by_alias(alias: Any, source: str) -> None:
        for key in _anchor_lookup_keys(alias):
            for canonical_id in anchor_index.alias_to_canonical_ids.get(key, []):
                add_seed_by_id(canonical_id, source, alias)

    for token in _extract_anchor_tokens(raw_query, language_hint=query_language, max_items=8):
        add_seed_by_alias(token, "raw_query")

    for memory_row in memory_items[:5]:
        for anchor in _iter_scoring_canonical_anchors(memory_row.get("canonical_anchors")):
            add_seed_by_id(
                anchor.get("canonical_term_id"),
                "memory_canonical",
                anchor.get("canonical_form") or anchor.get("display_alias"),
            )
        glossary_terms = memory_row.get("glossary_terms")
        if isinstance(glossary_terms, list):
            for term in glossary_terms[:8]:
                add_seed_by_alias(term, "memory_glossary")
        for token in _extract_anchor_tokens(
            str(memory_row.get("query_text") or ""),
            language_hint=query_language,
            max_items=5,
        ):
            add_seed_by_alias(token, "memory_query")

    seed_rows.sort(key=lambda row: (-int(row["priority"]), str(row["display"]).casefold()))
    diagnostics["seed_anchor_count"] = len({row["canonical_anchor_id"] for row in seed_rows})
    selected_related_ids: set[str] = set()
    anchors: list[dict[str, Any]] = []
    terms: list[str] = []
    candidate_count = 0

    for seed in seed_rows:
        accepted_for_seed = 0
        seed_id = str(seed["canonical_anchor_id"])
        relations = anchor_index.relations_by_anchor_id.get(seed_id, [])
        ranked_relations = sorted(
            relations,
            key=lambda row: (
                -float(row.get("relation_score") or 0.0),
                -_multi_source_term_priority(row.get("term_type")),
                -int(row.get("evidence_count") or 0),
                str(row.get("canonical_form") or "").casefold(),
            ),
        )
        for relation in ranked_relations:
            if len(anchors) >= bounded_max_total or accepted_for_seed >= bounded_max_per_seed:
                break
            candidate_count += 1
            relation_score = float(relation.get("relation_score") or 0.0)
            relation_type = str(relation.get("relation_type") or "").strip().lower()
            if relation_score < score_floor:
                diagnostics["filtered"]["score_below_threshold"] += 1
                continue
            if relation_type not in allowed_relation_types:
                diagnostics["filtered"]["relation_type_blocked"] += 1
                continue
            related_id = str(relation.get("related_anchor_id") or "").strip()
            if not related_id or related_id in seed_ids or related_id in selected_related_ids:
                diagnostics["filtered"]["duplicate"] += 1
                continue
            term = str(relation.get("canonical_form") or "").strip()
            if not term:
                diagnostics["filtered"]["invalid_phrase"] += 1
                continue
            if any(key in raw_query_keys for key in _anchor_lookup_keys(term)):
                diagnostics["filtered"]["raw_query_overlap"] += 1
                continue
            if not is_valid_anchor_phrase(term, language_hint=query_language):
                diagnostics["filtered"]["invalid_phrase"] += 1
                continue
            term_type = str(relation.get("term_type") or "").strip()
            if term_type == "concept" and relation_score < max(score_floor, 0.82):
                diagnostics["filtered"]["concept_low_score"] += 1
                continue
            selected_related_ids.add(related_id)
            terms.append(term)
            relation_source = str(relation.get("relation_source") or "")
            diagnostics["relation_source_distribution"][relation_source] = (
                int(diagnostics["relation_source_distribution"].get(relation_source, 0)) + 1
            )
            diagnostics["relation_type_distribution"][relation_type] = (
                int(diagnostics["relation_type_distribution"].get(relation_type, 0)) + 1
            )
            anchor_payload = {
                "term": term,
                "normalized_form": str(relation.get("normalized_form") or ""),
                "term_type": term_type,
                "relation_type": relation_type,
                "relation_score": round(relation_score, 4),
                "relation_source": relation_source,
                "evidence_count": int(relation.get("evidence_count") or 0),
                "seed_source": str(seed.get("source") or ""),
                "priority": "low",
            }
            if relation.get("method_code"):
                anchor_payload["method_code"] = str(relation["method_code"])
            anchors.append(anchor_payload)
            accepted_for_seed += 1

    diagnostics["candidate_expanded_anchor_count"] = candidate_count
    diagnostics["accepted_expanded_anchor_count"] = len(anchors)
    diagnostics["anchor_dedup_rate"] = (
        1.0 - (len(anchors) / candidate_count)
        if candidate_count > 0
        else 0.0
    )
    return {
        "priority": "low",
        "relation_version": anchor_index.relation_version,
        "policy": "Expanded anchors are optional hints only. Raw-query anchors and explicit user intent have higher priority.",
        "terms": terms,
        "anchors": anchors,
        "diagnostics": diagnostics,
    }


def build_memory_guided_query(
    raw_query: str,
    memory_items: list[dict[str, Any]],
    *,
    query_language: str = "ko",
    max_hint_tokens: int = 3,
) -> str:
    normalized_raw_query = str(raw_query or "").strip()
    if not normalized_raw_query:
        return normalized_raw_query
    if not memory_items:
        return normalized_raw_query

    bounded_hint_count = _clamp_count(max_hint_tokens, default=3, max_value=6)
    raw_query_folded = normalized_raw_query.casefold()
    raw_anchor_set = {
        token.casefold()
        for token in _extract_memory_hint_tokens(
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
        if not _is_probable_memory_hint_anchor(normalized):
            return None
        if not is_valid_anchor_phrase(normalized, language_hint=query_language):
            return None
        folded = normalized.casefold()
        if folded in raw_anchor_set:
            return None
        if len(normalized) > 40:
            return None
        if any(marker in normalized for marker in ("{", "}", "(", ")", ";", "=", "$")):
            return None
        return normalized

    for index, memory in enumerate(memory_items[:5]):
        rank_weight = 1.0 / float(index + 1)
        product_phrase = _product_hint_phrase(str(memory.get("product") or ""))
        if product_phrase and product_phrase.casefold() not in raw_query_folded:
            product_scores[product_phrase] = product_scores.get(product_phrase, 0.0) + (2.0 * rank_weight)

        query_text = str(memory.get("query_text") or "")
        for token in _extract_memory_hint_tokens(
            query_text,
            language_hint=query_language,
            max_items=7,
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
                   m.glossary_terms,
                   m.metadata -> 'canonical_anchors' AS canonical_anchors,
                   m.utility_score,
                   m.final_score,
                   (
                       SELECT d.title
                       FROM corpus_chunks c
                       JOIN corpus_documents d ON d.document_id = c.document_id
                       WHERE c.chunk_id = COALESCE(m.target_chunk_ids ->> 0, '')
                       LIMIT 1
                   ) AS target_title,
                   (
                       SELECT c.section_path_text
                       FROM corpus_chunks c
                       WHERE c.chunk_id = COALESCE(m.target_chunk_ids ->> 0, '')
                       LIMIT 1
                   ) AS target_section_path_text,
                   (
                       SELECT LEFT(c.chunk_text, 700)
                       FROM corpus_chunks c
                       WHERE c.chunk_id = COALESCE(m.target_chunk_ids ->> 0, '')
                       LIMIT 1
                   ) AS target_chunk_preview
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
            canonical_anchors=_json_mapping_or_none(row["canonical_anchors"]),
            target_title=str(row["target_title"]).strip() if row["target_title"] else None,
            target_section_path=(
                str(row["target_section_path_text"]).strip()
                if row["target_section_path_text"]
                else None
            ),
            target_chunk_preview=str(row["target_chunk_preview"]).strip() if row["target_chunk_preview"] else None,
            utility_score=float(row["utility_score"]) if row["utility_score"] is not None else None,
            final_score=float(row["final_score"]) if row["final_score"] is not None else None,
        )
        for row in rows
    ]


def _anchor_lookup_key(value: Any) -> str:
    text = str(value or "").strip()
    if not text:
        return ""
    return unicodedata.normalize("NFKC", text).casefold()


def _anchor_lookup_keys(value: Any) -> set[str]:
    keys: set[str] = set()
    text = str(value or "").strip()
    if not text:
        return keys
    for candidate in (text, normalize_anchor_text(text)):
        key = _anchor_lookup_key(candidate)
        if key:
            keys.add(key)
    return keys


def _append_alias_mapping(
    alias_to_canonical_ids: dict[str, list[str]],
    alias_value: Any,
    canonical_id: str,
) -> None:
    canonical_id = str(canonical_id or "").strip()
    if not canonical_id:
        return
    for key in _anchor_lookup_keys(alias_value):
        bucket = alias_to_canonical_ids.setdefault(key, [])
        if canonical_id not in bucket:
            bucket.append(canonical_id)


def _table_exists(connection: psycopg.Connection[Any], table_name: str) -> bool:
    with connection.cursor() as cursor:
        cursor.execute("SELECT to_regclass(%s) IS NOT NULL AS exists", (f"public.{table_name}",))
        row = cursor.fetchone()
    return bool(row and row.get("exists"))


def load_multi_source_anchor_index(
    connection: psycopg.Connection[Any],
    *,
    relation_version: str = DEFAULT_MULTI_SOURCE_ANCHOR_RELATION_VERSION,
    relation_types: list[str] | tuple[str, ...] | None = None,
    min_relation_score: float = 0.72,
) -> MultiSourceAnchorIndex | None:
    normalized_relation_version = str(relation_version or DEFAULT_MULTI_SOURCE_ANCHOR_RELATION_VERSION).strip()
    if not normalized_relation_version:
        normalized_relation_version = DEFAULT_MULTI_SOURCE_ANCHOR_RELATION_VERSION
    try:
        min_score = float(min_relation_score)
    except (TypeError, ValueError):
        min_score = 0.72
    min_score = max(0.0, min(1.0, min_score))
    normalized_types = [
        str(item).strip().lower().replace("-", "_")
        for item in (relation_types or DEFAULT_MULTI_SOURCE_ANCHOR_TYPES)
        if str(item).strip()
    ]
    if not normalized_types:
        normalized_types = list(DEFAULT_MULTI_SOURCE_ANCHOR_TYPES)

    if not _table_exists(connection, "canonical_anchor_relation"):
        return None

    terms_by_id: dict[str, dict[str, Any]] = {}
    alias_to_canonical_ids: dict[str, list[str]] = {}
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT term_id,
                   canonical_form,
                   normalized_form,
                   term_type
            FROM corpus_glossary_terms
            WHERE is_active = TRUE
            """
        )
        for row in cursor.fetchall():
            term_id = str(row["term_id"])
            term = {
                "canonical_anchor_id": term_id,
                "canonical_form": str(row["canonical_form"] or ""),
                "normalized_form": str(row["normalized_form"] or ""),
                "term_type": str(row["term_type"] or ""),
            }
            terms_by_id[term_id] = term
            _append_alias_mapping(alias_to_canonical_ids, term["canonical_form"], term_id)
            _append_alias_mapping(alias_to_canonical_ids, term["normalized_form"], term_id)

        cursor.execute(
            """
            SELECT a.term_id,
                   a.alias_text
            FROM corpus_glossary_aliases a
            JOIN corpus_glossary_terms t ON t.term_id = a.term_id
            WHERE t.is_active = TRUE
            """
        )
        for row in cursor.fetchall():
            _append_alias_mapping(alias_to_canonical_ids, row["alias_text"], str(row["term_id"]))

        if _table_exists(connection, "canonical_anchor_mapping"):
            cursor.execute(
                """
                SELECT canonical_term_id,
                       alias_text,
                       normalized_alias,
                       display_alias
                FROM canonical_anchor_mapping
                WHERE mapping_version = 'anchor-map-v1'
                  AND normalization_version = 'anchor-normalize-v1'
                  AND review_status = 'approved'
                  AND mapping_status = 'active'
                """
            )
            for row in cursor.fetchall():
                canonical_id = str(row["canonical_term_id"])
                _append_alias_mapping(alias_to_canonical_ids, row["alias_text"], canonical_id)
                _append_alias_mapping(alias_to_canonical_ids, row["normalized_alias"], canonical_id)
                _append_alias_mapping(alias_to_canonical_ids, row["display_alias"], canonical_id)

        cursor.execute(
            """
            SELECT r.canonical_anchor_id,
                   r.related_anchor_id,
                   r.relation_type,
                   r.relation_score,
                   r.relation_source,
                   r.evidence_count,
                   r.source_query_id,
                   r.source_chunk_id,
                   r.source_section_id,
                   r.method_code,
                   r.metadata_json,
                   related.canonical_form AS related_canonical_form,
                   related.normalized_form AS related_normalized_form,
                   related.term_type AS related_term_type
            FROM canonical_anchor_relation r
            JOIN corpus_glossary_terms related
              ON related.term_id = r.related_anchor_id
             AND related.is_active = TRUE
            WHERE r.relation_version = %s
              AND r.status = 'active'
              AND r.relation_score >= %s
              AND r.relation_type = ANY(%s)
            ORDER BY r.canonical_anchor_id,
                     r.relation_score DESC,
                     r.evidence_count DESC,
                     related.canonical_form
            """,
            (normalized_relation_version, min_score, normalized_types),
        )
        relation_rows = cursor.fetchall()

    relations_by_anchor_id: dict[str, list[dict[str, Any]]] = {}
    for row in relation_rows:
        seed_id = str(row["canonical_anchor_id"])
        related_id = str(row["related_anchor_id"])
        relations_by_anchor_id.setdefault(seed_id, []).append(
            {
                "canonical_anchor_id": seed_id,
                "related_anchor_id": related_id,
                "canonical_form": str(row["related_canonical_form"] or ""),
                "normalized_form": str(row["related_normalized_form"] or ""),
                "term_type": str(row["related_term_type"] or ""),
                "relation_type": str(row["relation_type"] or ""),
                "relation_score": float(row["relation_score"] or 0.0),
                "relation_source": str(row["relation_source"] or ""),
                "evidence_count": int(row["evidence_count"] or 0),
                "source_query_id": str(row["source_query_id"]) if row.get("source_query_id") else None,
                "source_chunk_id": str(row["source_chunk_id"]) if row.get("source_chunk_id") else None,
                "source_section_id": str(row["source_section_id"]) if row.get("source_section_id") else None,
                "method_code": str(row["method_code"]) if row.get("method_code") else None,
                "metadata": _json_mapping_or_none(row.get("metadata_json")) or {},
            }
        )

    if not relations_by_anchor_id:
        return None
    return MultiSourceAnchorIndex(
        relation_version=normalized_relation_version,
        alias_to_canonical_ids=alias_to_canonical_ids,
        terms_by_id=terms_by_id,
        relations_by_anchor_id=relations_by_anchor_id,
    )


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


def _canonical_lexical_query_text(query_text: str, canonical_payloads: Any) -> str:
    return build_canonical_lexical_text(
        query_text,
        canonical_payloads,
        match_text=query_text,
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
        lexical_texts=[
            build_canonical_lexical_text(memory.query_text, memory.canonical_anchors)
            for memory in eligible
        ],
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
    retrieval_adapter: DbAnnRuntimeRetrievalAdapter | None = None,
    query_canonical_anchors: Any | None = None,
) -> list[RetrievalCandidate]:
    if retrieval_adapter is not None:
        return retrieval_adapter.retrieve_top_k(
            query_text,
            top_k=top_k,
            query_canonical_anchors=query_canonical_anchors,
        )
    if not chunks:
        return []
    config = retriever_config or build_retriever_config({})
    candidate_pool_k = max(top_k, min(config.candidate_pool_k, max(top_k, len(chunks))))
    retriever = _get_chunk_retriever(chunks=chunks, config=config)
    lexical_query_text = _canonical_lexical_query_text(query_text, query_canonical_anchors)
    ranked_pool = retriever.rank(
        query_text,
        top_k=candidate_pool_k,
        lexical_query_text=lexical_query_text,
    )
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


def runtime_retriever_label(
    *,
    retriever_config: RetrieverConfig | None = None,
    retrieval_adapter: DbAnnRuntimeRetrievalAdapter | None = None,
) -> str:
    if retrieval_adapter is not None:
        return retrieval_adapter.retriever_name
    return local_retriever_label(retriever_config)


def runtime_retriever_metadata(
    *,
    retriever_config: RetrieverConfig | None = None,
    retrieval_adapter: DbAnnRuntimeRetrievalAdapter | None = None,
) -> dict[str, Any]:
    if retrieval_adapter is not None:
        return retrieval_adapter.metadata()
    config = retriever_config or build_retriever_config({})
    retriever_name = local_retriever_label(config)
    fallback_used = config.requires_dense and "hash-embedding-v1" in retriever_name
    return {
        "retrieval_backend": RETRIEVAL_BACKEND_LOCAL,
        "embedding_model": (
            "hash-embedding-v1"
            if fallback_used
            else str(config.dense_embedding_model).strip()
            if config.mode in {RETRIEVAL_MODE_DENSE_ONLY, RETRIEVAL_MODE_HYBRID}
            else None
        ),
        "vector_store": None,
        "fallback_used": fallback_used,
        "retriever_name": retriever_name,
        "retriever_config": config.to_metadata(),
    }


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
    retrieval_adapter: DbAnnRuntimeRetrievalAdapter | None = None,
) -> list[dict[str, Any]]:
    if retrieval_adapter is not None:
        return retrieval_adapter.memory_top_n(
            query_text,
            top_n=top_n,
            preset_filter=preset_filter,
            source_gate_run_id=source_gate_run_id,
            strategy_filters=strategy_filters,
        )
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
    lexical_query_text = _canonical_lexical_query_text(
        query_text,
        [memory.canonical_anchors for memory in eligible],
    )
    for ranked in retriever.rank(
        query_text,
        top_k=top_n,
        lexical_query_text=lexical_query_text,
    ):
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
                "canonical_anchors": memory.canonical_anchors,
                "target_title": memory.target_title,
                "target_section_path": memory.target_section_path,
                "target_chunk_preview": memory.target_chunk_preview,
                "utility_score": memory.utility_score,
                "final_score": memory.final_score,
                "similarity": ranked.score,
                "dense_similarity": ranked.dense_score,
                "bm25_score": ranked.bm25_score,
                "technical_token_overlap": ranked.technical_score,
                "retriever": retriever.retriever_name,
            }
        )
    return scored


def _normalize_rewrite_query_profile(value: str | None) -> str:
    normalized = str(value or "").strip().lower().replace("-", "_")
    if normalized == REWRITE_QUERY_PROFILE_DETAILED_INTENT:
        return REWRITE_QUERY_PROFILE_DETAILED_INTENT
    return REWRITE_QUERY_PROFILE_COMPACT_ANCHOR


def _rewrite_prompt_text(
    *,
    query_language: str = "ko",
    rewrite_query_profile: str = REWRITE_QUERY_PROFILE_COMPACT_ANCHOR,
) -> str:
    global _REWRITE_PROMPT_TEXT, _REWRITE_PROMPT_TEXTS
    if _REWRITE_PROMPT_TEXT is not None:
        return _REWRITE_PROMPT_TEXT
    normalized_language = "en" if str(query_language or "").strip().lower() == "en" else "ko"
    normalized_profile = _normalize_rewrite_query_profile(rewrite_query_profile)
    cache_key = f"{normalized_language}:{normalized_profile}"
    cached = _REWRITE_PROMPT_TEXTS.get(cache_key)
    if cached is not None:
        return cached
    root = Path(os.getenv("PROMPT_ROOT") or "configs/prompts")
    if normalized_profile == REWRITE_QUERY_PROFILE_DETAILED_INTENT:
        candidates = [
            root / "rewrite" / "selective_rewrite_detailed_intent_v1.md",
            Path("configs/prompts/rewrite/selective_rewrite_detailed_intent_v1.md"),
            Path("../configs/prompts/rewrite/selective_rewrite_detailed_intent_v1.md"),
        ]
    else:
        candidates = []
    if normalized_language == "en":
        candidates.extend(
            [
                root / "rewrite" / "selective_rewrite_en_v1.md",
                root / "rewrite" / "selective_rewrite_v2.md",
                root / "rewrite" / "selective_rewrite_v1.md",
                Path("configs/prompts/rewrite/selective_rewrite_en_v1.md"),
                Path("configs/prompts/rewrite/selective_rewrite_v2.md"),
                Path("configs/prompts/rewrite/selective_rewrite_v1.md"),
                Path("../configs/prompts/rewrite/selective_rewrite_en_v1.md"),
                Path("../configs/prompts/rewrite/selective_rewrite_v2.md"),
                Path("../configs/prompts/rewrite/selective_rewrite_v1.md"),
            ]
        )
    else:
        candidates.extend(
            [
                root / "rewrite" / "selective_rewrite_v3.md",
                root / "rewrite" / "selective_rewrite_v2.md",
                root / "rewrite" / "selective_rewrite_v1.md",
                Path("configs/prompts/rewrite/selective_rewrite_v3.md"),
                Path("configs/prompts/rewrite/selective_rewrite_v2.md"),
                Path("configs/prompts/rewrite/selective_rewrite_v1.md"),
                Path("../configs/prompts/rewrite/selective_rewrite_v3.md"),
                Path("../configs/prompts/rewrite/selective_rewrite_v2.md"),
                Path("../configs/prompts/rewrite/selective_rewrite_v1.md"),
            ]
        )
    for path in candidates:
        if path.exists():
            prompt_text = path.read_text(encoding="utf-8")
            _REWRITE_PROMPT_TEXTS[cache_key] = prompt_text
            return prompt_text
    if normalized_profile == REWRITE_QUERY_PROFILE_DETAILED_INTENT:
        raise FileNotFoundError(
            "rewrite prompt file not found: selective_rewrite_detailed_intent_v1.md or compact fallback prompt"
        )
    if normalized_language == "en":
        raise FileNotFoundError(
            "rewrite prompt file not found: selective_rewrite_en_v1.md, selective_rewrite_v2.md, or selective_rewrite_v1.md"
        )
    raise FileNotFoundError("rewrite prompt file not found: selective_rewrite_v3.md, selective_rewrite_v2.md, or selective_rewrite_v1.md")


def _rewrite_client(raw_config: dict[str, Any] | None = None) -> LlmClient:
    if raw_config:
        return LlmClient(load_stage_config(stage="rewrite", raw_config=raw_config))
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
) -> list[dict[str, Any]]:
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
) -> list[dict[str, Any]]:
    trace_id = f"rewrite:{hashlib.sha1(raw_query.encode('utf-8')).hexdigest()[:12]}"
    limited_candidate_count = max(1, min(int(candidate_count or 1), 2))
    payload: dict[str, Any] = {
        "raw_query": raw_query,
        "session_context": session_context,
        "top_memory_candidates": _memory_prompt_candidates(memory_items),
        "candidate_count": limited_candidate_count,
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
                candidate_count=limited_candidate_count,
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
        try:
            source_memory_index = int(item.get("source_memory_index") or 0)
        except (TypeError, ValueError):
            source_memory_index = 0
        intent_risk = str(item.get("intent_risk") or "medium").strip().lower()
        if intent_risk not in {"low", "medium", "high"}:
            intent_risk = "medium"
        normalized.append(
            {
                "label": label,
                "query": query,
                "preserved_raw_terms": _bounded_string_list(item.get("preserved_raw_terms"), max_items=12),
                "added_anchors": _bounded_string_list(item.get("added_anchors"), max_items=12),
                "source_memory_index": max(0, source_memory_index),
                "intent_risk": intent_risk,
            }
        )
        if len(normalized) >= limited_candidate_count:
            break
    if normalized:
        return normalized
    fallback_allowed = str(os.getenv("QUERY_FORGE_ALLOW_HEURISTIC_REWRITE_FALLBACK") or "").lower() == "true"
    if fallback_allowed:
        return _heuristic_rewrite_candidates(
            raw_query,
            memory_items,
            session_context=session_context,
            candidate_count=limited_candidate_count,
        )
    raise RuntimeError("LLM rewrite candidate response was empty.")


def _heuristic_rewrite_candidates_v2(
    raw_query: str,
    memory_items: list[dict[str, Any]],
    *,
    session_context: dict[str, Any],
    candidate_count: int,
    query_language: str,
) -> list[dict[str, Any]]:
    previous_entity = str(session_context.get("previous_assistant_summary") or "").strip()
    previous_question = str(session_context.get("previous_user_question") or "").strip()
    standalone_parts = [previous_question, raw_query, previous_entity]
    standalone_query = " ".join(part for part in standalone_parts if part).strip() or raw_query
    templates: list[dict[str, Any]] = [
        {
            "label": "standalone",
            "query": standalone_query,
            "source_memory_index": 0,
            "intent_risk": "low",
            "added_anchors": [],
        }
    ]
    if candidate_count > 1 and memory_items:
        expanded_query = build_memory_guided_query(
            standalone_query,
            memory_items,
            query_language=query_language,
            max_hint_tokens=3,
        )
        normalized_standalone = " ".join(standalone_query.split()).casefold()
        normalized_expanded = " ".join(expanded_query.split()).casefold()
        if normalized_expanded and normalized_expanded != normalized_standalone:
            try:
                source_memory_index = int(memory_items[0].get("source_memory_index") or 1)
            except (TypeError, ValueError):
                source_memory_index = 1
            templates.append(
                {
                    "label": "expanded",
                    "query": expanded_query,
                    "source_memory_index": max(1, source_memory_index),
                    "intent_risk": "medium",
                    "added_anchors": [],
                }
            )
    return templates[:candidate_count]


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


def _rewrite_domain_context(source_product: str | None) -> dict[str, Any]:
    product = str(source_product or "").strip()
    normalized = _normalize_product_scope_key(product)
    domain = product or "technical documentation"
    aliases: list[str] = []
    examples: list[dict[str, str]] = [
        {"ko": "트랜잭션", "en": "Transaction"},
        {"ko": "어노테이션", "en": "Annotation"},
        {"ko": "설정", "en": "Configuration"},
        {"ko": "인증", "en": "Authentication"},
        {"ko": "권한", "en": "Authorization"},
    ]
    if "spring" in normalized:
        domain = "Spring"
        aliases = ["Spring Framework", "Spring Boot", "Spring Security", "Spring Data"]
        examples.extend(
            [
                {"ko": "보안", "en": "Spring Security"},
                {"ko": "메서드 보안", "en": "Method Security"},
                {"ko": "저장소", "en": "Repository"},
                {"ko": "자바 영속성", "en": "JPA"},
                {"ko": "표현식 언어", "en": "SpEL"},
                {"ko": "여러 부분 요청", "en": "Multipart request"},
            ]
        )
    elif "postgres" in normalized or "postgis" in normalized:
        domain = "PostgreSQL"
        aliases = ["PostgreSQL", "Postgres", "PostGIS"]
        examples.extend(
            [
                {"ko": "트랜잭션", "en": "Transaction"},
                {"ko": "현재 트랜잭션 커밋", "en": "COMMIT current transaction"},
                {"ko": "저장점", "en": "SAVEPOINT"},
                {"ko": "되돌리기", "en": "ROLLBACK"},
                {"ko": "잘라내기", "en": "TRUNCATE"},
            ]
        )
    elif "kubernetes" in normalized or normalized == "k8s":
        domain = "Kubernetes"
        aliases = ["Kubernetes", "kubectl", "k8s"]
        examples.extend(
            [
                {"ko": "준비 상태 검사", "en": "readiness probe"},
                {"ko": "생존 검사", "en": "liveness probe"},
                {"ko": "파드", "en": "Pod"},
                {"ko": "배포", "en": "Deployment"},
                {"ko": "서비스", "en": "Service"},
            ]
        )
    elif "python" in normalized:
        domain = "Python"
        aliases = ["Python", "CPython", "Python standard library"]
        examples.extend(
            [
                {"ko": "반복자", "en": "Iterator"},
                {"ko": "문맥 관리자", "en": "Context Manager"},
                {"ko": "비동기", "en": "asyncio"},
                {"ko": "예외", "en": "Exception"},
            ]
        )
    deduped_examples: list[dict[str, str]] = []
    seen: set[tuple[str, str]] = set()
    for example in examples:
        key = (example["ko"].casefold(), example["en"].casefold())
        if key in seen:
            continue
        seen.add(key)
        deduped_examples.append(example)
        if len(deduped_examples) >= 12:
            break
    return {
        "current_technical_domain": domain,
        "source_product": product,
        "domain_aliases": aliases,
        "rewrite_instruction": (
            f"The current technical-document domain is {domain}. "
            "Interpret the user's Korean technical words inside this domain, "
            "then rewrite key technical terms into standard English documentation terms."
        ),
        "ko_to_en_term_examples": deduped_examples,
    }


def build_rewrite_candidates_v2(
    raw_query: str,
    memory_items: list[dict[str, Any]],
    *,
    session_context: dict[str, Any],
    candidate_count: int,
    query_language: str,
    rewrite_anchor_injection_enabled: bool = True,
    rewrite_terminology_hints_max_count: int = DEFAULT_REWRITE_TERMINOLOGY_HINTS_MAX,
    multi_source_anchor_hints: dict[str, Any] | None = None,
    retrieval_context: dict[str, Any] | None = None,
    raw_retrieval_context: list[dict[str, Any]] | None = None,
    domain_context: dict[str, Any] | None = None,
    rewrite_query_profile: str = REWRITE_QUERY_PROFILE_COMPACT_ANCHOR,
    rewrite_failure_policy: str | None = None,
    rewrite_runtime_stats: dict[str, int] | None = None,
    trusted_memory_items: list[dict[str, Any]] | None = None,
    raw_config: dict[str, Any] | None = None,
) -> list[dict[str, str]]:
    trace_id = f"rewrite:{hashlib.sha1(raw_query.encode('utf-8')).hexdigest()[:12]}"
    limited_candidate_count = max(1, min(int(candidate_count or 1), 2))
    normalized_rewrite_query_profile = _normalize_rewrite_query_profile(rewrite_query_profile)
    failure_policy = _normalize_rewrite_failure_policy(rewrite_failure_policy)
    fallback_allowed = failure_policy == "heuristic_fallback"
    _bump_rewrite_runtime_stat(rewrite_runtime_stats, "llm_attempted_count")
    rewrite_client: LlmClient | None = None
    expanded_memory_items = list(memory_items if trusted_memory_items is None else trusted_memory_items)
    pure_rewrite_latency_ms = 0.0
    standalone_source_text = f"{raw_query} {session_context}".strip()
    standalone_evidence_text = json.dumps(
        list(raw_retrieval_context or [])[:3],
        ensure_ascii=False,
    )
    standalone_forbidden_terms = [
        term
        for term in _build_rewrite_anchor_candidates(
            raw_query="",
            query_language=query_language,
            memory_items=memory_items,
        ).get("anchor_terms", [])
        if str(term).strip()
        and not _term_present_in_text(str(term), standalone_source_text)
        and not _term_present_in_text(str(term), standalone_evidence_text)
    ]

    def _standalone_query_without_memory_anchors(query: str) -> str:
        for term in standalone_forbidden_terms:
            if _term_present_in_text(str(term), query):
                return raw_query
        return query

    def _handle_failure(error: Exception) -> list[dict[str, str]]:
        _bump_rewrite_runtime_stat(rewrite_runtime_stats, "llm_failure_count")
        if rewrite_runtime_stats is not None:
            rewrite_runtime_stats["pure_rewrite_latency_ms"] = pure_rewrite_latency_ms
        if fallback_allowed:
            _bump_rewrite_runtime_stat(rewrite_runtime_stats, "heuristic_fallback_count")
            return _heuristic_rewrite_candidates_v2(
                raw_query,
                expanded_memory_items,
                session_context=session_context,
                candidate_count=limited_candidate_count,
                query_language=query_language,
            )
        if failure_policy == "skip_to_raw":
            return []
        raise error

    def _build_payload(
        *,
        mode: str,
        prompt_memory_items: list[dict[str, Any]],
        output_label: str,
    ) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "raw_query": raw_query,
            "query_language": query_language,
            "session_context": session_context,
            "domain_context": domain_context or _rewrite_domain_context(None),
            "top_memory_candidates": _memory_prompt_candidates(prompt_memory_items),
            "raw_retrieval_context": list(raw_retrieval_context or [])[:3],
            "rewrite_query_profile": normalized_rewrite_query_profile,
            "candidate_count": 1,
            "candidate_policy": {
                "mode": mode,
                "output_label": output_label,
                "rewrite_query_profile": normalized_rewrite_query_profile,
                "raw_query_is_source_of_truth": True,
                "memory_allowed": mode == "memory_expanded",
                "anchor_hints_allowed": mode == "memory_expanded" and rewrite_anchor_injection_enabled,
                "do_not_add_product_or_api_terms_unless_present_in_raw_or_allowed_inputs": True,
            },
        }
        if mode == "raw_standalone":
            payload["top_memory_candidates"] = []
        if retrieval_context:
            payload["retrieval_context"] = retrieval_context
        if mode == "memory_expanded" and rewrite_anchor_injection_enabled and prompt_memory_items:
            anchor_context = _build_rewrite_anchor_candidates(
                raw_query=raw_query,
                query_language=query_language,
                memory_items=prompt_memory_items,
            )
            payload["anchor_candidates"] = anchor_context["anchors"]
            payload["anchor_terms"] = anchor_context["anchor_terms"]
            payload["terminology_hints"] = _build_rewrite_terminology_hints(
                raw_query=raw_query,
                query_language=query_language,
                memory_items=prompt_memory_items,
                max_terms=rewrite_terminology_hints_max_count,
            )
            canonical_anchor_hints = _build_rewrite_canonical_anchor_hints(
                memory_items=prompt_memory_items,
                query_language=query_language,
                max_terms=rewrite_terminology_hints_max_count,
            )
            if canonical_anchor_hints["terms"]:
                payload["canonical_anchor_hints"] = canonical_anchor_hints
            if multi_source_anchor_hints and multi_source_anchor_hints.get("terms"):
                payload["multi_source_anchor_hints"] = multi_source_anchor_hints
        return payload

    def _chat_rewrite(payload: dict[str, Any], *, trace_suffix: str) -> list[Any]:
        nonlocal pure_rewrite_latency_ms, rewrite_client
        llm_started = time.perf_counter()
        _bump_rewrite_runtime_stat(rewrite_runtime_stats, "llm_call_count")
        try:
            if rewrite_client is None:
                rewrite_client = _rewrite_client(raw_config=raw_config)
            response = rewrite_client.chat_json(
                system_prompt=_rewrite_prompt_text(
                    query_language=query_language,
                    rewrite_query_profile=normalized_rewrite_query_profile,
                ),
                user_prompt=json.dumps(
                    payload,
                    ensure_ascii=False,
                    indent=2,
                ),
                response_schema=REWRITE_RESPONSE_SCHEMA,
                request_purpose="selective_rewrite",
                trace_id=f"{trace_id}:{trace_suffix}",
            )
        finally:
            pure_rewrite_latency_ms += (time.perf_counter() - llm_started) * 1000.0
        candidate_rows = response.get("candidates")
        if not isinstance(candidate_rows, list):
            raise RuntimeError("LLM rewrite response must contain `candidates` list.")
        return candidate_rows

    def _normalize_candidate_rows(
        candidate_rows: list[Any],
        *,
        label: str,
        max_items: int,
        default_source_memory_index: int = 0,
        anchor_fields_allowed: bool,
    ) -> list[dict[str, str]]:
        normalized: list[dict[str, str]] = []
        for item in candidate_rows:
            if not isinstance(item, dict):
                continue
            query = str(item.get("query") or "").strip()
            if not query:
                continue
            if not anchor_fields_allowed:
                query = _standalone_query_without_memory_anchors(query).strip()
                if not query:
                    continue
            try:
                source_memory_index = int(item.get("source_memory_index") or default_source_memory_index)
            except (TypeError, ValueError):
                source_memory_index = default_source_memory_index
            if not anchor_fields_allowed:
                source_memory_index = 0
            intent_risk = str(item.get("intent_risk") or "medium").strip().lower()
            if intent_risk not in {"low", "medium", "high"}:
                intent_risk = "medium"
            normalized.append(
                {
                    "label": label,
                    "query": query,
                    "preserved_raw_terms": _bounded_string_list(item.get("preserved_raw_terms"), max_items=12),
                    "added_anchors": (
                        _bounded_string_list(item.get("added_anchors"), max_items=12)
                        if anchor_fields_allowed
                        else []
                    ),
                    "source_memory_index": max(0, source_memory_index),
                    "intent_risk": intent_risk,
                }
            )
            if len(normalized) >= max_items:
                break
        return normalized

    candidates: list[dict[str, str]] = []
    try:
        standalone_rows = _chat_rewrite(
            _build_payload(mode="raw_standalone", prompt_memory_items=[], output_label="standalone"),
            trace_suffix="standalone",
        )
    except Exception as exception:
        return _handle_failure(exception)
    candidates.extend(
        _normalize_candidate_rows(
            standalone_rows,
            label="standalone",
            max_items=1,
            default_source_memory_index=0,
            anchor_fields_allowed=False,
        )
    )
    if not candidates:
        return _handle_failure(RuntimeError("LLM standalone rewrite candidate response was empty."))

    if limited_candidate_count > 1 and expanded_memory_items:
        default_source_memory_index = 0
        try:
            default_source_memory_index = int(expanded_memory_items[0].get("source_memory_index") or 0)
        except (TypeError, ValueError):
            default_source_memory_index = 0
        try:
            expanded_rows = _chat_rewrite(
                _build_payload(
                    mode="memory_expanded",
                    prompt_memory_items=expanded_memory_items,
                    output_label="expanded",
                ),
                trace_suffix="expanded",
            )
        except Exception as exception:
            if failure_policy == "fail_run":
                return _handle_failure(exception)
            _bump_rewrite_runtime_stat(rewrite_runtime_stats, "llm_failure_count")
            expanded_rows = []
        candidates.extend(
            _normalize_candidate_rows(
                expanded_rows,
                label="expanded",
                max_items=limited_candidate_count - len(candidates),
                default_source_memory_index=default_source_memory_index,
                anchor_fields_allowed=rewrite_anchor_injection_enabled,
            )
        )

    if rewrite_runtime_stats is not None:
        rewrite_runtime_stats["pure_rewrite_latency_ms"] = pure_rewrite_latency_ms
    if candidates:
        _bump_rewrite_runtime_stat(rewrite_runtime_stats, "llm_success_count")
        return candidates[:limited_candidate_count]
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


def retrieval_confidence_score(retrieval: list[RetrievalCandidate]) -> float:
    if not retrieval:
        return 0.0
    top1 = max(0.0, min(1.0, (retrieval[0].score + 1.0) / 2.0))
    top3_items = retrieval[:3]
    top3 = sum(
        max(0.0, min(1.0, (item.score + 1.0) / 2.0))
        for item in top3_items
    ) / max(1, len(top3_items))
    return (0.70 * top1) + (0.30 * top3)


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


def _iter_scoring_canonical_anchors(payload: Any) -> list[Mapping[str, Any]]:
    canonical_payload = _json_mapping_or_none(payload)
    if not canonical_payload:
        return []
    anchors = canonical_payload.get("anchors")
    if not isinstance(anchors, list):
        return []
    scoring_anchors: list[Mapping[str, Any]] = []
    for anchor in anchors:
        if not isinstance(anchor, Mapping):
            continue
        if anchor.get("used_for_scoring") is not True:
            continue
        canonical_term_id = str(anchor.get("canonical_term_id") or "").strip()
        canonical_form = str(anchor.get("canonical_form") or "").strip()
        if not canonical_term_id and not canonical_form:
            continue
        scoring_anchors.append(anchor)
    return scoring_anchors


def _canonical_anchor_group_key(anchor: Mapping[str, Any]) -> str:
    canonical_term_id = str(anchor.get("canonical_term_id") or "").strip()
    if canonical_term_id:
        return f"id:{canonical_term_id}"
    canonical_form = str(anchor.get("canonical_form") or "").strip().casefold()
    return f"term:{canonical_form}"


def _add_canonical_variant(variants: set[str], value: Any) -> None:
    text = str(value or "").strip()
    if not text:
        return
    variants.add(text)


def _collect_scoring_canonical_anchor_groups(memory_items: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    groups: dict[str, dict[str, Any]] = {}
    for memory_row in memory_items[:5]:
        for anchor in _iter_scoring_canonical_anchors(memory_row.get("canonical_anchors")):
            key = _canonical_anchor_group_key(anchor)
            if key.endswith("term:"):
                continue
            group = groups.setdefault(
                key,
                {
                    "canonical_term_id": str(anchor.get("canonical_term_id") or "").strip() or None,
                    "canonical_term": str(anchor.get("canonical_form") or "").strip() or None,
                    "variants": set(),
                },
            )
            if not group.get("canonical_term") and anchor.get("canonical_form"):
                group["canonical_term"] = str(anchor.get("canonical_form")).strip()
            for field_name in (
                "input_alias",
                "display_alias",
                "normalized_alias",
                "canonical_form",
                "canonical_normalized_form",
            ):
                _add_canonical_variant(group["variants"], anchor.get(field_name))
    return {key: value for key, value in groups.items() if value.get("variants")}


def _canonical_match_indexes(text: str) -> tuple[str, str]:
    normalized = unicodedata.normalize("NFKC", str(text or "")).casefold()
    normalized = normalized.replace("_", " ").replace("-", " ")
    spaced = re.sub(r"[^0-9a-z@\uac00-\ud7a3]+", " ", normalized)
    spaced = re.sub(r"\s+", " ", spaced).strip()
    compact = re.sub(r"[^0-9a-z@\uac00-\ud7a3]+", "", normalized)
    return spaced, compact


def _canonical_text_contains(text: str, variant: str) -> bool:
    variant_spaced, variant_compact = _canonical_match_indexes(variant)
    if not variant_compact:
        return False
    text_spaced, text_compact = _canonical_match_indexes(text)
    if variant_spaced and f" {variant_spaced} " in f" {text_spaced} ":
        return True
    is_phrase = " " in variant_spaced
    has_hangul = bool(re.search(r"[\uac00-\ud7a3]", variant_compact))
    if (is_phrase or has_hangul or variant_compact.startswith("@")) and variant_compact in text_compact:
        return True
    return False


def _canonical_anchor_hits(text: str, groups: dict[str, dict[str, Any]]) -> set[str]:
    hits: set[str] = set()
    for key, group in groups.items():
        variants = group.get("variants")
        if not isinstance(variants, set):
            continue
        if any(_canonical_text_contains(text, variant) for variant in variants):
            hits.add(key)
    return hits


def _canonical_hit_values(
    hit_keys: set[str],
    groups: dict[str, dict[str, Any]],
    field_name: str,
) -> list[str]:
    values: list[str] = []
    for key in sorted(hit_keys):
        value = groups.get(key, {}).get(field_name)
        if value:
            values.append(str(value))
    return values


def _canonical_memory_target_texts(memory_row: dict[str, Any]) -> list[str]:
    groups = _collect_scoring_canonical_anchor_groups([memory_row])
    texts: list[str] = []
    seen: set[str] = set()
    for group in groups.values():
        canonical_term = str(group.get("canonical_term") or "").strip()
        if canonical_term and canonical_term.casefold() not in seen:
            seen.add(canonical_term.casefold())
            texts.append(canonical_term)
        variants = group.get("variants")
        if not isinstance(variants, set):
            continue
        for variant in sorted(variants):
            folded = variant.casefold()
            if folded in seen:
                continue
            seen.add(folded)
            texts.append(variant)
    return texts


def _bounded_string_list(value: Any, *, max_items: int = 12, max_chars: int = 80) -> list[str]:
    if not isinstance(value, list):
        return []
    rows: list[str] = []
    seen: set[str] = set()
    for item in value:
        text = " ".join(str(item or "").split())
        if not text:
            continue
        text = text[:max_chars].strip()
        key = text.casefold()
        if key in seen:
            continue
        seen.add(key)
        rows.append(text)
        if len(rows) >= max_items:
            break
    return rows


def _memory_utility_score(memory_row: dict[str, Any]) -> float:
    for key in ("utility_score", "final_score", "gating_utility_score"):
        value = memory_row.get(key)
        if value is None:
            continue
        try:
            return _clamp01(float(value))
        except (TypeError, ValueError):
            continue
    return 0.0


def _product_match_score(memory_product: Any, source_product: str | None) -> float:
    memory_value = _normalize_product_scope_key(str(memory_product or ""))
    source_value = _normalize_product_scope_key(str(source_product or ""))
    if not memory_value or not source_value:
        return 0.0
    memory_aliases = _expand_source_product_aliases(memory_value)
    source_aliases = _expand_source_product_aliases(source_value)
    if memory_aliases & source_aliases:
        return 1.0
    if any(left in right or right in left for left in memory_aliases for right in source_aliases):
        return 0.5
    return 0.0


def _memory_anchor_tokens(memory_row: dict[str, Any], *, query_language: str) -> set[str]:
    tokens = _content_token_set(str(memory_row.get("query_text") or ""), max_items=18)
    tokens |= _technical_token_set(
        str(memory_row.get("query_text") or ""),
        query_language=query_language,
        max_items=12,
    )
    for term in memory_row.get("glossary_terms") or []:
        tokens |= _content_token_set(str(term), max_items=8)
        tokens |= _technical_token_set(str(term), query_language=query_language, max_items=4)
        if len(tokens) >= 32:
            break
    for canonical_text in _canonical_memory_target_texts(memory_row):
        tokens |= _content_token_set(str(canonical_text), max_items=8)
        tokens |= _technical_token_set(str(canonical_text), query_language=query_language, max_items=4)
        if len(tokens) >= 40:
            break
    for key in ("target_title", "target_section_path"):
        tokens |= _content_token_set(str(memory_row.get(key) or ""), max_items=10)
        if len(tokens) >= 48:
            break
    return tokens


def _raw_anchor_tokens(raw_query: str, *, query_language: str) -> set[str]:
    return _content_token_set(raw_query, max_items=18) | _technical_token_set(
        raw_query,
        query_language=query_language,
        max_items=18,
    )


def _memory_rerank_features(
    *,
    memory_row: dict[str, Any],
    raw_query: str,
    raw_retrieval: list[RetrievalCandidate],
    query_language: str,
    source_product: str | None,
) -> dict[str, Any]:
    target_chunk_ids = {
        str(item).strip()
        for item in (memory_row.get("target_chunk_ids") or [])
        if str(item).strip()
    }
    raw_chunk_ids = [item.chunk_id for item in raw_retrieval[:10] if item.chunk_id]
    raw_doc_ids = [item.document_id for item in raw_retrieval[:10] if item.document_id]
    raw_chunk_set = set(raw_chunk_ids)
    raw_doc_set = set(raw_doc_ids)
    target_doc_id = str(memory_row.get("target_doc_id") or "").strip()
    chunk_overlap_count = len(target_chunk_ids & raw_chunk_set)
    chunk_overlap_score = _safe_ratio(chunk_overlap_count, max(1, min(len(target_chunk_ids), len(raw_chunk_set))))
    doc_overlap_score = 1.0 if target_doc_id and target_doc_id in raw_doc_set else 0.0
    raw_tokens = _raw_anchor_tokens(raw_query, query_language=query_language)
    memory_tokens = _memory_anchor_tokens(memory_row, query_language=query_language)
    anchor_overlap_score = _safe_ratio(len(raw_tokens & memory_tokens), len(raw_tokens), default=0.0)
    utility_score = _memory_utility_score(memory_row)
    product_match_score = _product_match_score(memory_row.get("product"), source_product)
    raw_similarity = _clamp01((float(memory_row.get("similarity") or 0.0) + 1.0) / 2.0)
    rerank_score = _clamp01(
        (0.34 * raw_similarity)
        + (0.20 * chunk_overlap_score)
        + (0.12 * doc_overlap_score)
        + (0.18 * anchor_overlap_score)
        + (0.10 * utility_score)
        + (0.06 * product_match_score)
    )
    return {
        "raw_similarity_norm": round(raw_similarity, 6),
        "raw_topk_chunk_overlap_count": chunk_overlap_count,
        "raw_topk_chunk_overlap_score": round(chunk_overlap_score, 6),
        "raw_topk_doc_overlap_score": round(doc_overlap_score, 6),
        "canonical_anchor_overlap_score": round(anchor_overlap_score, 6),
        "gating_utility_score": round(utility_score, 6),
        "product_domain_match_score": round(product_match_score, 6),
        "rerank_score": round(rerank_score, 6),
    }


def _rerank_rewrite_memory_candidates(
    *,
    raw_query: str,
    memory_items: list[dict[str, Any]],
    raw_retrieval: list[RetrievalCandidate],
    query_language: str,
    source_product: str | None,
    top_n: int,
) -> list[dict[str, Any]]:
    if top_n <= 0 or not memory_items:
        return []
    scored: list[dict[str, Any]] = []
    for index, memory_row in enumerate(memory_items, start=1):
        row = dict(memory_row)
        features = _memory_rerank_features(
            memory_row=row,
            raw_query=raw_query,
            raw_retrieval=raw_retrieval,
            query_language=query_language,
            source_product=source_product,
        )
        row["memory_rank_before"] = index
        row["memory_rerank_features"] = features
        row["memory_rerank_score"] = features["rerank_score"]
        scored.append(row)
    scored.sort(
        key=lambda row: (
            -float(row.get("memory_rerank_score") or 0.0),
            -float((row.get("memory_rerank_features") or {}).get("raw_topk_chunk_overlap_score") or 0.0),
            -float((row.get("memory_rerank_features") or {}).get("raw_topk_doc_overlap_score") or 0.0),
            int(row.get("memory_rank_before") or 9999),
            str(row.get("memory_id") or ""),
        )
    )
    for index, row in enumerate(scored, start=1):
        row["memory_rank_after"] = index
    return scored[:top_n]


def _short_evidence_summary(memory_row: dict[str, Any]) -> str:
    for key in ("target_chunk_preview", "target_section_path", "target_title"):
        text = " ".join(str(memory_row.get(key) or "").split())
        if text:
            return text[:260].strip()
    return " ".join(str(memory_row.get("query_text") or "").split())[:180].strip()


def _memory_prompt_candidates(memory_items: list[dict[str, Any]]) -> list[dict[str, Any]]:
    prompt_rows: list[dict[str, Any]] = []
    for index, memory_row in enumerate(memory_items[:5], start=1):
        try:
            source_memory_index = int(memory_row.get("source_memory_index") or index)
        except (TypeError, ValueError):
            source_memory_index = index
        prompt_rows.append(
            {
                "source_memory_index": source_memory_index,
                "synthetic_query": str(memory_row.get("query_text") or ""),
                "target_title": str(memory_row.get("target_title") or ""),
                "section_path": str(memory_row.get("target_section_path") or ""),
                "glossary_terms": _bounded_string_list(memory_row.get("glossary_terms"), max_items=8),
                "canonical_anchors": _bounded_string_list(
                    _canonical_memory_target_texts(memory_row),
                    max_items=8,
                ),
                "short_evidence_summary": _short_evidence_summary(memory_row),
            }
        )
    return prompt_rows


def _raw_retrieval_prompt_candidates(
    raw_retrieval: list[RetrievalCandidate],
    *,
    query_language: str,
    max_items: int = 3,
) -> list[dict[str, Any]]:
    prompt_rows: list[dict[str, Any]] = []
    for index, item in enumerate(raw_retrieval[:max_items], start=1):
        text = " ".join(str(item.text or "").split())
        section_match = re.search(r"Section Path:\s*([^`]+?)(?:\s{2,}|$)", text)
        section_path = section_match.group(1).strip()[:180] if section_match else ""
        prompt_rows.append(
            {
                "rank": index,
                "score": round(float(item.score), 6),
                "section_path": section_path,
                "technical_terms": _extract_memory_hint_tokens(
                    text,
                    language_hint=query_language,
                    max_items=10,
                ),
                "text_preview": text[:420].strip(),
            }
        )
    return prompt_rows


def _trusted_rewrite_memory_items(memory_items: list[dict[str, Any]]) -> list[dict[str, Any]]:
    trusted_rows: list[dict[str, Any]] = []
    for index, memory_row in enumerate(memory_items, start=1):
        features = memory_row.get("memory_rerank_features") if isinstance(memory_row, dict) else None
        if not isinstance(features, dict):
            continue
        try:
            chunk_overlap_count = int(features.get("raw_topk_chunk_overlap_count") or 0)
        except (TypeError, ValueError):
            chunk_overlap_count = 0
        try:
            chunk_overlap_score = float(features.get("raw_topk_chunk_overlap_score") or 0.0)
        except (TypeError, ValueError):
            chunk_overlap_score = 0.0
        try:
            doc_overlap_score = float(features.get("raw_topk_doc_overlap_score") or 0.0)
        except (TypeError, ValueError):
            doc_overlap_score = 0.0
        if chunk_overlap_count <= 0 and chunk_overlap_score <= 0.0 and doc_overlap_score <= 0.0:
            continue
        trusted_row = dict(memory_row)
        trusted_row["source_memory_index"] = index
        trusted_rows.append(trusted_row)
    return trusted_rows


def _rewrite_retrieval_context(
    *,
    retriever_config: RetrieverConfig,
    retrieval_adapter: DbAnnRuntimeRetrievalAdapter | None,
    retrieval_top_k: int,
    memory_candidate_pool_n: int,
    memory_top_n_value: int,
) -> dict[str, Any]:
    adapter_metadata = retrieval_adapter.metadata() if retrieval_adapter is not None else {}
    backend = str(adapter_metadata.get("retrieval_backend") or RETRIEVAL_BACKEND_LOCAL)
    vector_store = adapter_metadata.get("vector_store")
    if not vector_store and backend == RETRIEVAL_BACKEND_LOCAL:
        vector_store = "in_memory_local"
    retriever_name = adapter_metadata.get("retriever_name")
    if not retriever_name:
        retriever_name = (
            f"local:{retriever_config.mode}"
            if retriever_config.mode == RETRIEVAL_MODE_BM25_ONLY
            else f"local:{retriever_config.mode}:{retriever_config.dense_embedding_model}"
        )
    weights = retriever_config.fusion_weights()
    return {
        "retrieval_backend": backend,
        "vector_store": vector_store,
        "retriever_name": retriever_name,
        "retriever_mode": retriever_config.mode,
        "dense_embedding_model": str(retriever_config.dense_embedding_model),
        "dense_embedding_required": bool(retriever_config.dense_embedding_required),
        "dense_fallback_enabled": bool(retriever_config.dense_fallback_enabled),
        "dense_embedding_device": str(retriever_config.dense_embedding_device),
        "candidate_pool_k": int(retriever_config.candidate_pool_k),
        "retrieval_top_k": int(retrieval_top_k),
        "memory_candidate_pool_n": int(memory_candidate_pool_n),
        "top_memory_candidates_count": int(memory_top_n_value),
        "rerank_enabled": bool(retriever_config.rerank_enabled),
        "fusion_weights": {
            "dense": float(weights[0]),
            "bm25": float(weights[1]),
            "technical": float(weights[2]),
        },
        "rewrite_guidance": (
            "Choose a compact query form that matches this retriever configuration. "
            "Dense-heavy retrieval benefits from intent-complete semantic phrases; "
            "BM25/technical-heavy retrieval benefits from exact anchors and canonical terms; "
            "hybrid retrieval needs both."
        ),
    }


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
    for canonical_text in _canonical_memory_target_texts(top_memory):
        memory_target_tokens |= _content_token_set(str(canonical_text), max_items=8)
        if len(memory_target_tokens) >= 24:
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


def _term_present_in_text(term: str, text: str) -> bool:
    normalized_term = normalize_anchor_text(term)
    if not normalized_term:
        return False
    normalized_text = unicodedata.normalize("NFKC", str(text or "")).casefold()
    if normalized_term.casefold() in normalized_text:
        return True
    term_spaced, term_compact = _canonical_match_indexes(normalized_term)
    text_spaced, text_compact = _canonical_match_indexes(text)
    return bool(
        (term_spaced and term_spaced in text_spaced)
        or (term_compact and term_compact in text_compact)
    )


def _llm_anchor_coverage_metrics(
    *,
    raw_query: str,
    candidate_query: str,
    preserved_raw_terms: Any,
    added_anchors: Any,
) -> dict[str, Any]:
    preserved_terms = _bounded_string_list(preserved_raw_terms, max_items=12)
    added_terms = _bounded_string_list(added_anchors, max_items=12)
    preserved_terms_from_raw = [
        term for term in preserved_terms if _term_present_in_text(term, raw_query)
    ]
    preserved_terms_in_candidate = [
        term for term in preserved_terms_from_raw if _term_present_in_text(term, candidate_query)
    ]
    added_terms_in_candidate = [
        term for term in added_terms if _term_present_in_text(term, candidate_query)
    ]
    return {
        "preserved_raw_terms": preserved_terms,
        "added_anchors": added_terms,
        "preserved_raw_terms_from_raw_count": len(preserved_terms_from_raw),
        "preserved_raw_terms_in_candidate_count": len(preserved_terms_in_candidate),
        "preserved_raw_term_coverage_ratio": _safe_ratio(
            len(preserved_terms_in_candidate),
            len(preserved_terms_from_raw),
            default=1.0,
        ),
        "added_anchor_coverage_count": len(added_terms_in_candidate),
        "added_anchor_coverage_ratio": _safe_ratio(
            len(added_terms_in_candidate),
            len(added_terms),
            default=1.0,
        ),
    }


def _source_memory_target_retrieval_metrics(
    *,
    source_memory_index: int,
    memory_items: list[dict[str, Any]],
    raw_retrieval: list[RetrievalCandidate],
    candidate_retrieval: list[RetrievalCandidate],
) -> dict[str, Any]:
    if source_memory_index <= 0:
        return {
            "source_memory_index_valid": True,
            "source_memory_target_chunk_hit": False,
            "source_memory_target_doc_hit": False,
            "source_memory_target_improved": False,
        }
    if source_memory_index > len(memory_items):
        return {
            "source_memory_index_valid": False,
            "source_memory_target_chunk_hit": False,
            "source_memory_target_doc_hit": False,
            "source_memory_target_improved": False,
        }
    memory_row = memory_items[source_memory_index - 1]
    target_chunks = {
        str(item).strip()
        for item in (memory_row.get("target_chunk_ids") or [])
        if str(item).strip()
    }
    target_doc = str(memory_row.get("target_doc_id") or "").strip()
    raw_chunks = {item.chunk_id for item in raw_retrieval[:10]}
    raw_docs = {item.document_id for item in raw_retrieval[:10]}
    candidate_chunks = {item.chunk_id for item in candidate_retrieval[:10]}
    candidate_docs = {item.document_id for item in candidate_retrieval[:10]}
    raw_chunk_hit = bool(target_chunks & raw_chunks)
    candidate_chunk_hit = bool(target_chunks & candidate_chunks)
    raw_doc_hit = bool(target_doc and target_doc in raw_docs)
    candidate_doc_hit = bool(target_doc and target_doc in candidate_docs)
    return {
        "source_memory_index_valid": True,
        "source_memory_target_chunk_hit": candidate_chunk_hit,
        "source_memory_target_doc_hit": candidate_doc_hit,
        "source_memory_target_improved": bool(
            (candidate_chunk_hit and not raw_chunk_hit)
            or (candidate_doc_hit and not raw_doc_hit)
        ),
    }


def _terminology_preservation_metrics(
    *,
    raw_query: str,
    candidate_query: str,
    query_language: str,
    raw_anchor_terms: list[str],
    canonical_anchor_groups: dict[str, dict[str, Any]] | None = None,
) -> dict[str, Any]:
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
    raw_technical_preservation_ratio = technical_preservation_ratio

    raw_anchor_set = {str(item).casefold() for item in raw_anchor_terms if str(item).strip()}
    anchor_overlap_ratio = (
        _safe_ratio(len(raw_anchor_set & candidate_tokens), len(raw_anchor_set), default=1.0)
        if raw_anchor_set
        else technical_preservation_ratio
    )
    raw_anchor_overlap_ratio = anchor_overlap_ratio

    canonical_groups = canonical_anchor_groups or {}
    canonical_raw_hits = _canonical_anchor_hits(raw_query, canonical_groups) if canonical_groups else set()
    canonical_candidate_hits = _canonical_anchor_hits(candidate_query, canonical_groups) if canonical_groups else set()
    canonical_preserved_hits = canonical_raw_hits & canonical_candidate_hits
    canonical_anchor_overlap_ratio = (
        _safe_ratio(len(canonical_preserved_hits), len(canonical_raw_hits), default=1.0)
        if canonical_raw_hits
        else 0.0
    )
    if canonical_raw_hits:
        technical_preservation_ratio = max(technical_preservation_ratio, canonical_anchor_overlap_ratio)
        anchor_overlap_ratio = max(anchor_overlap_ratio, canonical_anchor_overlap_ratio)

    terminology_preservation_score = _clamp01((0.70 * technical_preservation_ratio) + (0.30 * anchor_overlap_ratio))
    if canonical_raw_hits:
        terminology_preservation_score = max(terminology_preservation_score, canonical_anchor_overlap_ratio)
    return {
        "raw_technical_token_count": float(raw_token_count),
        "preserved_technical_token_count": float(preserved_count),
        "raw_technical_preservation_ratio": raw_technical_preservation_ratio,
        "raw_anchor_overlap_ratio": raw_anchor_overlap_ratio,
        "technical_preservation_ratio": technical_preservation_ratio,
        "anchor_overlap_ratio": anchor_overlap_ratio,
        "canonical_anchor_overlap_ratio": canonical_anchor_overlap_ratio,
        "canonical_anchor_raw_count": float(len(canonical_raw_hits)),
        "canonical_anchor_preserved_count": float(len(canonical_preserved_hits)),
        "canonical_anchor_term_ids": _canonical_hit_values(
            canonical_raw_hits,
            canonical_groups,
            "canonical_term_id",
        ),
        "canonical_anchor_terms": _canonical_hit_values(
            canonical_raw_hits,
            canonical_groups,
            "canonical_term",
        ),
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


def _raw_retrieval_loss_metrics(
    raw_retrieval: list[RetrievalCandidate],
    candidate_retrieval: list[RetrievalCandidate],
    *,
    raw_retrieval_score: float,
    confidence_floor: float,
    min_overlap_ratio: float,
    require_top1_loss: bool = True,
    top_n: int = 5,
) -> dict[str, Any]:
    raw_ids = _top_chunk_ids(raw_retrieval, top_n=top_n)
    candidate_ids = _top_chunk_ids(candidate_retrieval, top_n=top_n)
    raw_set = set(raw_ids)
    candidate_set = set(candidate_ids)
    raw_top1 = raw_ids[0] if raw_ids else ""
    top1_preserved = bool(raw_top1 and raw_top1 in candidate_set)
    overlap_ratio = (
        _safe_ratio(len(raw_set & candidate_set), len(raw_set), default=1.0)
        if raw_set
        else 1.0
    )
    triggered = bool(
        raw_set
        and raw_retrieval_score >= confidence_floor
        and (not require_top1_loss or not top1_preserved)
        and overlap_ratio < min_overlap_ratio
    )
    return {
        "raw_loss_guard_triggered": triggered,
        "raw_loss_guard_reason": "raw_loss_guard_top1_lost" if triggered else "",
        "raw_loss_guard_raw_top1_preserved": top1_preserved,
        "raw_loss_guard_topk_overlap_ratio": overlap_ratio,
        "raw_loss_guard_confidence_floor": confidence_floor,
        "raw_loss_guard_min_overlap_ratio": min_overlap_ratio,
        "raw_loss_guard_require_top1_loss": require_top1_loss,
    }


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
    # Legacy/ablation-only helper. Default rewrite evaluation selects either
    # raw retrieval or one rewritten-query retrieval result without merging.
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


def _memory_hint_retrieval(
    *,
    raw_query: str,
    query_language: str,
    memory_items: list[dict[str, Any]],
    chunks: list[ChunkItem],
    top_k: int,
    retriever_config: RetrieverConfig,
    retrieval_adapter: DbAnnRuntimeRetrievalAdapter | None,
    query_canonical_anchors: Any | None,
    max_hint_tokens: int,
) -> tuple[str | None, list[RetrievalCandidate], bool]:
    # Legacy/ablation-only helper. The default selective rewrite path must not
    # call this because synthetic memory is prompt context, not a retrieval query.
    if not memory_items:
        return None, [], False
    hint_query = build_memory_guided_query(
        raw_query,
        memory_items,
        query_language=query_language,
        max_hint_tokens=max_hint_tokens,
    )
    normalized_raw_query = " ".join(str(raw_query or "").split()).casefold()
    normalized_hint_query = " ".join(str(hint_query or "").split()).casefold()
    if not hint_query.strip() or normalized_hint_query == normalized_raw_query:
        return hint_query, [], False
    retrieval = retrieve_top_k(
        hint_query,
        chunks,
        top_k=top_k,
        retriever_config=retriever_config,
        retrieval_adapter=retrieval_adapter,
        query_canonical_anchors=query_canonical_anchors,
    )
    return hint_query, retrieval, bool(retrieval)


def _fallback_agentic_query_plan(
    *,
    raw_query: str,
    reason: str,
    planner_latency_ms: float,
) -> AgenticQueryPlan:
    return AgenticQueryPlan(
        original_query=raw_query,
        subqueries=[
            AgenticSubquery(
                index=1,
                query=raw_query,
                intent="original_query_fallback",
                weight=1.0,
            )
        ],
        fallback_applied=True,
        fallback_reason=reason,
        planner_model="fallback-original-query",
        planner_latency_ms=planner_latency_ms,
        metadata={},
    )


def _agentic_query_plan_payload(plan: AgenticQueryPlan) -> dict[str, Any]:
    return {
        "original_query": plan.original_query,
        "fallback_applied": plan.fallback_applied,
        "fallback_reason": plan.fallback_reason,
        "planner_model": plan.planner_model,
        "planner_latency_ms": plan.planner_latency_ms,
        "subqueries": [
            {
                "index": subquery.index,
                "query": subquery.query,
                "intent": subquery.intent,
                "weight": subquery.weight,
            }
            for subquery in plan.subqueries
        ],
        "metadata": plan.metadata,
    }


def _normalize_agentic_subqueries(
    payload: Any,
    *,
    max_subqueries: int,
) -> list[AgenticSubquery]:
    rows: list[Any]
    if isinstance(payload, Mapping):
        raw_rows = payload.get("subqueries")
        if raw_rows is None:
            raw_rows = payload.get("queries")
        rows = raw_rows if isinstance(raw_rows, list) else []
    elif isinstance(payload, list):
        rows = payload
    else:
        rows = []

    normalized: list[AgenticSubquery] = []
    seen: set[str] = set()
    for row in rows:
        if isinstance(row, str):
            query = row.strip()
            intent = "subquery"
            weight = 1.0
        elif isinstance(row, Mapping):
            query = str(row.get("query") or row.get("text") or "").strip()
            intent = str(row.get("intent") or "subquery").strip() or "subquery"
            try:
                weight = float(row.get("weight") or 1.0)
            except (TypeError, ValueError):
                weight = 1.0
        else:
            continue
        if not query:
            continue
        key = " ".join(query.split()).casefold()
        if key in seen:
            continue
        seen.add(key)
        normalized.append(
            AgenticSubquery(
                index=len(normalized) + 1,
                query=query,
                intent=intent[:120],
                weight=max(0.0, min(weight, 1.0)),
            )
        )
        if len(normalized) >= max_subqueries:
            break
    return normalized


def build_agentic_query_plan(
    *,
    raw_query: str,
    query_language: str,
    session_context: dict[str, Any],
    source_product: str | None,
    memory_hints: list[dict[str, Any]],
    max_subqueries: int,
    raw_config: dict[str, Any] | None = None,
) -> AgenticQueryPlan:
    started = time.perf_counter()
    bounded_max_subqueries = _clamp_count(max_subqueries, default=3, max_value=4)
    try:
        client = _rewrite_client(raw_config=raw_config)
        domain_context = _rewrite_domain_context(source_product)
        payload = {
            "raw_query": raw_query,
            "query_language": query_language,
            "session_context": session_context or {},
            "domain_context": domain_context,
            "max_subqueries": bounded_max_subqueries,
            "scope_policy": {
                "single_domain_only": True,
                "cross_domain_routing_allowed": False,
                "final_answer_not_requested": True,
            },
            "top_memory_hints": _memory_prompt_candidates(memory_hints)[:5],
        }
        response = client.chat_json(
            system_prompt=AGENTIC_QUERY_PLANNER_SYSTEM_PROMPT,
            user_prompt=json.dumps(payload, ensure_ascii=False, indent=2),
            response_schema=AGENTIC_QUERY_PLAN_RESPONSE_SCHEMA,
            request_purpose="agentic_query_planner",
            trace_id=f"agentic-plan:{hashlib.sha1(raw_query.encode('utf-8')).hexdigest()[:12]}",
        )
        subqueries = _normalize_agentic_subqueries(
            response,
            max_subqueries=bounded_max_subqueries,
        )
        planner_latency_ms = (time.perf_counter() - started) * 1000.0
        if not subqueries:
            return _fallback_agentic_query_plan(
                raw_query=raw_query,
                reason="empty_subqueries",
                planner_latency_ms=planner_latency_ms,
            )
        meta = response.get("_llm_meta") if isinstance(response, Mapping) else None
        planner_model = None
        if isinstance(meta, Mapping):
            planner_model = str(meta.get("model") or "").strip() or None
        return AgenticQueryPlan(
            original_query=raw_query,
            subqueries=subqueries,
            fallback_applied=False,
            fallback_reason=None,
            planner_model=planner_model,
            planner_latency_ms=planner_latency_ms,
            metadata={
                "planner_notes": str(response.get("planner_notes") or "").strip()
                if isinstance(response, Mapping)
                else "",
                "llm_meta": dict(meta) if isinstance(meta, Mapping) else {},
            },
        )
    except Exception as exception:  # noqa: BLE001 - planner must fail closed to original query.
        planner_latency_ms = (time.perf_counter() - started) * 1000.0
        return _fallback_agentic_query_plan(
            raw_query=raw_query,
            reason=f"{type(exception).__name__}: {str(exception)[:240]}",
            planner_latency_ms=planner_latency_ms,
        )


def rrf_merge_retrieval_results(
    result_sets: list[list[RetrievalCandidate]],
    *,
    top_k: int,
    rrf_k: int = 60,
) -> list[RetrievalCandidate]:
    bounded_top_k = max(1, int(top_k or 1))
    bounded_rrf_k = max(1, int(rrf_k or 60))
    by_chunk: dict[str, dict[str, Any]] = {}
    for retrieval in result_sets:
        seen_in_result: set[str] = set()
        for rank, row in enumerate(retrieval, start=1):
            chunk_id = str(row.chunk_id or "").strip()
            if not chunk_id or chunk_id in seen_in_result:
                continue
            seen_in_result.add(chunk_id)
            entry = by_chunk.setdefault(
                chunk_id,
                {
                    "representative": row,
                    "rrf_score": 0.0,
                    "best_original_score": row.score,
                },
            )
            entry["rrf_score"] = float(entry["rrf_score"]) + (1.0 / (bounded_rrf_k + rank))
            if row.score > float(entry.get("best_original_score", row.score)):
                entry["representative"] = row
                entry["best_original_score"] = row.score

    ranked = sorted(
        by_chunk.values(),
        key=lambda entry: (
            -float(entry["rrf_score"]),
            -float(entry.get("best_original_score", 0.0)),
            str(entry["representative"].chunk_id),
        ),
    )
    merged: list[RetrievalCandidate] = []
    for entry in ranked[:bounded_top_k]:
        representative = entry["representative"]
        merged.append(
            RetrievalCandidate(
                chunk_id=representative.chunk_id,
                document_id=representative.document_id,
                score=float(entry["rrf_score"]),
                text=representative.text,
            )
        )
    return merged


def run_agentic_multi_query(
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
    rewrite_anchor_injection_enabled: bool = True,
    rewrite_terminology_hints_max_count: int = DEFAULT_REWRITE_TERMINOLOGY_HINTS_MAX,
    multi_source_anchor_expansion_enabled: bool = False,
    multi_source_anchor_index: MultiSourceAnchorIndex | None = None,
    multi_source_anchor_relation_types: list[str] | tuple[str, ...] | None = None,
    multi_source_anchor_min_score: float = 0.72,
    multi_source_anchor_max_per_seed: int = 2,
    multi_source_anchor_max_total: int = 8,
    rewrite_query_profile: str = REWRITE_QUERY_PROFILE_COMPACT_ANCHOR,
    rewrite_failure_policy: str | None = None,
    rewrite_retrieval_strategy: str = "replace",
    rewrite_adoption_policy: dict[str, Any] | None = None,
    raw_config: dict[str, Any] | None = None,
    retriever_config: RetrieverConfig | None = None,
    retrieval_adapter: DbAnnRuntimeRetrievalAdapter | None = None,
    raw_retrieval: list[RetrievalCandidate] | None = None,
    source_product: str | None = None,
    memory_candidate_pool_n: int | None = None,
    max_subqueries: int = 3,
    rrf_k: int = 60,
) -> tuple[dict[str, Any], list[RetrievalCandidate]]:
    started = time.perf_counter()
    config = retriever_config or build_retriever_config({})
    bounded_max_subqueries = _clamp_count(max_subqueries, default=3, max_value=4)
    bounded_rrf_k = max(1, int(_float_value(rrf_k, 60)))
    memory_pool_n = max(
        memory_top_n_value,
        int(_float_value(memory_candidate_pool_n, max(memory_top_n_value * 4, 20))),
    )
    planner_memory_hints = memory_top_n(
        raw_query,
        memories,
        top_n=min(memory_pool_n, 8),
        preset_filter=preset_filter,
        source_gate_run_id=source_gate_run_id,
        strategy_filters=strategy_filters,
        retriever_config=config,
        retrieval_adapter=retrieval_adapter,
    )
    plan = build_agentic_query_plan(
        raw_query=raw_query,
        query_language=query_language,
        session_context=session_context,
        source_product=source_product,
        memory_hints=planner_memory_hints,
        max_subqueries=bounded_max_subqueries,
        raw_config=raw_config,
    )

    subquery_traces: list[dict[str, Any]] = []
    subquery_result_sets: list[list[RetrievalCandidate]] = []
    any_rewrite_applied = False
    rewrite_llm_attempted = False
    rewrite_llm_succeeded = False
    rewrite_heuristic_fallback_used = False
    best_candidate_confidence = 0.0
    raw_confidence = 0.0
    for subquery in plan.subqueries[:bounded_max_subqueries]:
        sub_started = time.perf_counter()
        sub_raw_retrieval = retrieve_top_k(
            subquery.query,
            chunks,
            top_k=retrieval_top_k,
            retriever_config=config,
            retrieval_adapter=retrieval_adapter,
        )
        trace_error = None
        try:
            rewrite_outcome, sub_retrieval = run_selective_rewrite(
                raw_query=subquery.query,
                query_language=query_language,
                query_category=query_category,
                session_context=session_context,
                chunks=chunks,
                memories=memories,
                memory_top_n_value=memory_top_n_value,
                candidate_count=candidate_count,
                threshold=threshold,
                retrieval_top_k=retrieval_top_k,
                preset_filter=preset_filter,
                source_gate_run_id=source_gate_run_id,
                strategy_filters=strategy_filters,
                force_rewrite=False,
                rewrite_anchor_injection_enabled=rewrite_anchor_injection_enabled,
                rewrite_terminology_hints_max_count=rewrite_terminology_hints_max_count,
                multi_source_anchor_expansion_enabled=multi_source_anchor_expansion_enabled,
                multi_source_anchor_index=multi_source_anchor_index,
                multi_source_anchor_relation_types=multi_source_anchor_relation_types,
                multi_source_anchor_min_score=multi_source_anchor_min_score,
                multi_source_anchor_max_per_seed=multi_source_anchor_max_per_seed,
                multi_source_anchor_max_total=multi_source_anchor_max_total,
                rewrite_query_profile=rewrite_query_profile,
                rewrite_failure_policy=rewrite_failure_policy,
                rewrite_retrieval_strategy=rewrite_retrieval_strategy,
                rewrite_adoption_policy=rewrite_adoption_policy,
                raw_config=raw_config,
                retriever_config=config,
                retrieval_adapter=retrieval_adapter,
                raw_retrieval=sub_raw_retrieval,
                source_product=source_product,
                memory_candidate_pool_n=memory_candidate_pool_n,
            )
            final_query = rewrite_outcome.final_query
            rewrite_applied = rewrite_outcome.rewrite_applied
            rewrite_reason = rewrite_outcome.rewrite_reason
            memory_top = rewrite_outcome.memory_top_n
            selected_rewrite = rewrite_outcome.selected_rewrite
            candidates = rewrite_outcome.candidates
            raw_confidence = max(raw_confidence, float(rewrite_outcome.raw_confidence or 0.0))
            best_candidate_confidence = max(
                best_candidate_confidence,
                float(rewrite_outcome.best_candidate_confidence or 0.0),
            )
            any_rewrite_applied = any_rewrite_applied or rewrite_applied
            rewrite_llm_attempted = rewrite_llm_attempted or rewrite_outcome.rewrite_llm_attempted
            rewrite_llm_succeeded = rewrite_llm_succeeded or rewrite_outcome.rewrite_llm_succeeded
            rewrite_heuristic_fallback_used = (
                rewrite_heuristic_fallback_used or rewrite_outcome.rewrite_heuristic_fallback_used
            )
        except Exception as exception:  # noqa: BLE001 - one failed subquery must not abort the eval sample.
            sub_retrieval = sub_raw_retrieval
            final_query = subquery.query
            rewrite_applied = False
            rewrite_reason = "subquery_rewrite_failed_raw_fallback"
            memory_top = []
            selected_rewrite = None
            candidates = []
            trace_error = f"{type(exception).__name__}: {str(exception)[:240]}"

        subquery_result_sets.append(sub_retrieval)
        subquery_traces.append(
            {
                "index": subquery.index,
                "subquery": subquery.query,
                "intent": subquery.intent,
                "weight": subquery.weight,
                "final_query": final_query,
                "rewrite_applied": rewrite_applied,
                "rewrite_reason": rewrite_reason,
                "rewrite_error": trace_error,
                "selected_rewrite": selected_rewrite,
                "rewrite_candidates": candidates,
                "memory_top_n": memory_top,
                "retrieval_count": len(sub_retrieval),
                "retrieved_top_k": retrieval_candidates_to_payload(sub_retrieval[:5]),
                "latency_ms": (time.perf_counter() - sub_started) * 1000.0,
            }
        )

    if not subquery_result_sets:
        fallback_retrieval = (
            list(raw_retrieval)
            if raw_retrieval is not None
            else retrieve_top_k(
                raw_query,
                chunks,
                top_k=retrieval_top_k,
                retriever_config=config,
                retrieval_adapter=retrieval_adapter,
            )
        )
        subquery_result_sets = [fallback_retrieval]
    merged = rrf_merge_retrieval_results(
        subquery_result_sets,
        top_k=retrieval_top_k,
        rrf_k=bounded_rrf_k,
    )
    elapsed_ms = (time.perf_counter() - started) * 1000.0
    return (
        {
            "rewrite_applied": any_rewrite_applied,
            "raw_confidence": raw_confidence,
            "best_candidate_confidence": best_candidate_confidence,
            "final_query": raw_query,
            "rewrite_reason": "agentic_multi_query_rrf",
            "memory_top_n": planner_memory_hints,
            "candidates": [],
            "selected_rewrite": None,
            "anchor_candidates": [],
            "terminology_hints": None,
            "canonical_anchor_hints": None,
            "multi_source_anchor_hints": None,
            "rewrite_llm_attempted": rewrite_llm_attempted,
            "rewrite_llm_succeeded": rewrite_llm_succeeded,
            "rewrite_heuristic_fallback_used": rewrite_heuristic_fallback_used,
            "final_rewrite_latency_ms": elapsed_ms,
            "pure_rewrite_latency_ms": plan.planner_latency_ms,
            "memory_hint_query": None,
            "memory_hint_retrieval_applied": False,
            "agentic_plan": _agentic_query_plan_payload(plan),
            "subquery_traces": subquery_traces,
            "merge_strategy": "RRF",
            "max_subqueries": bounded_max_subqueries,
            "rrf_k": bounded_rrf_k,
            "planner_fallback_applied": plan.fallback_applied,
            "agentic_planner_latency_ms": plan.planner_latency_ms,
            "agentic_total_latency_ms": elapsed_ms,
        },
        merged,
    )


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
    # Legacy name retained for rewrite_always callers. Selective adoption uses
    # final candidate score plus raw-loss guards over the raw baseline.
    force_rewrite: bool = False,
    rewrite_retrieval_strategy: str = "replace",
    rewrite_anchor_injection_enabled: bool = True,
    rewrite_terminology_hints_max_count: int = DEFAULT_REWRITE_TERMINOLOGY_HINTS_MAX,
    multi_source_anchor_expansion_enabled: bool = False,
    multi_source_anchor_index: MultiSourceAnchorIndex | None = None,
    multi_source_anchor_relation_types: list[str] | tuple[str, ...] | None = None,
    multi_source_anchor_min_score: float = 0.72,
    multi_source_anchor_max_per_seed: int = 2,
    multi_source_anchor_max_total: int = 8,
    rewrite_query_profile: str = REWRITE_QUERY_PROFILE_COMPACT_ANCHOR,
    rewrite_failure_policy: str | None = None,
    rewrite_adoption_policy: dict[str, Any] | None = None,
    raw_config: dict[str, Any] | None = None,
    retriever_config: RetrieverConfig | None = None,
    retrieval_adapter: DbAnnRuntimeRetrievalAdapter | None = None,
    raw_retrieval: list[RetrievalCandidate] | None = None,
    source_product: str | None = None,
    memory_candidate_pool_n: int | None = None,
    # Legacy compatibility knobs. They are intentionally ignored by the default
    # rewrite evaluator so memory hints cannot enter final retrieval.
    rewrite_memory_hint_retrieval_enabled: bool = False,
    rewrite_memory_hint_token_max: int = 3,
    rewrite_memory_hint_retrieval_strategy: str = "max_score",
) -> tuple[RewriteOutcome, list[RetrievalCandidate]]:
    # Staged selective-rewrite scoring:
    # 1) retrieval gain (confidence + shift)
    # 2) terminology preservation / anchor overlap
    # 3) memory alignment
    # 4) verbosity + preservation penalties
    # Final adoption compares final_candidate_score against the raw baseline and
    # blocks confident raw-result loss unless memory-target evidence improves.
    #
    # final_rewrite_latency_ms:
    #   measured from rewrite-stage entry through candidate validation/scoring and
    #   final rewrite-query adoption decision, excluding downstream answer scoring.
    config = retriever_config or build_retriever_config({})
    rewrite_started = time.perf_counter()
    raw_retrieval = (
        list(raw_retrieval)
        if raw_retrieval is not None
        else retrieve_top_k(
            raw_query,
            chunks,
            top_k=retrieval_top_k,
            retriever_config=config,
            retrieval_adapter=retrieval_adapter,
        )
    )
    memory_pool_n = max(
        memory_top_n_value,
        int(_float_value(memory_candidate_pool_n, max(memory_top_n_value * 4, 20))),
    )
    memory_pool_items = memory_top_n(
        raw_query,
        memories,
        top_n=memory_pool_n,
        preset_filter=preset_filter,
        source_gate_run_id=source_gate_run_id,
        strategy_filters=strategy_filters,
        retriever_config=config,
        retrieval_adapter=retrieval_adapter,
    )
    memory_items = _rerank_rewrite_memory_candidates(
        raw_query=raw_query,
        memory_items=memory_pool_items,
        raw_retrieval=raw_retrieval,
        query_language=query_language,
        source_product=source_product,
        top_n=memory_top_n_value,
    )
    trusted_memory_items = _trusted_rewrite_memory_items(memory_items)
    raw_memory_affinity = memory_items[0]["similarity"] if memory_items else 0.0
    raw_retrieval_score = retrieval_confidence_score(raw_retrieval)
    raw_confidence_base = raw_retrieval_score

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
    max_compact_query_chars = max(0, int(_float_value(thresholds.get("max_compact_query_chars"), 0.0)))
    min_retrieval_gain_score = _clamp01(_float_value(thresholds.get("min_retrieval_gain_score"), 0.0))
    preserved_raw_term_coverage_floor = _clamp01(
        _float_value(thresholds.get("preserved_raw_term_coverage_floor"), 1.0)
    )
    added_anchor_coverage_floor = _clamp01(
        _float_value(thresholds.get("added_anchor_coverage_floor"), 0.67)
    )
    underspecified_memory_norm_cutoff = _clamp01(
        _float_value(thresholds.get("underspecified_memory_norm_cutoff"), 0.0)
    )
    raw_loss_guard_confidence_floor = _clamp01(
        _float_value(thresholds.get("raw_loss_guard_confidence_floor"), 0.78)
    )
    raw_loss_guard_min_overlap_ratio = _clamp01(
        _float_value(thresholds.get("raw_loss_guard_min_overlap_ratio"), 0.20)
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
    source_memory_target_hit_margin_bonus = max(
        0.0,
        _float_value(bonuses.get("source_memory_target_hit_margin"), 0.0),
    )
    source_memory_target_selection_bonus = max(
        0.0,
        _float_value(bonuses.get("source_memory_target_selection"), 0.0),
    )
    query_profile = str(policy.get("query_profile") or "").strip().lower()
    raw_loss_guard_allow_source_memory_improved = bool(
        thresholds.get("raw_loss_guard_allow_source_memory_improved", True)
    )
    raw_loss_guard_require_top1_loss = bool(
        thresholds.get("raw_loss_guard_require_top1_loss", True)
    )
    normalized_rewrite_retrieval_strategy = _normalize_rewrite_retrieval_strategy(
        rewrite_retrieval_strategy
    )

    raw_memory = _memory_alignment_score(
        raw_memory_similarity=raw_memory_affinity,
        candidate_memory_similarity=raw_memory_affinity,
    )
    raw_reference_score = _weighted_candidate_score(
        retrieval_gain_score=raw_retrieval_score,
        terminology_preservation_score=1.0,
        memory_alignment_score=raw_memory["memory_alignment_score"],
        weights=weights,
    )
    raw_confidence = raw_reference_score

    memory_hint_query: str | None = None
    memory_hint_retrieval_applied = False
    rewrite_runtime_stats: dict[str, int] = {}
    multi_source_anchor_hints = None
    if rewrite_anchor_injection_enabled and multi_source_anchor_expansion_enabled and trusted_memory_items:
        multi_source_anchor_hints = _build_multi_source_anchor_hints(
            raw_query=raw_query,
            query_language=query_language,
            memory_items=trusted_memory_items,
            anchor_index=multi_source_anchor_index,
            relation_type_allowlist=multi_source_anchor_relation_types,
            min_relation_score=multi_source_anchor_min_score,
            max_per_seed=multi_source_anchor_max_per_seed,
            max_total=multi_source_anchor_max_total,
        )
    prompt_anchor_context: dict[str, Any] = {"anchors": [], "anchor_terms": []}
    prompt_terminology_hints: dict[str, Any] | None = None
    prompt_canonical_anchor_hints: dict[str, Any] | None = None
    if rewrite_anchor_injection_enabled and trusted_memory_items:
        prompt_anchor_context = _build_rewrite_anchor_candidates(
            raw_query=raw_query,
            query_language=query_language,
            memory_items=trusted_memory_items,
        )
        prompt_terminology_hints = _build_rewrite_terminology_hints(
            raw_query=raw_query,
            query_language=query_language,
            memory_items=trusted_memory_items,
            max_terms=rewrite_terminology_hints_max_count,
        )
        prompt_canonical_anchor_hints = _build_rewrite_canonical_anchor_hints(
            memory_items=trusted_memory_items,
            query_language=query_language,
            max_terms=rewrite_terminology_hints_max_count,
        )
        if not prompt_canonical_anchor_hints.get("terms"):
            prompt_canonical_anchor_hints = None
    candidate_templates = build_rewrite_candidates_v2(
        raw_query,
        memory_items,
        session_context=session_context,
        candidate_count=candidate_count,
        query_language=query_language,
        rewrite_anchor_injection_enabled=rewrite_anchor_injection_enabled,
        rewrite_terminology_hints_max_count=rewrite_terminology_hints_max_count,
        multi_source_anchor_hints=multi_source_anchor_hints,
        retrieval_context=_rewrite_retrieval_context(
            retriever_config=config,
            retrieval_adapter=retrieval_adapter,
            retrieval_top_k=retrieval_top_k,
            memory_candidate_pool_n=memory_pool_n,
            memory_top_n_value=memory_top_n_value,
        ),
        raw_retrieval_context=_raw_retrieval_prompt_candidates(
            raw_retrieval,
            query_language=query_language,
        ),
        domain_context=_rewrite_domain_context(source_product),
        rewrite_query_profile=rewrite_query_profile,
        rewrite_failure_policy=rewrite_failure_policy,
        rewrite_runtime_stats=rewrite_runtime_stats,
        trusted_memory_items=trusted_memory_items,
        raw_config=raw_config,
    )
    rewrite_llm_attempted = rewrite_runtime_stats.get("llm_attempted_count", 0) > 0
    rewrite_llm_succeeded = rewrite_runtime_stats.get("llm_success_count", 0) > 0
    rewrite_heuristic_fallback_used = rewrite_runtime_stats.get("heuristic_fallback_count", 0) > 0
    pure_rewrite_latency_ms = (
        float(rewrite_runtime_stats["pure_rewrite_latency_ms"])
        if "pure_rewrite_latency_ms" in rewrite_runtime_stats
        else None
    )
    raw_only_anchor_context = _build_rewrite_anchor_candidates(
        raw_query=raw_query,
        query_language=query_language,
        memory_items=[],
    )
    raw_only_anchor_terms = [
        str(item)
        for item in raw_only_anchor_context.get("anchor_terms") or []
        if str(item).strip()
    ]
    anchor_context = _build_rewrite_anchor_candidates(
        raw_query=raw_query,
        query_language=query_language,
        memory_items=trusted_memory_items,
    )
    memory_anchor_terms = [str(item) for item in anchor_context.get("anchor_terms") or [] if str(item).strip()]
    canonical_anchor_groups = _collect_scoring_canonical_anchor_groups(trusted_memory_items)
    candidates: list[dict[str, Any]] = []
    best_candidate: dict[str, Any] | None = None
    best_eligible_candidate: dict[str, Any] | None = None
    for template in candidate_templates:
        candidate_label = str(template.get("label") or "").strip().lower()
        uses_memory_context = candidate_label != "standalone"
        retrieved = retrieve_top_k(
            template["query"],
            chunks,
            top_k=retrieval_top_k,
            retriever_config=config,
            retrieval_adapter=retrieval_adapter,
        )
        candidate_memory_items = memory_top_n(
            template["query"],
            memories,
            top_n=memory_top_n_value,
            preset_filter=preset_filter,
            source_gate_run_id=source_gate_run_id,
            strategy_filters=strategy_filters,
            retriever_config=config,
            retrieval_adapter=retrieval_adapter,
        )
        candidate_memory_affinity = (
            candidate_memory_items[0]["similarity"]
            if candidate_memory_items
            else 0.0
        )
        candidate_retrieval_score = retrieval_confidence_score(retrieved)
        retrieval_score_delta = candidate_retrieval_score - raw_retrieval_score
        base_confidence = candidate_retrieval_score
        retrieval_shift = _retrieval_shift_score(raw_retrieval, retrieved)
        shift_bonus = shift_bonus_weight * retrieval_shift if base_confidence >= raw_confidence_base else 0.0
        retrieval_gain_score = _clamp01(base_confidence + shift_bonus)

        terminology_metrics = _terminology_preservation_metrics(
            raw_query=raw_query,
            candidate_query=template["query"],
            query_language=query_language,
            raw_anchor_terms=memory_anchor_terms if uses_memory_context else raw_only_anchor_terms,
            canonical_anchor_groups=canonical_anchor_groups if uses_memory_context else {},
        )
        terminology_preservation_score = terminology_metrics["terminology_preservation_score"]
        technical_preservation_ratio = terminology_metrics["technical_preservation_ratio"]
        anchor_overlap_ratio = terminology_metrics["anchor_overlap_ratio"]

        scoring_candidate_memory_affinity = (
            candidate_memory_affinity
            if uses_memory_context
            else raw_memory_affinity
        )
        memory_metrics = _memory_alignment_score(
            raw_memory_similarity=raw_memory_affinity,
            candidate_memory_similarity=scoring_candidate_memory_affinity,
        )
        memory_alignment_score = memory_metrics["memory_alignment_score"]
        try:
            source_memory_index = int(template.get("source_memory_index") or 0)
        except (TypeError, ValueError):
            source_memory_index = 0
        memory_target_context = memory_items if uses_memory_context else []
        if uses_memory_context and 0 < source_memory_index <= len(memory_items):
            source_memory_row = memory_items[source_memory_index - 1]
            memory_target_context = [
                source_memory_row,
                *[
                    item
                    for index, item in enumerate(memory_items, start=1)
                    if index != source_memory_index
                ],
            ]
        memory_target_metrics = _memory_target_metrics(
            raw_query=raw_query,
            candidate_query=template["query"],
            memory_items=memory_target_context,
            query_profile=query_profile,
            raw_memory_norm=raw_memory["raw_memory_norm"],
            underspecified_memory_norm_cutoff=underspecified_memory_norm_cutoff,
            memory_target_presence_bonus_weight=memory_target_presence_bonus_weight,
            memory_target_missing_penalty_weight=memory_target_missing_penalty_weight,
        )
        source_memory_metrics = _source_memory_target_retrieval_metrics(
            source_memory_index=source_memory_index,
            memory_items=memory_items,
            raw_retrieval=raw_retrieval,
            candidate_retrieval=retrieved,
        )
        raw_loss_metrics = _raw_retrieval_loss_metrics(
            raw_retrieval,
            retrieved,
            raw_retrieval_score=raw_retrieval_score,
            confidence_floor=raw_loss_guard_confidence_floor,
            min_overlap_ratio=raw_loss_guard_min_overlap_ratio,
            require_top1_loss=raw_loss_guard_require_top1_loss,
        )
        llm_anchor_metrics = _llm_anchor_coverage_metrics(
            raw_query=raw_query,
            candidate_query=template["query"],
            preserved_raw_terms=template.get("preserved_raw_terms") or [],
            added_anchors=template.get("added_anchors") or [],
        )
        source_memory_target_margin_bonus = (
            source_memory_target_hit_margin_bonus
            if source_memory_metrics["source_memory_target_improved"]
            else 0.0
        )
        source_memory_target_score_bonus = (
            source_memory_target_selection_bonus
            if source_memory_metrics["source_memory_target_improved"]
            else 0.0
        )
        intent_risk = str(template.get("intent_risk") or "medium").strip().lower()
        if intent_risk not in {"low", "medium", "high"}:
            intent_risk = "medium"

        length_ratio = _length_ratio_without_spaces(raw_query, template["query"])
        candidate_compact_chars = len("".join(str(template["query"] or "").split()))
        verbosity_exceeds_limit = length_ratio > max_length_ratio
        if max_compact_query_chars > 0 and candidate_compact_chars <= max_compact_query_chars:
            verbosity_exceeds_limit = False
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
            + source_memory_target_score_bonus
            - verbosity_penalty
            - preservation_penalty
            - memory_target_metrics["memory_target_missing_penalty"]
        )

        effective_threshold = max(float(threshold), min_improvement)
        final_score_delta = final_candidate_score - raw_reference_score
        adoption_margin = final_score_delta + source_memory_target_margin_bonus
        normalized_raw_query = " ".join(raw_query.split()).strip().lower()
        normalized_candidate_query = " ".join(str(template["query"]).split()).strip().lower()
        same_query = normalized_raw_query == normalized_candidate_query

        rejection_reason = ""
        if same_query:
            rejection_reason = "candidate_same_as_raw"
        elif intent_risk == "high":
            rejection_reason = "intent_risk_high"
        elif not source_memory_metrics["source_memory_index_valid"]:
            rejection_reason = "invalid_source_memory_index"
        elif llm_anchor_metrics["preserved_raw_term_coverage_ratio"] < preserved_raw_term_coverage_floor:
            rejection_reason = "preserved_raw_terms_missing"
        elif llm_anchor_metrics["added_anchor_coverage_ratio"] < added_anchor_coverage_floor:
            rejection_reason = "added_anchor_coverage_below_floor"
        elif verbosity_exceeds_limit:
            rejection_reason = "verbosity_exceeds_limit"
        elif terminology_preservation_score < preservation_floor:
            rejection_reason = "preservation_below_floor"
        elif retrieval_gain_score < min_retrieval_gain_score:
            rejection_reason = "retrieval_gain_below_floor"
        elif memory_target_metrics["missing_memory_target"]:
            rejection_reason = "missing_memory_target"
        elif (
            not force_rewrite
            and raw_loss_metrics["raw_loss_guard_triggered"]
            and (
                not source_memory_metrics["source_memory_target_improved"]
                or not raw_loss_guard_allow_source_memory_improved
            )
        ):
            rejection_reason = raw_loss_metrics["raw_loss_guard_reason"]
        elif not force_rewrite and adoption_margin < effective_threshold:
            rejection_reason = "delta_below_threshold"

        eligible = not rejection_reason
        candidate = {
            "label": template["label"],
            "query": template["query"],
            "preserved_raw_terms": llm_anchor_metrics["preserved_raw_terms"],
            "added_anchors": llm_anchor_metrics["added_anchors"],
            "source_memory_index": source_memory_index,
            "intent_risk": intent_risk,
            "base_confidence": base_confidence,
            "raw_final_score": raw_reference_score,
            "raw_retrieval_score": raw_retrieval_score,
            "candidate_retrieval_score": candidate_retrieval_score,
            "retrieval_score_delta": retrieval_score_delta,
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
            "canonical_anchor_overlap_ratio": terminology_metrics.get("canonical_anchor_overlap_ratio", 0.0),
            "canonical_anchor_raw_count": terminology_metrics.get("canonical_anchor_raw_count", 0.0),
            "canonical_anchor_preserved_count": terminology_metrics.get("canonical_anchor_preserved_count", 0.0),
            "canonical_anchor_term_ids": terminology_metrics.get("canonical_anchor_term_ids", []),
            "canonical_anchor_terms": terminology_metrics.get("canonical_anchor_terms", []),
            "verbosity_penalty": verbosity_penalty,
            "preservation_penalty": preservation_penalty,
            "memory_target_presence_bonus": memory_target_metrics["memory_target_presence_bonus"],
            "memory_target_missing_penalty": memory_target_metrics["memory_target_missing_penalty"],
            "source_memory_target_hit_margin_bonus": source_memory_target_margin_bonus,
            "source_memory_target_selection_bonus": source_memory_target_score_bonus,
            **source_memory_metrics,
            **llm_anchor_metrics,
            "memory_target_tokens": memory_target_metrics["memory_target_tokens"],
            "raw_target_overlap_count": memory_target_metrics["raw_target_overlap_count"],
            "candidate_target_overlap_count": memory_target_metrics["candidate_target_overlap_count"],
            "raw_is_underspecified": memory_target_metrics["raw_is_underspecified"],
            "final_candidate_score": final_candidate_score,
            "final_score_delta": final_score_delta,
            "adoption_margin": adoption_margin,
            "effective_threshold": effective_threshold,
            "preservation_floor": preservation_floor,
            "max_length_ratio": max_length_ratio,
            "max_compact_query_chars": max_compact_query_chars,
            "candidate_compact_chars": candidate_compact_chars,
            "length_ratio": length_ratio,
            "eligible": eligible,
            "rejection_reason": rejection_reason,
            "confidence": final_candidate_score,
            "retrieval": retrieved,
            **raw_loss_metrics,
        }
        candidates.append(candidate)
        if best_candidate is None or final_candidate_score > float(best_candidate.get("final_candidate_score", 0.0)):
            best_candidate = candidate
        if eligible and (
            best_eligible_candidate is None
            or final_candidate_score > float(best_eligible_candidate.get("final_candidate_score", -1.0))
        ):
            best_eligible_candidate = candidate

    if best_candidate is None:
        rewrite_elapsed_ms = (time.perf_counter() - rewrite_started) * 1000.0
        final_retrieval = raw_retrieval
        return (
            RewriteOutcome(
                final_query=raw_query,
                rewrite_applied=False,
                rewrite_reason="no_candidate",
                raw_confidence=raw_confidence,
                best_candidate_confidence=raw_confidence,
                memory_top_n=memory_items,
                candidates=[],
                selected_rewrite=None,
                anchor_candidates=list(prompt_anchor_context.get("anchors") or []),
                terminology_hints=prompt_terminology_hints,
                canonical_anchor_hints=prompt_canonical_anchor_hints,
                rewrite_llm_attempted=rewrite_llm_attempted,
                rewrite_llm_succeeded=rewrite_llm_succeeded,
                rewrite_heuristic_fallback_used=rewrite_heuristic_fallback_used,
                final_rewrite_latency_ms=None,
                pure_rewrite_latency_ms=pure_rewrite_latency_ms,
                multi_source_anchor_hints=multi_source_anchor_hints,
                memory_hint_query=memory_hint_query,
                memory_hint_retrieval_applied=memory_hint_retrieval_applied,
            ),
            final_retrieval,
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
            strategy=normalized_rewrite_retrieval_strategy,
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
    selected_rewrite_payload = {
        "label": selected_candidate.get("label"),
        "query": selected_candidate.get("query"),
        "preserved_raw_terms": selected_candidate.get("preserved_raw_terms", []),
        "added_anchors": selected_candidate.get("added_anchors", []),
        "source_memory_index": selected_candidate.get("source_memory_index", 0),
        "intent_risk": selected_candidate.get("intent_risk", "medium"),
        "confidence": selected_candidate.get("confidence", 0.0),
        "final_candidate_score": selected_candidate.get("final_candidate_score", 0.0),
        "raw_final_score": selected_candidate.get("raw_final_score", raw_reference_score),
        "raw_retrieval_score": selected_candidate.get("raw_retrieval_score", raw_retrieval_score),
        "candidate_retrieval_score": selected_candidate.get("candidate_retrieval_score", 0.0),
        "retrieval_score_delta": selected_candidate.get("retrieval_score_delta", 0.0),
        "final_score_delta": selected_candidate.get("final_score_delta", 0.0),
        "adoption_margin": selected_candidate.get("adoption_margin", 0.0),
        "raw_loss_guard_triggered": selected_candidate.get("raw_loss_guard_triggered", False),
        "raw_loss_guard_reason": selected_candidate.get("raw_loss_guard_reason", ""),
        "raw_loss_guard_raw_top1_preserved": selected_candidate.get("raw_loss_guard_raw_top1_preserved", False),
        "raw_loss_guard_topk_overlap_ratio": selected_candidate.get("raw_loss_guard_topk_overlap_ratio", 1.0),
        "effective_threshold": selected_candidate.get("effective_threshold", 0.0),
        "eligible": bool(selected_candidate.get("eligible")),
        "force_rewrite": force_rewrite,
        "rejection_reason": selected_candidate.get("rejection_reason", ""),
    }

    return (
        RewriteOutcome(
            final_query=final_query,
            rewrite_applied=should_apply,
            rewrite_reason=reason,
            raw_confidence=raw_confidence,
            best_candidate_confidence=float(selected_candidate.get("final_candidate_score", 0.0)),
            memory_top_n=memory_items,
            candidates=[
                {
                    "label": candidate["label"],
                    "query": candidate["query"],
                    "preserved_raw_terms": candidate.get("preserved_raw_terms", []),
                    "added_anchors": candidate.get("added_anchors", []),
                    "source_memory_index": candidate.get("source_memory_index", 0),
                    "intent_risk": candidate.get("intent_risk", "medium"),
                    "base_confidence": candidate.get("base_confidence", candidate["confidence"]),
                    "raw_final_score": candidate.get("raw_final_score", raw_reference_score),
                    "raw_retrieval_score": candidate.get("raw_retrieval_score", raw_retrieval_score),
                    "candidate_retrieval_score": candidate.get("candidate_retrieval_score", 0.0),
                    "retrieval_score_delta": candidate.get("retrieval_score_delta", 0.0),
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
                    "canonical_anchor_overlap_ratio": candidate.get("canonical_anchor_overlap_ratio", 0.0),
                    "canonical_anchor_raw_count": candidate.get("canonical_anchor_raw_count", 0.0),
                    "canonical_anchor_preserved_count": candidate.get("canonical_anchor_preserved_count", 0.0),
                    "canonical_anchor_term_ids": candidate.get("canonical_anchor_term_ids", []),
                    "canonical_anchor_terms": candidate.get("canonical_anchor_terms", []),
                    "verbosity_penalty": candidate.get("verbosity_penalty", 0.0),
                    "preservation_penalty": candidate.get("preservation_penalty", 0.0),
                    "memory_target_presence_bonus": candidate.get("memory_target_presence_bonus", 0.0),
                    "memory_target_missing_penalty": candidate.get("memory_target_missing_penalty", 0.0),
                    "source_memory_target_hit_margin_bonus": candidate.get("source_memory_target_hit_margin_bonus", 0.0),
                    "source_memory_index_valid": candidate.get("source_memory_index_valid", True),
                    "source_memory_target_chunk_hit": candidate.get("source_memory_target_chunk_hit", False),
                    "source_memory_target_doc_hit": candidate.get("source_memory_target_doc_hit", False),
                    "source_memory_target_improved": candidate.get("source_memory_target_improved", False),
                    "preserved_raw_terms_from_raw_count": candidate.get("preserved_raw_terms_from_raw_count", 0),
                    "preserved_raw_terms_in_candidate_count": candidate.get("preserved_raw_terms_in_candidate_count", 0),
                    "preserved_raw_term_coverage_ratio": candidate.get("preserved_raw_term_coverage_ratio", 1.0),
                    "added_anchor_coverage_count": candidate.get("added_anchor_coverage_count", 0),
                    "added_anchor_coverage_ratio": candidate.get("added_anchor_coverage_ratio", 1.0),
                    "memory_target_tokens": candidate.get("memory_target_tokens", []),
                    "raw_target_overlap_count": candidate.get("raw_target_overlap_count", 0),
                    "candidate_target_overlap_count": candidate.get("candidate_target_overlap_count", 0),
                    "raw_is_underspecified": candidate.get("raw_is_underspecified", False),
                    "final_candidate_score": candidate.get("final_candidate_score", candidate["confidence"]),
                    "final_score_delta": candidate.get("final_score_delta", 0.0),
                    "adoption_margin": candidate.get("adoption_margin", 0.0),
                    "raw_loss_guard_triggered": candidate.get("raw_loss_guard_triggered", False),
                    "raw_loss_guard_reason": candidate.get("raw_loss_guard_reason", ""),
                    "raw_loss_guard_raw_top1_preserved": candidate.get("raw_loss_guard_raw_top1_preserved", False),
                    "raw_loss_guard_topk_overlap_ratio": candidate.get("raw_loss_guard_topk_overlap_ratio", 1.0),
                    "raw_loss_guard_confidence_floor": candidate.get("raw_loss_guard_confidence_floor", 0.0),
                    "raw_loss_guard_min_overlap_ratio": candidate.get("raw_loss_guard_min_overlap_ratio", 0.0),
                    "effective_threshold": candidate.get("effective_threshold", 0.0),
                    "preservation_floor": candidate.get("preservation_floor", 0.0),
                    "max_length_ratio": candidate.get("max_length_ratio", 0.0),
                    "max_compact_query_chars": candidate.get("max_compact_query_chars", 0),
                    "candidate_compact_chars": candidate.get("candidate_compact_chars", 0),
                    "length_ratio": candidate.get("length_ratio", 0.0),
                    "eligible": bool(candidate.get("eligible")),
                    "selected": candidate is selected_candidate,
                    "rejection_reason": candidate.get("rejection_reason", ""),
                    "confidence": candidate["confidence"],
                }
                for candidate in candidates
            ],
            selected_rewrite=selected_rewrite_payload,
            anchor_candidates=list(prompt_anchor_context.get("anchors") or []),
            terminology_hints=prompt_terminology_hints,
            canonical_anchor_hints=prompt_canonical_anchor_hints,
            rewrite_llm_attempted=rewrite_llm_attempted,
            rewrite_llm_succeeded=rewrite_llm_succeeded,
            rewrite_heuristic_fallback_used=rewrite_heuristic_fallback_used,
            final_rewrite_latency_ms=rewrite_elapsed_ms if should_apply else None,
            pure_rewrite_latency_ms=pure_rewrite_latency_ms,
            multi_source_anchor_hints=multi_source_anchor_hints,
            memory_hint_query=memory_hint_query,
            memory_hint_retrieval_applied=memory_hint_retrieval_applied,
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
