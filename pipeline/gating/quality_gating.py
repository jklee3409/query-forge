from __future__ import annotations

import json
import logging
import uuid
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import psycopg
from psycopg.types.json import Jsonb

try:
    from common.cohere_reranker import CohereReranker, load_cohere_rerank_config
    from common.embeddings import cosine_similarity, embed_text
    from common.experiment_config import ExperimentConfig, load_experiment_config
    from common.experiment_run import ExperimentRunRecorder
    from common.llm_client import LlmClient, load_stage_config
    from common.prompt_assets import load_and_register_prompt
    from common.text_utils import copy_ratio, korean_ratio, special_char_ratio, token_count
    from loaders.common import connect, default_database_args
except ModuleNotFoundError:  # pragma: no cover - direct module execution fallback
    from pipeline.common.cohere_reranker import CohereReranker, load_cohere_rerank_config
    from pipeline.common.embeddings import cosine_similarity, embed_text
    from pipeline.common.experiment_config import ExperimentConfig, load_experiment_config
    from pipeline.common.experiment_run import ExperimentRunRecorder
    from pipeline.common.llm_client import LlmClient, load_stage_config
    from pipeline.common.prompt_assets import load_and_register_prompt
    from pipeline.common.text_utils import copy_ratio, korean_ratio, special_char_ratio, token_count
    from pipeline.loaders.common import connect, default_database_args


LOGGER = logging.getLogger(__name__)

SHORT_TYPES = {"short_user", "follow_up"}
SPECIAL_CHAR_EXCEPTION_PATTERN = (
    ".",
    "-",
    "_",
    "/",
    ":",
    "(",
    ")",
    "@",
    "'",
    "`",
)

SELF_EVAL_RESPONSE_SCHEMA: dict[str, Any] = {
    "type": "object",
    "required": ["scores"],
    "properties": {
        "schema_version": {"type": "string"},
        "scores": {
            "type": "object",
            "required": [
                "grounded",
                "answerable",
                "user_like",
                "korean_naturalness",
                "copy_control",
            ],
            "properties": {
                "grounded": {"type": "integer"},
                "answerable": {"type": "integer"},
                "user_like": {"type": "integer"},
                "korean_naturalness": {"type": "integer"},
                "copy_control": {"type": "integer"},
            },
            "additionalProperties": True,
        },
    },
    "additionalProperties": True,
}


@dataclass(slots=True)
class RawQueryRow:
    synthetic_query_id: str
    chunk_id_source: str
    target_doc_id: str
    target_chunk_ids: list[str]
    answerability_type: str
    query_text: str
    query_type: str
    generation_strategy: str
    source_summary: str
    metadata: dict[str, Any]


@dataclass(slots=True)
class ChunkItem:
    chunk_id: str
    document_id: str
    title: str
    chunk_text: str
    embedding: list[float]


def _stable_id(parts: list[str]) -> str:
    return str(uuid.uuid5(uuid.NAMESPACE_URL, "|".join(parts)))


def _strategies_for_gating(config: ExperimentConfig) -> list[str]:
    strategies = [config.generation_strategy]
    if config.enable_code_mixed and "D" not in strategies:
        strategies.append("D")
    source_strategies = config.raw.get("source_generation_strategies")
    if isinstance(source_strategies, list) and source_strategies:
        normalized = [str(value).upper() for value in source_strategies if str(value).strip()]
        if normalized:
            strategies = normalized
    return strategies


def _latest_generation_run_id(
    connection: psycopg.Connection[Any],
    strategies: list[str],
) -> str | None:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT experiment_run_id
            FROM synthetic_queries_raw_all
            WHERE generation_strategy = ANY(%s)
            ORDER BY created_at DESC
            LIMIT 1
            """,
            (strategies,),
        )
        row = cursor.fetchone()
    if row is None:
        return None
    if isinstance(row, dict):
        value = row.get("experiment_run_id")
    else:
        value = row[0]
    return str(value) if value is not None else None


def _gating_batch_exists(
    connection: psycopg.Connection[Any],
    gating_batch_id: str,
) -> bool:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT 1
            FROM quality_gating_batch
            WHERE gating_batch_id = %s
            """,
            (gating_batch_id,),
        )
        return cursor.fetchone() is not None


def _load_raw_queries(
    connection: psycopg.Connection[Any],
    *,
    strategies: list[str],
    generation_run_id: str | None,
) -> list[RawQueryRow]:
    where_run = ""
    parameters: list[Any] = [strategies]
    if generation_run_id is not None:
        where_run = " AND experiment_run_id = %s"
        parameters.append(generation_run_id)
    with connection.cursor() as cursor:
        cursor.execute(
            f"""
            SELECT synthetic_query_id,
                   chunk_id_source,
                   target_doc_id,
                   target_chunk_ids,
                   answerability_type,
                   query_text,
                   query_type,
                   generation_strategy,
                   source_summary,
                   metadata
            FROM synthetic_queries_raw_all
            WHERE generation_strategy = ANY(%s)
            {where_run}
            ORDER BY created_at ASC
            """,
            parameters,
        )
        rows = cursor.fetchall()
    return [
        RawQueryRow(
            synthetic_query_id=str(row["synthetic_query_id"]),
            chunk_id_source=str(row["chunk_id_source"]),
            target_doc_id=str(row["target_doc_id"]),
            target_chunk_ids=list(row["target_chunk_ids"] or []),
            answerability_type=str(row["answerability_type"]),
            query_text=str(row["query_text"]),
            query_type=str(row["query_type"]),
            generation_strategy=str(row["generation_strategy"]),
            source_summary=str(row["source_summary"] or ""),
            metadata=dict(row["metadata"] or {}),
        )
        for row in rows
    ]


