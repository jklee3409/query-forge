from __future__ import annotations

import hashlib
import json
import math
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import psycopg

try:
    from common.cohere_reranker import CohereReranker, load_cohere_rerank_config
    from common.embeddings import embed_text
    from common.local_retriever import RetrieverConfig, build_retriever_config, get_local_text_retriever, local_retriever_name
    from common.llm_client import LlmClient, load_stage_config
except ModuleNotFoundError:  # pragma: no cover
    from pipeline.common.cohere_reranker import CohereReranker, load_cohere_rerank_config
    from pipeline.common.embeddings import embed_text
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


_REWRITE_CLIENT: LlmClient | None = None
_REWRITE_PROMPT_TEXT: str | None = None
_RERANKER: CohereReranker | None = None

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
                       es.single_or_multi_chunk
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
                       single_or_multi_chunk
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
        )
        for row in rows
    ]


def load_chunk_items(connection: psycopg.Connection[Any]) -> list[ChunkItem]:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT c.chunk_id, c.document_id, c.chunk_text
            FROM corpus_chunks c
            JOIN corpus_documents d ON d.document_id = c.document_id
            ORDER BY c.document_id, c.chunk_index_in_document
            """
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
                   m.metadata ->> 'source_gate_run_id' AS source_gate_run_id
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
        )
        for row in rows
    ]


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
    retriever = get_local_text_retriever(
        namespace="eval-chunks",
        item_ids=[chunk.chunk_id for chunk in chunks],
        texts=[chunk.text for chunk in chunks],
        fallback_embeddings=[chunk.embedding for chunk in chunks],
        retriever_config=config,
    )
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
    eligible: list[MemoryItem] = []
    for memory in memories:
        if preset_filter and memory.gating_preset != preset_filter:
            continue
        if source_gate_run_id and memory.source_gate_run_id != source_gate_run_id:
            continue
        if strategy_set and memory.generation_strategy.upper() not in strategy_set:
            continue
        eligible.append(memory)
    if not eligible:
        return []
    config = retriever_config or build_retriever_config({})
    retriever = get_local_text_retriever(
        namespace="eval-memory",
        item_ids=[memory.memory_id for memory in eligible],
        texts=[memory.query_text for memory in eligible],
        fallback_embeddings=[memory.embedding for memory in eligible],
        retriever_config=config,
    )
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
) -> list[dict[str, str]]:
    trace_id = f"rewrite:{hashlib.sha1(raw_query.encode('utf-8')).hexdigest()[:12]}"
    response = _rewrite_client().chat_json(
        system_prompt=_rewrite_prompt_text(),
        user_prompt=json.dumps(
            {
                "raw_query": raw_query,
                "session_context": session_context,
                "top_memory_candidates": memory_items[:5],
                "candidate_count": candidate_count,
            },
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


def build_rewrite_candidates_v2(
    raw_query: str,
    memory_items: list[dict[str, Any]],
    *,
    session_context: dict[str, Any],
    candidate_count: int,
    query_language: str,
) -> list[dict[str, str]]:
    trace_id = f"rewrite:{hashlib.sha1(raw_query.encode('utf-8')).hexdigest()[:12]}"
    fallback_allowed = str(os.getenv("QUERY_FORGE_ALLOW_HEURISTIC_REWRITE_FALLBACK") or "true").lower() == "true"
    try:
        response = _rewrite_client().chat_json(
            system_prompt=_rewrite_prompt_text(),
            user_prompt=json.dumps(
                {
                    "raw_query": raw_query,
                    "query_language": query_language,
                    "session_context": session_context,
                    "top_memory_candidates": memory_items[:5],
                    "candidate_count": candidate_count,
                },
                ensure_ascii=False,
                indent=2,
            ),
            response_schema=REWRITE_RESPONSE_SCHEMA,
            request_purpose="selective_rewrite",
            trace_id=trace_id,
        )
    except Exception:
        if fallback_allowed:
            return _heuristic_rewrite_candidates_v2(
                raw_query,
                memory_items,
                session_context=session_context,
                candidate_count=candidate_count,
                query_language=query_language,
            )
        raise
    candidate_rows = response.get("candidates")
    if not isinstance(candidate_rows, list):
        if fallback_allowed:
            return _heuristic_rewrite_candidates_v2(
                raw_query,
                memory_items,
                session_context=session_context,
                candidate_count=candidate_count,
                query_language=query_language,
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
    if fallback_allowed:
        return _heuristic_rewrite_candidates_v2(
            raw_query,
            memory_items,
            session_context=session_context,
            candidate_count=candidate_count,
            query_language=query_language,
        )
    raise RuntimeError("LLM rewrite candidate response was empty.")


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
    preset_filter: str | None = None,
    source_gate_run_id: str | None = None,
    strategy_filters: list[str] | None = None,
    force_rewrite: bool = False,
    retriever_config: RetrieverConfig | None = None,
) -> tuple[RewriteOutcome, list[RetrievalCandidate]]:
    config = retriever_config or build_retriever_config({})
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
    raw_confidence = confidence_score(raw_retrieval, raw_memory_affinity)

    candidate_templates = build_rewrite_candidates_v2(
        raw_query,
        memory_items,
        session_context=session_context,
        candidate_count=candidate_count,
        query_language=query_language,
    )
    candidates: list[dict[str, Any]] = []
    best_candidate = None
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
        shift_bonus = 0.03 * retrieval_shift if base_confidence >= raw_confidence else 0.0
        score = base_confidence + shift_bonus
        candidate = {
            "label": template["label"],
            "query": template["query"],
            "base_confidence": base_confidence,
            "raw_memory_similarity": raw_memory_affinity,
            "candidate_memory_similarity": candidate_memory_affinity,
            "memory_similarity_delta": candidate_memory_affinity - raw_memory_affinity,
            "retrieval_shift_score": retrieval_shift,
            "retrieval_shift_bonus": shift_bonus,
            "confidence": score,
            "retrieval": retrieved,
        }
        candidates.append(candidate)
        if best_candidate is None or score > best_candidate["confidence"]:
            best_candidate = candidate

    if best_candidate is None:
        return (
            RewriteOutcome(
                final_query=raw_query,
                rewrite_applied=False,
                rewrite_reason="no_candidate",
                raw_confidence=raw_confidence,
                best_candidate_confidence=raw_confidence,
                memory_top_n=memory_items,
                candidates=[],
            ),
            raw_retrieval,
        )

    delta = best_candidate["confidence"] - raw_confidence
    normalized_raw_query = " ".join(raw_query.split()).strip().lower()
    normalized_best_query = " ".join(str(best_candidate["query"]).split()).strip().lower()
    same_query = normalized_raw_query == normalized_best_query
    should_apply = force_rewrite or (not same_query and delta >= threshold)
    final_query = best_candidate["query"] if should_apply else raw_query
    final_retrieval = best_candidate["retrieval"] if should_apply else raw_retrieval
    reason = (
        "forced"
        if should_apply and force_rewrite
        else "delta_above_threshold"
        if should_apply
        else "candidate_same_as_raw"
        if same_query
        else "delta_below_threshold"
    )

    return (
        RewriteOutcome(
            final_query=final_query,
            rewrite_applied=should_apply,
            rewrite_reason=reason,
            raw_confidence=raw_confidence,
            best_candidate_confidence=best_candidate["confidence"],
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
                    "confidence": candidate["confidence"],
                }
                for candidate in candidates
            ],
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
