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
    from common.embeddings import cosine_similarity, embed_text
    from common.experiment_config import ExperimentConfig, load_experiment_config
    from common.experiment_run import ExperimentRunRecorder
    from common.llm_client import LlmClient, load_stage_config
    from common.prompt_assets import load_and_register_prompt
    from common.text_utils import copy_ratio, korean_ratio, special_char_ratio, token_count
    from loaders.common import connect, default_database_args
except ModuleNotFoundError:  # pragma: no cover - direct module execution fallback
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
            FROM synthetic_queries_raw
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
            FROM synthetic_queries_raw
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
) -> float:
    ranked = _rank_chunks(query_embedding, chunks, top_k=5)
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


def _rule_pass(query: RawQueryRow, source_chunk: ChunkItem) -> tuple[bool, list[str]]:
    reasons: list[str] = []
    stripped = query.query_text.strip()
    length = len(stripped)
    min_len = 4 if query.query_type in SHORT_TYPES else 8
    max_len = 60 if query.query_type in SHORT_TYPES else 100
    if length < min_len or length > max_len:
        reasons.append("length_out_of_range")

    tokens = token_count(stripped)
    if tokens < 2 or tokens > 20:
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
    min_korean_ratio = 0.20 if query.query_type == "code_mixed" else 0.40
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
        chunks, chunk_index = _load_chunk_items(connection)

        accepted_by_chunk: dict[str, list[list[float]]] = defaultdict(list)
        accepted_by_doc: dict[str, list[list[float]]] = defaultdict(list)
        accepted_global: list[list[float]] = []

        decision_counter: Counter[str] = Counter()
        rejection_counter: Counter[str] = Counter()
        preview_rows: list[dict[str, Any]] = []
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

        llm_batch_size = int(config.raw.get("llm_batch_size") or 20)
        llm_batch_size = max(1, min(llm_batch_size, 20))
        for row_index, query in enumerate(raw_queries):
            source_chunk = chunk_index.get(query.chunk_id_source)
            if source_chunk is None:
                continue
            query_embedding = embed_text(query.query_text)

            passed_rule, rule_reasons = _rule_pass(query, source_chunk)
            llm_payload: dict[str, Any] = {"schema_version": "v1", "scores": {}}
            if config.enable_llm_self_eval:
                llm_scores, llm_payload = _llm_self_eval(
                    query,
                    source_chunk,
                    client=self_eval_client,
                    prompt_text=self_eval_prompt_text,
                )
                passed_llm = _llm_pass(llm_scores)
                llm_avg = sum(llm_scores.values()) / (len(llm_scores) * 5.0)
            else:
                llm_scores = {
                    "grounded": 5,
                    "answerable": 5,
                    "user_like": 5,
                    "korean_naturalness": 5,
                    "copy_control": 5,
                }
                passed_llm = True
                llm_avg = 1.0

            utility_score = _retrieval_utility_score(
                query_embedding=query_embedding,
                query=query,
                chunks=chunks,
                utility_weights=config.retrieval_utility_weights,
            )
            passed_utility = utility_score >= config.utility_threshold

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
            passed_diversity = (
                max_chunk_similarity <= config.diversity_threshold_same_chunk
                and max_doc_similarity <= config.diversity_threshold_same_doc
            )

            final_score = (
                config.gating_weights["utility"] * utility_score
                + config.gating_weights["llm"] * llm_avg
                + config.gating_weights["novelty"] * novelty_score
            )
            passed_final_score = final_score >= config.final_score_threshold

            passed = _preset_pass(
                config.gating_preset,
                rule_pass=passed_rule if config.enable_rule_filter else True,
                llm_pass=passed_llm if config.enable_llm_self_eval else True,
                utility_pass=passed_utility if config.enable_retrieval_utility else True,
                diversity_pass=passed_diversity if config.enable_diversity else True,
                final_pass=passed_final_score,
            )
            rejected_stage = _first_rejection_stage(
                rule_enabled=config.enable_rule_filter,
                llm_enabled=config.enable_llm_self_eval,
                utility_enabled=config.enable_retrieval_utility,
                diversity_enabled=config.enable_diversity,
                passed_rule=passed_rule,
                passed_llm=passed_llm,
                passed_utility=passed_utility,
                passed_diversity=passed_diversity,
                passed_final_score=passed_final_score,
            )

            rejection_reasons = list(rule_reasons)
            if config.enable_llm_self_eval and not passed_llm:
                rejection_reasons.append("llm_self_eval_failed")
            if config.enable_retrieval_utility and not passed_utility:
                rejection_reasons.append("utility_failed")
            if config.enable_diversity and not passed_diversity:
                rejection_reasons.append("diversity_failed")
            if not passed_final_score:
                rejection_reasons.append("final_score_failed")

            rule_stage_pass = passed_rule if config.enable_rule_filter else True
            llm_stage_pass = passed_llm if config.enable_llm_self_eval else True
            utility_stage_pass = passed_utility if config.enable_retrieval_utility else True
            diversity_stage_pass = passed_diversity if config.enable_diversity else True
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
                                    "all_rejection_reasons": rejection_reasons,
                                    "preset": config.gating_preset,
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
                            None if llm_stage_pass else "llm_self_eval_failed",
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
                            None if utility_stage_pass else "utility_failed",
                            Jsonb({"threshold": config.utility_threshold, "enabled": config.enable_retrieval_utility}),
                            diversity_stage_pass,
                            novelty_score,
                            None if diversity_stage_pass else "diversity_failed",
                            Jsonb(
                                {
                                    "same_chunk_threshold": config.diversity_threshold_same_chunk,
                                    "same_doc_threshold": config.diversity_threshold_same_doc,
                                    "enabled": config.enable_diversity,
                                }
                            ),
                            passed_final_score,
                            final_score,
                            None if passed_final_score else "final_score_failed",
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
                        "utility_score": round(utility_score, 4),
                        "final_score": round(final_score, 4),
                        "rejection_reasons": rejection_reasons,
                    }
                )
            if (row_index + 1) % llm_batch_size == 0:
                connection.commit()

        summary = {
            "experiment_key": config.experiment_key,
            "experiment_run_id": run_context.experiment_run_id,
            "source_generation_run_id": generation_run_id,
            "gating_preset": config.gating_preset,
            "strategies": strategies,
            "processed_queries": len(raw_queries),
            "accepted_queries": decision_counter["accepted"],
            "rejected_queries": decision_counter["rejected"],
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