def _stage_passes_from_persisted_row(
    *,
    rule_pass: bool | None,
    llm_pass: bool | None,
    utility_pass: bool | None,
    diversity_pass: bool | None,
    config: ExperimentConfig,
) -> tuple[bool | None, bool | None, bool | None, bool | None]:
    if config.enable_rule_filter:
        rule_stage_pass: bool | None = bool(rule_pass)
    else:
        rule_stage_pass = True

    if config.enable_llm_self_eval and rule_stage_pass:
        llm_stage_pass: bool | None = bool(llm_pass)
    elif not config.enable_llm_self_eval:
        llm_stage_pass = True
    else:
        llm_stage_pass = None

    can_run_utility = bool(rule_stage_pass) and llm_stage_pass is not False
    if can_run_utility:
        if config.enable_retrieval_utility:
            utility_stage_pass: bool | None = bool(utility_pass)
        else:
            utility_stage_pass = True
    elif not config.enable_retrieval_utility:
        utility_stage_pass = True
    else:
        utility_stage_pass = None

    can_run_diversity = can_run_utility and utility_stage_pass is not False
    if can_run_diversity:
        if config.enable_diversity:
            diversity_stage_pass: bool | None = bool(diversity_pass)
        else:
            diversity_stage_pass = True
    elif not config.enable_diversity:
        diversity_stage_pass = True
    else:
        diversity_stage_pass = None

    return rule_stage_pass, llm_stage_pass, utility_stage_pass, diversity_stage_pass


def _load_existing_gating_state(
    connection: psycopg.Connection[Any],
    *,
    gating_batch_id: str | None,
    config: ExperimentConfig,
) -> dict[str, Any]:
    state = {
        "processed_query_ids": set(),
        "last_processed_query_id": None,
        "accepted_seed_rows": [],
        "decision_counter": Counter(),
        "rejection_counter": Counter(),
        "stage_counter": Counter(
            {
                "generated": 0,
                "rule_filter": 0,
                "llm_self_eval": 0,
                "retrieval_utility": 0,
                "diversity_dedup": 0,
                "final_approved": 0,
            }
        ),
        "preview_rows": [],
    }
    if not gating_batch_id:
        return state

    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT gr.synthetic_query_id,
                   gr.query_text,
                   gr.accepted,
                   gr.rule_pass,
                   (gr.stage_payload_json ->> 'passed_llm_self_eval')::boolean AS passed_llm_self_eval,
                   (gr.stage_payload_json ->> 'passed_retrieval_utility')::boolean AS passed_retrieval_utility,
                   gr.diversity_pass,
                   gr.utility_score,
                   gr.final_score,
                   gr.rejected_reason,
                   gr.stage_payload_json -> 'rejection_reasons' AS rejection_reasons
            FROM synthetic_query_gating_result gr
            WHERE gr.gating_batch_id = %s
            ORDER BY gr.created_at ASC, gr.synthetic_query_id ASC
            """,
            (gating_batch_id,),
        )
        rows = cursor.fetchall()

    for row in rows:
        synthetic_query_id = str(row["synthetic_query_id"])
        accepted = bool(row["accepted"])
        rule_stage_pass, llm_stage_pass, utility_stage_pass, diversity_stage_pass = _stage_passes_from_persisted_row(
            rule_pass=row["rule_pass"],
            llm_pass=row["passed_llm_self_eval"],
            utility_pass=row["passed_retrieval_utility"],
            diversity_pass=row["diversity_pass"],
            config=config,
        )

        reasons_value = row["rejection_reasons"]
        rejection_reasons = [str(reason) for reason in (reasons_value or []) if str(reason).strip()]
        if not rejection_reasons and row["rejected_reason"]:
            rejection_reasons = [str(row["rejected_reason"])]

        state["processed_query_ids"].add(synthetic_query_id)
        state["last_processed_query_id"] = synthetic_query_id

        if accepted:
            state["decision_counter"]["accepted"] += 1
            state["stage_counter"]["final_approved"] += 1
        else:
            state["decision_counter"]["rejected"] += 1
            for reason in rejection_reasons:
                state["rejection_counter"][reason] += 1

        state["stage_counter"]["generated"] += 1
        if rule_stage_pass:
            state["stage_counter"]["rule_filter"] += 1
        if rule_stage_pass and llm_stage_pass:
            state["stage_counter"]["llm_self_eval"] += 1
        if rule_stage_pass and llm_stage_pass and utility_stage_pass:
            state["stage_counter"]["retrieval_utility"] += 1
        if rule_stage_pass and llm_stage_pass and utility_stage_pass and diversity_stage_pass:
            state["stage_counter"]["diversity_dedup"] += 1

        if len(state["preview_rows"]) < 20:
            state["preview_rows"].append(
                {
                    "synthetic_query_id": synthetic_query_id,
                    "query_text": str(row["query_text"] or ""),
                    "final_decision": accepted,
                    "utility_score": round(float(row["utility_score"]), 4) if row["utility_score"] is not None else None,
                    "final_score": round(float(row["final_score"]), 4) if row["final_score"] is not None else None,
                    "rejection_reasons": rejection_reasons,
                }
            )

    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT r.chunk_id_source,
                   r.target_doc_id,
                   gr.query_text
            FROM synthetic_query_gating_result gr
            JOIN synthetic_queries_raw_all r
              ON r.synthetic_query_id = gr.synthetic_query_id
            WHERE gr.gating_batch_id = %s
              AND gr.accepted = TRUE
            ORDER BY gr.created_at ASC, gr.synthetic_query_id ASC
            """,
            (gating_batch_id,),
        )
        accepted_rows = cursor.fetchall()
    state["accepted_seed_rows"] = [
        (
            str(row["chunk_id_source"] or ""),
            str(row["target_doc_id"] or ""),
            str(row["query_text"] or ""),
        )
        for row in accepted_rows
    ]
    return state


def _load_chunk_items(connection: psycopg.Connection[Any]) -> tuple[list[ChunkItem], dict[str, ChunkItem]]:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT c.chunk_id,
                   c.document_id,
                   c.chunk_text,
                   d.title
            FROM corpus_chunks c
            JOIN corpus_documents d ON d.document_id = c.document_id
            ORDER BY c.document_id, c.chunk_index_in_document
            """
        )
        rows = cursor.fetchall()
    chunks = [
        ChunkItem(
            chunk_id=str(row["chunk_id"]),
            document_id=str(row["document_id"]),
            title=str(row["title"]),
            chunk_text=str(row["chunk_text"]),
            embedding=embed_text(str(row["chunk_text"])),
        )
        for row in rows
    ]
    indexed = {chunk.chunk_id: chunk for chunk in chunks}
    return chunks, indexed


def _rank_chunks(query_embedding: list[float], chunks: list[ChunkItem], top_k: int = 5) -> list[tuple[ChunkItem, float]]:
    scored = [(chunk, cosine_similarity(query_embedding, chunk.embedding)) for chunk in chunks]
    scored.sort(key=lambda item: item[1], reverse=True)
    return scored[:top_k]


def _retrieval_utility_score(
    *,
    query_embedding: list[float],
    query: RawQueryRow,
    chunks: list[ChunkItem],
    utility_weights: dict[str, float],
    reranker: CohereReranker,
    candidate_pool_k: int,
) -> float:
    initial_top_k = max(10, candidate_pool_k)
    pre_ranked = _rank_chunks(query_embedding, chunks, top_k=initial_top_k)
    reranked: list[tuple[ChunkItem, float]] = []
    if pre_ranked:
        rerank_rows = reranker.rerank(
            query=query.query_text,
            documents=[chunk.chunk_text for chunk, _score in pre_ranked],
            top_n=10,
        )
        if rerank_rows:
            for index, score in rerank_rows:
                if 0 <= index < len(pre_ranked):
                    chunk = pre_ranked[index][0]
                    # normalize cohere relevance score(0..1) into cosine-like range(-1..1)
                    reranked.append((chunk, (float(score) * 2.0) - 1.0))
    ranked = reranked if reranked else pre_ranked[:10]
    rank_map = {item.chunk_id: index + 1 for index, (item, _score) in enumerate(ranked)}
    target_ranks = [rank_map.get(chunk_id) for chunk_id in query.target_chunk_ids if chunk_id in rank_map]

    def _base_score(rank: int | None) -> float:
        if rank is None:
            return utility_weights["outside_top5"]
        if rank == 1:
            return utility_weights["target_top1"]
        if rank <= 3:
            return utility_weights["target_top3"]
        if rank <= 5:
            return utility_weights["target_top5"]
        if rank <= 10:
            return utility_weights.get("target_top10", utility_weights["target_top5"])
        return utility_weights["outside_top5"]

    if query.answerability_type == "single" or len(query.target_chunk_ids) <= 1:
        return _base_score(target_ranks[0] if target_ranks else None)

    hit_count = len(target_ranks)
    best_rank = min(target_ranks) if target_ranks else None
    score = _base_score(best_rank)
    if hit_count == 1:
        score += utility_weights.get("multi_partial_bonus", 0.0)
    elif hit_count >= 2:
        score += utility_weights.get("multi_full_bonus", 0.0)

    if hit_count == 0:
        for index, (chunk, _sim) in enumerate(ranked, start=1):
            if chunk.document_id == query.target_doc_id and index <= 3:
                return utility_weights["same_doc_top3"]
            if chunk.document_id == query.target_doc_id and index <= 5:
                return utility_weights["same_doc_top5"]
    return min(1.0, score)


def _llm_self_eval(
    query: RawQueryRow,
    source_chunk: ChunkItem,
    *,
    client: LlmClient,
    prompt_text: str,
) -> tuple[dict[str, int], dict[str, Any]]:
    payload = client.chat_json(
        system_prompt=prompt_text,
        user_prompt=json.dumps(
            {
                "query_text": query.query_text,
                "query_type": query.query_type,
                "answerability_type": query.answerability_type,
                "source_chunk_text": source_chunk.chunk_text,
                "source_summary": query.source_summary,
                "target_doc_id": query.target_doc_id,
                "target_chunk_ids": query.target_chunk_ids,
            },
            ensure_ascii=False,
            indent=2,
        ),
        response_schema=SELF_EVAL_RESPONSE_SCHEMA,
        request_purpose="quality_gating_self_eval",
        trace_id=f"query:{query.synthetic_query_id}",
    )
    scores_raw = payload.get("scores") if isinstance(payload.get("scores"), dict) else {}
    scores = {
        "grounded": int(scores_raw.get("grounded", 3)),
        "answerable": int(scores_raw.get("answerable", 3)),
        "user_like": int(scores_raw.get("user_like", 3)),
        "korean_naturalness": int(scores_raw.get("korean_naturalness", 3)),
        "copy_control": int(scores_raw.get("copy_control", 3)),
    }
    return scores, payload


def _llm_pass(scores: dict[str, int]) -> bool:
    return all(
        [
            scores["grounded"] >= 4,
            scores["answerable"] >= 3,
            scores["user_like"] >= 3,
            scores["korean_naturalness"] >= 3,
            scores["copy_control"] >= 3,
        ]
    )


def _first_rejection_stage(
    *,
    rule_enabled: bool,
    llm_enabled: bool,
    utility_enabled: bool,
    diversity_enabled: bool,
    passed_rule: bool,
    passed_llm: bool,
    passed_utility: bool,
    passed_diversity: bool,
    passed_final_score: bool,
) -> str:
    if rule_enabled and not passed_rule:
        return "rule_filter"
    if llm_enabled and not passed_llm:
        return "llm_self_eval"
    if utility_enabled and not passed_utility:
        return "retrieval_utility"
    if diversity_enabled and not passed_diversity:
        return "diversity_dedup"
    if not passed_final_score:
        return "final_score"
    return "approved"


def _rule_pass(
    query: RawQueryRow,
    source_chunk: ChunkItem,
    config: ExperimentConfig,
) -> tuple[bool, list[str]]:
    reasons: list[str] = []
    stripped = query.query_text.strip()
    length = len(stripped)
    short_min_len = int(config.raw.get("rule_min_len_short", 4))
    short_max_len = int(config.raw.get("rule_max_len_short", 60))
    long_min_len = int(config.raw.get("rule_min_len_long", 8))
    long_max_len = int(config.raw.get("rule_max_len_long", 100))
    min_len = short_min_len if query.query_type in SHORT_TYPES else long_min_len
    max_len = short_max_len if query.query_type in SHORT_TYPES else long_max_len
    if length < min_len or length > max_len:
        reasons.append("length_out_of_range")

    tokens = token_count(stripped)
    min_tokens = int(config.raw.get("rule_min_tokens", 2))
    max_tokens = int(config.raw.get("rule_max_tokens", 20))
    if tokens < min_tokens or tokens > max_tokens:
        reasons.append("token_count_out_of_range")

    special_ratio = special_char_ratio(stripped)
    allowed_special = (
        query.metadata.get("term_type") in {"config_key", "class", "annotation", "cli"}
        or any(char in stripped for char in SPECIAL_CHAR_EXCEPTION_PATTERN)
    )
    if not allowed_special and special_ratio > 0.20:
        reasons.append("special_ratio_high")

    copied = copy_ratio(stripped, source_chunk.title + " " + source_chunk.chunk_text, ngram=4)
    if copied > 0.60:
        reasons.append("copy_ratio_high")

    ratio = korean_ratio(stripped)
    min_korean_ratio = float(config.raw.get("rule_min_korean_ratio", 0.40))
    min_korean_ratio_code_mixed = float(config.raw.get("rule_min_korean_ratio_code_mixed", 0.20))
    min_korean_ratio = max(0.0, min(1.0, min_korean_ratio))
    min_korean_ratio_code_mixed = max(0.0, min(1.0, min_korean_ratio_code_mixed))
    min_korean_ratio = min_korean_ratio_code_mixed if query.query_type == "code_mixed" else min_korean_ratio
    if ratio < min_korean_ratio:
        reasons.append("korean_ratio_low")

    return len(reasons) == 0, reasons


def _preset_pass(
    preset: str,
    *,
    rule_pass: bool,
    llm_pass: bool,
    utility_pass: bool,
    diversity_pass: bool,
    final_pass: bool,
) -> bool:
    if preset == "ungated":
        return True
    if preset == "rule_only":
        return rule_pass
    if preset == "rule_plus_llm":
        return rule_pass and llm_pass
    return rule_pass and llm_pass and utility_pass and diversity_pass and final_pass


def run_quality_gating(
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
                "stage": "gate-queries",
                "gating_preset": config.gating_preset,
                "enable_rule_filter": config.enable_rule_filter,
                "enable_llm_self_eval": config.enable_llm_self_eval,
                "enable_retrieval_utility": config.enable_retrieval_utility,
                "enable_diversity": config.enable_diversity,
            },
            run_label="gate-queries",
        )

        self_eval_prompt_path = prompt_root / "self_eval" / "quality_gate_v1.md"
        self_eval_prompt = load_and_register_prompt(connection, self_eval_prompt_path)
        self_eval_prompt_text = self_eval_prompt_path.read_text(encoding="utf-8")
        self_eval_client = LlmClient(load_stage_config(stage="self_eval", raw_config=config.raw))
        reranker = CohereReranker(load_cohere_rerank_config(config.raw))
        utility_candidate_pool_k = int(config.raw.get("utility_candidate_pool_k") or 40)
        utility_candidate_pool_k = max(5, min(utility_candidate_pool_k, 200))

        strategies = _strategies_for_gating(config)
        gating_batch_id = str(config.raw.get("gating_batch_id") or "").strip() or None
        if gating_batch_id and not _gating_batch_exists(connection, gating_batch_id):
            LOGGER.warning(
                "gating_batch_id=%s not found. storing gated rows with NULL batch id and deferring linkage by experiment_run_id",
                gating_batch_id,
            )
            gating_batch_id = None
        configured_run_id = config.raw.get("source_generation_run_id")
        generation_run_id = str(configured_run_id).strip() if configured_run_id else None
        if not generation_run_id:
            generation_run_id = _latest_generation_run_id(connection, strategies)
        raw_queries = _load_raw_queries(
            connection,
            strategies=strategies,
            generation_run_id=generation_run_id,
        )
        existing_state = _load_existing_gating_state(
            connection,
            gating_batch_id=gating_batch_id,
            config=config,
        )
        processed_query_ids: set[str] = set(existing_state["processed_query_ids"])
        processed_existing_count = len(processed_query_ids)

        pending_queries = raw_queries
        last_processed_query_id = existing_state["last_processed_query_id"]
        if last_processed_query_id:
            index_by_query_id = {query.synthetic_query_id: index for index, query in enumerate(raw_queries)}
            last_index = index_by_query_id.get(last_processed_query_id)
            if last_index is not None:
                pending_queries = raw_queries[last_index + 1 :]
            else:
                pending_queries = raw_queries
        pending_queries = [query for query in pending_queries if query.synthetic_query_id not in processed_query_ids]

        chunks, chunk_index = _load_chunk_items(connection)

        accepted_by_chunk: dict[str, list[list[float]]] = defaultdict(list)
        accepted_by_doc: dict[str, list[list[float]]] = defaultdict(list)
        accepted_global: list[list[float]] = []
        for chunk_id_source, target_doc_id, query_text in existing_state["accepted_seed_rows"]:
            if not chunk_id_source or not target_doc_id or not query_text:
                continue
            seed_embedding = embed_text(query_text)
            accepted_by_chunk[chunk_id_source].append(seed_embedding)
            accepted_by_doc[target_doc_id].append(seed_embedding)
            accepted_global.append(seed_embedding)

        decision_counter: Counter[str] = Counter(existing_state["decision_counter"])
        rejection_counter: Counter[str] = Counter(existing_state["rejection_counter"])
        preview_rows: list[dict[str, Any]] = list(existing_state["preview_rows"])
        stage_counter: Counter[str] = Counter(
            {
                "generated": len(raw_queries),
                "rule_filter": 0,
                "llm_self_eval": 0,
                "retrieval_utility": 0,
                "diversity_dedup": 0,
                "final_approved": 0,
            }
        )
        stage_counter.update(existing_state["stage_counter"])
        stage_counter["generated"] = len(raw_queries)

        if processed_existing_count > 0:
            LOGGER.info(
                "Resuming quality gating for batch=%s. processed=%d, pending=%d, total=%d",
                gating_batch_id,
                processed_existing_count,
                len(pending_queries),
                len(raw_queries),
            )

        llm_batch_size = int(config.raw.get("llm_batch_size") or 20)
        llm_batch_size = max(1, min(llm_batch_size, 20))
        for row_index, query in enumerate(pending_queries):
            if query.synthetic_query_id in processed_query_ids:
                continue
            source_chunk = chunk_index.get(query.chunk_id_source)
            if source_chunk is None:
                continue
            query_embedding = embed_text(query.query_text)

            passed_rule: bool | None = None
            rule_reasons: list[str] = []
            if config.enable_rule_filter:
                passed_rule, rule_reasons = _rule_pass(query, source_chunk, config)
                rule_stage_pass: bool | None = passed_rule
            else:
                rule_stage_pass = True

            llm_scores: dict[str, int] = {}
            llm_payload: dict[str, Any] = {"schema_version": "v1", "scores": {}}
            llm_avg: float | None = None
            passed_llm: bool | None = None
            if config.enable_llm_self_eval and rule_stage_pass:
                llm_scores, llm_payload = _llm_self_eval(
                    query,
                    source_chunk,
                    client=self_eval_client,
                    prompt_text=self_eval_prompt_text,
                )
                passed_llm = _llm_pass(llm_scores)
                llm_avg = sum(llm_scores.values()) / (len(llm_scores) * 5.0)
                llm_stage_pass: bool | None = passed_llm
            elif not config.enable_llm_self_eval:
                llm_avg = 1.0
                llm_stage_pass = True
            else:
                llm_stage_pass = None

            utility_score: float | None = None
            passed_utility: bool | None = None
            can_run_utility = rule_stage_pass and llm_stage_pass is not False
            if can_run_utility:
                utility_score = _retrieval_utility_score(
                    query_embedding=query_embedding,
                    query=query,
                    chunks=chunks,
                    utility_weights=config.retrieval_utility_weights,
                    reranker=reranker,
                    candidate_pool_k=utility_candidate_pool_k,
                )
                if config.enable_retrieval_utility:
                    passed_utility = utility_score >= config.utility_threshold
                    utility_stage_pass: bool | None = passed_utility
                else:
                    utility_stage_pass = True
            elif not config.enable_retrieval_utility:
                utility_stage_pass = True
            else:
                utility_stage_pass = None

            novelty_score: float | None = None
            passed_diversity: bool | None = None
            can_run_diversity = can_run_utility and utility_stage_pass is not False
            if can_run_diversity:
                max_chunk_similarity = max(
                    [cosine_similarity(query_embedding, emb) for emb in accepted_by_chunk[query.chunk_id_source]]
                    or [0.0]
                )
                max_doc_similarity = max(
                    [cosine_similarity(query_embedding, emb) for emb in accepted_by_doc[query.target_doc_id]]
                    or [0.0]
                )
                max_global_similarity = max(
                    [cosine_similarity(query_embedding, emb) for emb in accepted_global]
                    or [0.0]
                )
                novelty_score = max(0.0, 1.0 - max_global_similarity)
                if config.enable_diversity:
                    passed_diversity = (
                        max_chunk_similarity <= config.diversity_threshold_same_chunk
                        and max_doc_similarity <= config.diversity_threshold_same_doc
                    )
                    diversity_stage_pass: bool | None = passed_diversity
                else:
                    diversity_stage_pass = True
            elif not config.enable_diversity:
                diversity_stage_pass = True
            else:
                diversity_stage_pass = None

            final_score: float | None = None
            passed_final_score: bool | None = None
            can_run_final = can_run_diversity and diversity_stage_pass is not False
            if can_run_final and utility_score is not None and novelty_score is not None:
                llm_component = llm_avg if llm_avg is not None else 0.0
                final_score = (
                    config.gating_weights["utility"] * utility_score
                    + config.gating_weights["llm"] * llm_component
                    + config.gating_weights["novelty"] * novelty_score
                )
                passed_final_score = final_score >= config.final_score_threshold

            rule_pass_for_decision = rule_stage_pass is not False
            llm_pass_for_decision = llm_stage_pass is not False
            utility_pass_for_decision = utility_stage_pass is not False
            diversity_pass_for_decision = diversity_stage_pass is not False
            final_pass_for_decision = passed_final_score is not False

            passed = _preset_pass(
                config.gating_preset,
                rule_pass=rule_pass_for_decision,
                llm_pass=llm_pass_for_decision,
                utility_pass=utility_pass_for_decision,
                diversity_pass=diversity_pass_for_decision,
                final_pass=final_pass_for_decision,
            )
            rejected_stage = _first_rejection_stage(
                rule_enabled=config.enable_rule_filter,
                llm_enabled=config.enable_llm_self_eval,
                utility_enabled=config.enable_retrieval_utility,
                diversity_enabled=config.enable_diversity,
                passed_rule=rule_pass_for_decision,
                passed_llm=llm_pass_for_decision,
                passed_utility=utility_pass_for_decision,
                passed_diversity=diversity_pass_for_decision,
                passed_final_score=final_pass_for_decision,
            )

            rejection_reasons = list(rule_reasons)
            if llm_stage_pass is False:
                rejection_reasons.append("llm_self_eval_failed")
            if utility_stage_pass is False:
                rejection_reasons.append("utility_failed")
            if diversity_stage_pass is False:
                rejection_reasons.append("diversity_failed")
            if passed_final_score is False:
                rejection_reasons.append("final_score_failed")

            if rule_stage_pass:
                stage_counter["rule_filter"] += 1
            if rule_stage_pass and llm_stage_pass:
                stage_counter["llm_self_eval"] += 1
            if rule_stage_pass and llm_stage_pass and utility_stage_pass:
                stage_counter["retrieval_utility"] += 1
            if rule_stage_pass and llm_stage_pass and utility_stage_pass and diversity_stage_pass:
                stage_counter["diversity_dedup"] += 1

            if passed:
                accepted_by_chunk[query.chunk_id_source].append(query_embedding)
                accepted_by_doc[query.target_doc_id].append(query_embedding)
                accepted_global.append(query_embedding)
                decision_counter["accepted"] += 1
                stage_counter["final_approved"] += 1
            else:
                decision_counter["rejected"] += 1
                for reason in rejection_reasons:
                    rejection_counter[reason] += 1
            processed_query_ids.add(query.synthetic_query_id)

            gated_query_id = _stable_id(
                [
                    query.synthetic_query_id,
                    config.gating_preset,
                    run_context.experiment_run_id,
                ]
            )
            with connection.cursor() as cursor:
                cursor.execute(
                    """
                    INSERT INTO synthetic_queries_gated (
                        gated_query_id,
                        synthetic_query_id,
                        gating_batch_id,
                        gating_preset,
                        passed_rule_filter,
                        passed_llm_self_eval,
                        passed_retrieval_utility,
                        passed_diversity,
                        final_decision,
                        llm_scores,
                        utility_score,
                        novelty_score,
                        final_score,
                        llm_provider,
                        llm_model,
                        rejected_stage,
                        rejected_reason,
                        rejection_reasons,
                        metadata
                    ) VALUES (
                        %s, %s, %s, %s, %s, %s, %s, %s,
                        %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s
                    )
                    ON CONFLICT (synthetic_query_id, gating_preset) DO UPDATE
                    SET gating_batch_id = EXCLUDED.gating_batch_id,
                        passed_rule_filter = EXCLUDED.passed_rule_filter,
                        passed_llm_self_eval = EXCLUDED.passed_llm_self_eval,
                        passed_retrieval_utility = EXCLUDED.passed_retrieval_utility,
                        passed_diversity = EXCLUDED.passed_diversity,
                        final_decision = EXCLUDED.final_decision,
                        llm_scores = EXCLUDED.llm_scores,
                        utility_score = EXCLUDED.utility_score,
                        novelty_score = EXCLUDED.novelty_score,
                        final_score = EXCLUDED.final_score,
                        llm_provider = EXCLUDED.llm_provider,
                        llm_model = EXCLUDED.llm_model,
                        rejected_stage = EXCLUDED.rejected_stage,
                        rejected_reason = EXCLUDED.rejected_reason,
                        rejection_reasons = EXCLUDED.rejection_reasons,
                        metadata = EXCLUDED.metadata
                    """,
                    (
                        gated_query_id,
                        query.synthetic_query_id,
                        gating_batch_id,
                        config.gating_preset,
                        passed_rule if config.enable_rule_filter else None,
                        passed_llm if config.enable_llm_self_eval else None,
                        passed_utility if config.enable_retrieval_utility else None,
                        passed_diversity if config.enable_diversity else None,
                        passed,
                        Jsonb(
                            {
                                "schema_version": "v1",
                                "scores": llm_scores,
                                "raw": llm_payload,
                                "prompt_version": self_eval_prompt.version,
                                "prompt_hash": self_eval_prompt.content_hash,
                                "provider": self_eval_client.config.provider,
                                "model": self_eval_client.config.model,
                            }
                        ),
                        utility_score,
                        novelty_score,
                        final_score,
                        self_eval_client.config.provider if config.enable_llm_self_eval else None,
                        self_eval_client.config.model if config.enable_llm_self_eval else None,
                        None if passed else rejected_stage,
                        None if passed or not rejection_reasons else rejection_reasons[0],
                        Jsonb(rejection_reasons),
                        Jsonb(
                            {
                                "generation_strategy": query.generation_strategy,
                                "experiment_run_id": run_context.experiment_run_id,
                                "gating_batch_id": gating_batch_id,
                                "rejected_stage": None if passed else rejected_stage,
                            }
                        ),
                    ),
                )
                if gating_batch_id:
                    cursor.execute(
                        """
                        INSERT INTO synthetic_query_gating_result (
                            gating_batch_id,
                            synthetic_query_id,
                            query_text,
                            query_type,
                            language_profile,
                            generation_strategy,
                            rule_pass,
                            llm_eval_score,
                            utility_score,
                            diversity_pass,
                            novelty_score,
                            final_score,
                            llm_provider,
                            llm_model,
                            accepted,
                            rejected_stage,
                            rejected_reason,
                            llm_scores,
                            stage_payload_json
                        ) VALUES (
                            %s, %s, %s, %s, %s, %s, %s, %s,
                            %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s
                        )
                        ON CONFLICT (gating_batch_id, synthetic_query_id) DO UPDATE
                        SET query_text = EXCLUDED.query_text,
                            query_type = EXCLUDED.query_type,
                            language_profile = EXCLUDED.language_profile,
                            generation_strategy = EXCLUDED.generation_strategy,
                            rule_pass = EXCLUDED.rule_pass,
                            llm_eval_score = EXCLUDED.llm_eval_score,
                            utility_score = EXCLUDED.utility_score,
                            diversity_pass = EXCLUDED.diversity_pass,
                            novelty_score = EXCLUDED.novelty_score,
                            final_score = EXCLUDED.final_score,
                            llm_provider = EXCLUDED.llm_provider,
                            llm_model = EXCLUDED.llm_model,
                            accepted = EXCLUDED.accepted,
                            rejected_stage = EXCLUDED.rejected_stage,
                            rejected_reason = EXCLUDED.rejected_reason,
                            llm_scores = EXCLUDED.llm_scores,
                            stage_payload_json = EXCLUDED.stage_payload_json
                        """,
                        (
                            gating_batch_id,
                            query.synthetic_query_id,
                            query.query_text,
                            query.query_type,
                            "code_mixed" if query.query_type == "code_mixed" else "ko",
                            query.generation_strategy,
                            passed_rule if config.enable_rule_filter else None,
                            llm_avg,
                            utility_score,
                            passed_diversity if config.enable_diversity else None,
                            novelty_score,
                            final_score,
                            self_eval_client.config.provider if config.enable_llm_self_eval else None,
                            self_eval_client.config.model if config.enable_llm_self_eval else None,
                            passed,
                            None if passed else rejected_stage,
                            None if passed or not rejection_reasons else rejection_reasons[0],
                            Jsonb(
                                {
                                    "scores": llm_scores,
                                    "raw": llm_payload,
                                }
                            ),
                            Jsonb(
                                {
                                    "rule_reasons": rule_reasons,
                                    "rejection_reasons": rejection_reasons,
                                    "all_rejection_reasons": rejection_reasons,
                                    "preset": config.gating_preset,
                                    "passed_rule_filter": passed_rule if config.enable_rule_filter else None,
                                    "passed_llm_self_eval": passed_llm if config.enable_llm_self_eval else None,
                                    "passed_retrieval_utility": passed_utility if config.enable_retrieval_utility else None,
                                    "passed_diversity": passed_diversity if config.enable_diversity else None,
                                    "llm_provider": self_eval_client.config.provider if config.enable_llm_self_eval else None,
                                    "llm_model": self_eval_client.config.model if config.enable_llm_self_eval else None,
                                }
                            ),
                        ),
                    )
                    cursor.execute(
                        """
                        INSERT INTO synthetic_query_gating_history (
                            gating_batch_id,
                            synthetic_query_id,
                            stage_name,
                            stage_order,
                            passed,
                            score,
                            reason,
                            payload_json
                        )
                        SELECT %s, %s, x.stage_name, x.stage_order, x.passed, x.score, x.reason, x.payload_json
                        FROM (
                            VALUES
                                ('rule_filter', 1, %s, NULL::double precision, %s, %s),
                                ('llm_self_eval', 2, %s, %s, %s, %s),
                                ('retrieval_utility', 3, %s, %s, %s, %s),
                                ('diversity_dedup', 4, %s, %s, %s, %s),
                                ('final_score', 5, %s, %s, %s, %s),
                                ('approved', 6, %s, %s, %s, %s)
                        ) AS x(stage_name, stage_order, passed, score, reason, payload_json)
                        """,
                        (
                            gating_batch_id,
                            query.synthetic_query_id,
                            rule_stage_pass,
                            ",".join(rule_reasons) if rule_reasons else None,
                            Jsonb({"enabled": config.enable_rule_filter}),
                            llm_stage_pass,
                            llm_avg,
                            None if llm_stage_pass is not False else "llm_self_eval_failed",
                            Jsonb(
                                {
                                    "scores": llm_scores,
                                    "raw": llm_payload,
                                    "enabled": config.enable_llm_self_eval,
                                    "provider": self_eval_client.config.provider if config.enable_llm_self_eval else None,
                                    "model": self_eval_client.config.model if config.enable_llm_self_eval else None,
                                }
                            ),
                            utility_stage_pass,
                            utility_score,
                            None if utility_stage_pass is not False else "utility_failed",
                            Jsonb({"threshold": config.utility_threshold, "enabled": config.enable_retrieval_utility}),
                            diversity_stage_pass,
                            novelty_score,
                            None if diversity_stage_pass is not False else "diversity_failed",
                            Jsonb(
                                {
                                    "same_chunk_threshold": config.diversity_threshold_same_chunk,
                                    "same_doc_threshold": config.diversity_threshold_same_doc,
                                    "enabled": config.enable_diversity,
                                }
                            ),
                            passed_final_score,
                            final_score,
                            None if passed_final_score is not False else "final_score_failed",
                            Jsonb({"threshold": config.final_score_threshold}),
                            passed,
                            final_score,
                            None if passed else rejected_stage,
                            Jsonb({"preset": config.gating_preset}),
                        ),
                    )

            if len(preview_rows) < 20:
                preview_rows.append(
                    {
                        "synthetic_query_id": query.synthetic_query_id,
                        "query_text": query.query_text,
                        "final_decision": passed,
                        "utility_score": round(utility_score, 4) if utility_score is not None else None,
                        "final_score": round(final_score, 4) if final_score is not None else None,
                        "rejection_reasons": rejection_reasons,
                    }
                )
            if (row_index + 1) % llm_batch_size == 0:
                connection.commit()

        processed_total = decision_counter["accepted"] + decision_counter["rejected"]
        summary = {
            "experiment_key": config.experiment_key,
            "experiment_run_id": run_context.experiment_run_id,
            "source_generation_run_id": generation_run_id,
            "gating_preset": config.gating_preset,
            "strategies": strategies,
            "processed_queries": processed_total,
            "accepted_queries": decision_counter["accepted"],
            "rejected_queries": decision_counter["rejected"],
            "resumed_processed_queries": processed_existing_count,
            "newly_processed_queries": max(0, processed_total - processed_existing_count),
            "rejection_reasons": dict(rejection_counter),
            "stage_funnel": dict(stage_counter),
            "self_eval_prompt": {
                "id": self_eval_prompt.prompt_name,
                "version": self_eval_prompt.version,
                "hash": self_eval_prompt.content_hash,
                "asset_id": self_eval_prompt.prompt_asset_id,
                "llm_provider": self_eval_client.config.provider if config.enable_llm_self_eval else None,
                "llm_model": self_eval_client.config.model if config.enable_llm_self_eval else None,
            },
            "preview": preview_rows,
        }
        if gating_batch_id:
            with connection.cursor() as cursor:
                cursor.execute(
                    "DELETE FROM quality_gating_stage_result WHERE gating_batch_id = %s",
                    (gating_batch_id,),
                )
                stage_rows = [
                    ("generated", 0, stage_counter["generated"], stage_counter["generated"], 0, {"stage": "generated"}),
                    (
                        "rule_filter",
                        1,
                        stage_counter["generated"],
                        stage_counter["rule_filter"],
                        max(0, stage_counter["generated"] - stage_counter["rule_filter"]),
                        {"enabled": config.enable_rule_filter},
                    ),
                    (
                        "llm_self_eval",
                        2,
                        stage_counter["rule_filter"],
                        stage_counter["llm_self_eval"],
                        max(0, stage_counter["rule_filter"] - stage_counter["llm_self_eval"]),
                        {"enabled": config.enable_llm_self_eval},
                    ),
                    (
                        "retrieval_utility",
                        3,
                        stage_counter["llm_self_eval"],
                        stage_counter["retrieval_utility"],
                        max(0, stage_counter["llm_self_eval"] - stage_counter["retrieval_utility"]),
                        {"enabled": config.enable_retrieval_utility},
                    ),
                    (
                        "diversity_dedup",
                        4,
                        stage_counter["retrieval_utility"],
                        stage_counter["diversity_dedup"],
                        max(0, stage_counter["retrieval_utility"] - stage_counter["diversity_dedup"]),
                        {"enabled": config.enable_diversity},
                    ),
                    (
                        "final_approved",
                        5,
                        stage_counter["diversity_dedup"],
                        stage_counter["final_approved"],
                        max(0, stage_counter["diversity_dedup"] - stage_counter["final_approved"]),
                        {"threshold": config.final_score_threshold},
                    ),
                ]
                for stage_name, stage_order, input_count, passed_count, rejected_count, metrics in stage_rows:
                    cursor.execute(
                        """
                        INSERT INTO quality_gating_stage_result (
                            gating_batch_id,
                            stage_name,
                            stage_order,
                            input_count,
                            passed_count,
                            rejected_count,
                            metrics_json
                        ) VALUES (%s, %s, %s, %s, %s, %s, %s)
                        ON CONFLICT (gating_batch_id, stage_name) DO UPDATE
                        SET stage_order = EXCLUDED.stage_order,
                            input_count = EXCLUDED.input_count,
                            passed_count = EXCLUDED.passed_count,
                            rejected_count = EXCLUDED.rejected_count,
                            metrics_json = EXCLUDED.metrics_json
                        """,
                        (
                            gating_batch_id,
                            stage_name,
                            stage_order,
                            input_count,
                            passed_count,
                            rejected_count,
                            Jsonb(metrics),
                        ),
                    )
        recorder.finish_run(run_context, status="completed", metrics=summary)
        connection.commit()
        return summary
    except Exception as exception:  # noqa: BLE001
        connection.rollback()
        LOGGER.exception("Quality gating failed.")
        raise exception
    finally:
        connection.close()


def run_quality_gating_from_env(experiment: str) -> dict[str, Any]:
    defaults = default_database_args()
    return run_quality_gating(
        experiment=experiment,
        database_url=defaults["database_url"],
        db_host=defaults["db_host"],
        db_port=defaults["db_port"],
        db_name=defaults["db_name"],
        db_user=defaults["db_user"],
        db_password=defaults["db_password"],
    )
