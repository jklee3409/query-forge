from __future__ import annotations

import csv
import json
import logging
import math
import os
import re
import statistics
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Any

import psycopg
from psycopg.types.json import Jsonb

try:
    from common.anchor_normalization import canonical_anchor_version_payload
    from common.experiment_config import load_experiment_config
    from common.experiment_run import ExperimentRunRecorder
    from common.text_utils import copy_ratio, extract_extractive_summary
    from loaders.common import connect, default_database_args
    from eval.runtime import (
        DbAnnRuntimeRetrievalAdapter,
        RewriteOutcome,
        derive_eval_corpus_scope,
        load_chunk_items,
        load_eval_samples,
        load_memory_items,
        load_multi_source_anchor_index,
        normalize_retrieval_backend,
        rerank_retrieval_candidates,
        retrieve_top_k,
        run_selective_rewrite,
        runtime_retriever_label,
        runtime_retriever_metadata,
    )
except ModuleNotFoundError:  # pragma: no cover
    from pipeline.common.anchor_normalization import canonical_anchor_version_payload
    from pipeline.common.experiment_config import load_experiment_config
    from pipeline.common.experiment_run import ExperimentRunRecorder
    from pipeline.common.text_utils import copy_ratio, extract_extractive_summary
    from pipeline.loaders.common import connect, default_database_args
    from pipeline.eval.runtime import (
        DbAnnRuntimeRetrievalAdapter,
        RewriteOutcome,
        derive_eval_corpus_scope,
        load_chunk_items,
        load_eval_samples,
        load_memory_items,
        load_multi_source_anchor_index,
        normalize_retrieval_backend,
        rerank_retrieval_candidates,
        retrieve_top_k,
        run_selective_rewrite,
        runtime_retriever_label,
        runtime_retriever_metadata,
    )


LOGGER = logging.getLogger(__name__)
TOKEN_PATTERN = re.compile(r"[A-Za-z0-9_./-]+|[가-힣]+")


def _tokens(text: str) -> set[str]:
    return {token.lower() for token in TOKEN_PATTERN.findall(text or "")}


def _overlap_ratio(left: str, right: str) -> float:
    left_tokens = _tokens(left)
    right_tokens = _tokens(right)
    if not left_tokens:
        return 0.0
    return len(left_tokens & right_tokens) / len(left_tokens)


def _mean(values: list[float]) -> float:
    if not values:
        return 0.0
    return float(statistics.fmean(values))


def _resolve_csv_fieldnames(rows: list[dict[str, Any]], fieldnames: list[str]) -> list[str]:
    resolved = list(fieldnames or [])
    known = set(resolved)
    for row in rows:
        if not isinstance(row, dict):
            continue
        for key in row.keys():
            if key not in known:
                resolved.append(key)
                known.add(key)
    return resolved


def _write_csv(path: Path, rows: list[dict[str, Any]], fieldnames: list[str]) -> None:
    resolved_fieldnames = _resolve_csv_fieldnames(rows, fieldnames)
    if resolved_fieldnames != fieldnames:
        appended = [name for name in resolved_fieldnames if name not in set(fieldnames or [])]
        LOGGER.warning("Extending CSV fieldnames for %s with dynamic keys: %s", path, appended)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as target:
        writer = csv.DictWriter(target, fieldnames=resolved_fieldnames, extrasaction="ignore")
        writer.writeheader()
        for row in rows:
            writer.writerow(row)


def _normalize_latency_ms(value: Any) -> float | None:
    if value is None:
        return None
    try:
        parsed = float(value)
    except (TypeError, ValueError):
        return None
    if not math.isfinite(parsed) or parsed < 0.0:
        return None
    return parsed


def _average_latency_ms(values: list[Any]) -> tuple[float | None, int]:
    normalized = [
        latency
        for latency in (_normalize_latency_ms(value) for value in values)
        if latency is not None
    ]
    if not normalized:
        return None, 0
    return _mean(normalized), len(normalized)


def _build_latency_summary(sample_rows: list[dict[str, Any]]) -> dict[str, Any]:
    total_sample_count = len(sample_rows)
    avg_query_eval_total_latency_ms, eval_sample_count = _average_latency_ms(
        [row.get("query_eval_total_latency_ms") for row in sample_rows]
    )
    avg_final_rewrite_latency_ms, rewrite_sample_count = _average_latency_ms(
        [row.get("final_rewrite_latency_ms") for row in sample_rows]
    )
    avg_pure_rewrite_latency_ms, pure_rewrite_sample_count = _average_latency_ms(
        [row.get("pure_rewrite_latency_ms") for row in sample_rows]
    )
    return {
        "avg_query_eval_total_latency_ms": avg_query_eval_total_latency_ms,
        "avg_final_rewrite_latency_ms": avg_final_rewrite_latency_ms,
        "avg_pure_rewrite_latency_ms": avg_pure_rewrite_latency_ms,
        "eval_sample_count": eval_sample_count,
        "rewrite_sample_count": rewrite_sample_count,
        "pure_rewrite_sample_count": pure_rewrite_sample_count,
        "excluded_sample_count": max(0, total_sample_count - eval_sample_count),
    }


def _resolve_eval_concurrency(raw_config: dict[str, Any]) -> int:
    value = (
        raw_config.get("answer_eval_concurrency")
        or raw_config.get("eval_concurrency")
        or os.getenv("QUERY_FORGE_ANSWER_EVAL_CONCURRENCY")
        or os.getenv("QUERY_FORGE_EVAL_CONCURRENCY")
        or os.getenv("QUERY_FORGE_LLM_CONCURRENCY_LIMIT")
        or 4
    )
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        parsed = 4
    return max(1, min(parsed, 32))


def _is_rewrite_anchor_injection_enabled(raw_config: dict[str, Any]) -> bool:
    value = raw_config.get("rewrite_anchor_injection_enabled", True)
    if isinstance(value, bool):
        return value
    normalized = str(value or "").strip().lower()
    if normalized in {"", "true", "1", "yes", "y", "on"}:
        return True
    if normalized in {"false", "0", "no", "n", "off"}:
        return False
    return True


def _is_multi_source_anchor_expansion_enabled(raw_config: dict[str, Any]) -> bool:
    value = raw_config.get("multi_source_anchor_expansion_enabled", False)
    if isinstance(value, bool):
        return value
    normalized = str(value or "").strip().lower()
    return normalized in {"true", "1", "yes", "y", "on"}


def _multi_source_relation_types(raw_config: dict[str, Any]) -> list[str]:
    value = raw_config.get("multi_source_anchor_relation_types")
    if isinstance(value, str):
        return [item.strip() for item in value.split(",") if item.strip()]
    if isinstance(value, list):
        return [str(item).strip() for item in value if str(item).strip()]
    return ["canonical_alias", "synthetic_query_cooccurrence", "chunk_cooccurrence"]


def _multi_source_anchor_diagnostics(payload: Any) -> dict[str, Any]:
    if not isinstance(payload, dict):
        return {}
    diagnostics = payload.get("diagnostics")
    return diagnostics if isinstance(diagnostics, dict) else {}


def _evaluate_answer_sample(
    *,
    sample: Any,
    chunks: Any,
    memories: Any,
    config: Any,
    rewrite_enabled: bool,
    selective_rewrite: bool,
    gating_applied: bool,
    source_gating_run_id: str | None,
    memory_strategy_filters: list[str],
    retrieval_adapter: DbAnnRuntimeRetrievalAdapter | None,
    multi_source_anchor_index: Any | None,
) -> dict[str, Any]:
    # query_eval_total_latency_ms:
    #   measured from per-sample evaluation entry through answer text/metric/sample-row
    #   assembly, excluding later DB flush / CSV write / report serialization.
    started = time.perf_counter()
    if rewrite_enabled:
        rewrite_outcome, retrieval = run_selective_rewrite(
            raw_query=sample.query_text,
            query_language=sample.query_language,
            query_category=sample.query_category,
            session_context=sample.dialog_context if config.use_session_context else {},
            chunks=chunks,
            memories=memories,
            memory_top_n_value=config.memory_top_n,
            candidate_count=config.rewrite_candidate_count,
            threshold=config.rewrite_threshold,
            retrieval_top_k=config.retrieval_top_k,
            preset_filter=config.gating_preset if gating_applied else "ungated",
            source_gate_run_id=source_gating_run_id,
            strategy_filters=memory_strategy_filters,
            force_rewrite=not selective_rewrite,
            rewrite_retrieval_strategy=str(config.raw.get("rewrite_retrieval_strategy") or "replace"),
            rewrite_anchor_injection_enabled=_is_rewrite_anchor_injection_enabled(config.raw),
            rewrite_terminology_hints_max_count=config.raw.get("rewrite_terminology_hints_max_count", 12),
            multi_source_anchor_expansion_enabled=_is_multi_source_anchor_expansion_enabled(config.raw),
            multi_source_anchor_index=multi_source_anchor_index,
            multi_source_anchor_relation_types=_multi_source_relation_types(config.raw),
            multi_source_anchor_min_score=config.raw.get("multi_source_anchor_min_score", 0.72),
            multi_source_anchor_max_per_seed=config.raw.get("multi_source_anchor_max_per_seed", 2),
            multi_source_anchor_max_total=config.raw.get("multi_source_anchor_max_total", 8),
            rewrite_failure_policy=str(config.raw.get("rewrite_failure_policy") or "fail_run"),
            rewrite_adoption_policy=config.rewrite_adoption_policy,
            retriever_config=config.retriever_config,
            retrieval_adapter=retrieval_adapter,
        )
    else:
        retrieval = retrieve_top_k(
            sample.query_text,
            chunks,
            top_k=config.retrieval_top_k,
            retriever_config=config.retriever_config,
            retrieval_adapter=retrieval_adapter,
        )
        rewrite_outcome = RewriteOutcome(
            final_query=sample.query_text,
            rewrite_applied=False,
            rewrite_reason="rewrite_disabled",
            raw_confidence=0.0,
            best_candidate_confidence=0.0,
            memory_top_n=[],
            candidates=[],
            final_rewrite_latency_ms=None,
            pure_rewrite_latency_ms=None,
        )
    reranked = rerank_retrieval_candidates(
        rewrite_outcome.final_query if rewrite_enabled else sample.query_text,
        retrieval,
        top_n=config.rerank_top_n,
        retriever_config=config.retriever_config,
    )
    answer_segments = [
        extract_extractive_summary(item.text, max_sentences=1)
        for item in reranked[:2]
    ]
    answer_text = " ".join(segment for segment in answer_segments if segment).strip()

    expected_points = " ".join([point for point in sample.expected_answer_key_points])
    if not expected_points:
        expected_points = " ".join(sample.expected_chunk_ids)
    correctness = _overlap_ratio(expected_points, answer_text)
    keyword_overlap = _overlap_ratio(
        " ".join(sample.expected_chunk_ids + sample.expected_doc_ids),
        answer_text,
    )
    answer_relevance = _overlap_ratio(sample.query_text, answer_text)
    context_blob = " ".join([item.text for item in reranked])
    grounding = _overlap_ratio(answer_text, context_blob)
    hallucination_rate = max(0.0, 1.0 - grounding)
    faithfulness = 1.0 - min(1.0, copy_ratio(answer_text, context_blob, ngram=3))

    covered_expected = sum(
        1 for expected_chunk_id in sample.expected_chunk_ids if any(item.chunk_id == expected_chunk_id for item in reranked)
    )
    context_recall = covered_expected / max(1, len(sample.expected_chunk_ids))
    context_precision = covered_expected / max(1, len(reranked))

    metrics = {
        "correctness": correctness,
        "grounding": grounding,
        "hallucination_rate": hallucination_rate,
        "keyword_overlap": keyword_overlap,
        "answer_relevance": answer_relevance,
        "faithfulness": faithfulness,
        "context_precision": context_precision,
        "context_recall": context_recall,
        "rewrite_applied": rewrite_outcome.rewrite_applied,
    }
    query_eval_total_latency_ms = (time.perf_counter() - started) * 1000.0
    metrics["query_eval_total_latency_ms"] = query_eval_total_latency_ms
    metrics["final_rewrite_latency_ms"] = rewrite_outcome.final_rewrite_latency_ms
    metrics["pure_rewrite_latency_ms"] = rewrite_outcome.pure_rewrite_latency_ms
    multi_source_diagnostics = _multi_source_anchor_diagnostics(rewrite_outcome.multi_source_anchor_hints)
    return {
        "sample": sample,
        "metrics": metrics,
        "sample_row": {
            "sample_id": sample.sample_id,
            "split": sample.split,
            "category": sample.query_category,
            "final_query": rewrite_outcome.final_query,
            "answer_text": answer_text,
            "rewrite_llm_attempted": bool(rewrite_outcome.rewrite_llm_attempted),
            "rewrite_llm_succeeded": bool(rewrite_outcome.rewrite_llm_succeeded),
            "rewrite_heuristic_fallback_used": bool(rewrite_outcome.rewrite_heuristic_fallback_used),
            "query_eval_total_latency_ms": query_eval_total_latency_ms,
            "final_rewrite_latency_ms": rewrite_outcome.final_rewrite_latency_ms,
            "pure_rewrite_latency_ms": rewrite_outcome.pure_rewrite_latency_ms,
            "expanded_anchor_count": multi_source_diagnostics.get("candidate_expanded_anchor_count", 0),
            "accepted_expanded_anchor_count": multi_source_diagnostics.get("accepted_expanded_anchor_count", 0),
            **metrics,
        },
        "reranked": reranked,
        "final_query": rewrite_outcome.final_query,
    }


def run_answer_eval(
    *,
    experiment: str,
    experiment_root: Path = Path("configs/experiments"),
    output_root: Path = Path("data/reports"),
    docs_root: Path = Path("docs/experiments"),
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
                "stage": "eval-answer",
                "retriever_config": config.retriever_config.to_metadata(),
                "retrieval_backend": normalize_retrieval_backend(str(config.raw.get("retrieval_backend") or "")),
            },
            run_label="eval-answer",
        )

        dataset_id = str(config.raw.get("dataset_id") or "").strip() or None
        synthetic_free_baseline = bool(config.raw.get("synthetic_free_baseline", False))
        memory_strategy_filters = [
            str(item).upper()
            for item in (config.raw.get("memory_generation_strategies") or [])
            if str(item).strip()
        ]
        source_gating_run_id = str(config.raw.get("source_gating_run_id") or "").strip() or None
        rewrite_enabled = bool(config.raw.get("rewrite_enabled", True))
        selective_rewrite = bool(config.raw.get("selective_rewrite", True))
        gating_applied = bool(config.raw.get("gating_applied", True))
        if synthetic_free_baseline:
            memory_strategy_filters = []
            source_gating_run_id = None
            rewrite_enabled = False
            selective_rewrite = False
            gating_applied = False
        eval_concurrency = _resolve_eval_concurrency(config.raw)
        retrieval_backend = normalize_retrieval_backend(str(config.raw.get("retrieval_backend") or "local"))
        eval_query_language = str(config.raw.get("eval_query_language") or "ko").strip().lower()
        samples = load_eval_samples(connection, dataset_id=dataset_id, query_language=eval_query_language)
        LOGGER.info(
            "answer_eval_parallelism samples=%s concurrency=%s",
            len(samples),
            eval_concurrency,
        )
        dataset_scope = derive_eval_corpus_scope(samples) if dataset_id else {
            "source_products": set(),
            "product_filters": set(),
            "expected_doc_ids": set(),
        }
        allowed_products = dataset_scope["product_filters"]
        expected_doc_ids = dataset_scope["expected_doc_ids"]
        if dataset_id and not allowed_products and expected_doc_ids:
            LOGGER.warning(
                "answer_eval_dataset_scope_missing_source_products dataset_id=%s expected_doc_ids=%s; "
                "falling back to expected_doc_ids-only chunk scope",
                dataset_id,
                len(expected_doc_ids),
            )
        elif dataset_id and not allowed_products and not expected_doc_ids:
            LOGGER.warning(
                "answer_eval_dataset_scope_empty dataset_id=%s; falling back to full corpus",
                dataset_id,
            )
        retrieval_adapter: DbAnnRuntimeRetrievalAdapter | None = None
        if retrieval_backend == "db_ann":
            retrieval_adapter = DbAnnRuntimeRetrievalAdapter(
                connection,
                allowed_products=sorted(allowed_products) if dataset_id else None,
                include_document_ids=sorted(expected_doc_ids) if dataset_id else None,
                memory_experiment_key=config.experiment_key,
                retriever_config=config.retriever_config,
            )
            chunks = []
        else:
            chunks = load_chunk_items(
                connection,
                allowed_products=allowed_products if dataset_id else None,
                include_document_ids=expected_doc_ids if dataset_id else None,
            )
        if dataset_id:
            LOGGER.info(
                "answer_eval_dataset_scope dataset_id=%s source_products=%s product_filters=%s expected_doc_ids=%s loaded_chunks=%s",
                dataset_id,
                len(dataset_scope["source_products"]),
                len(allowed_products),
                len(expected_doc_ids),
                len(chunks),
            )
        memories = []
        if rewrite_enabled and retrieval_backend != "db_ann":
            memories = load_memory_items(connection, memory_experiment_key=config.experiment_key)
        retriever_metadata = runtime_retriever_metadata(
            retriever_config=config.retriever_config,
            retrieval_adapter=retrieval_adapter,
        )
        multi_source_anchor_index = None
        if _is_multi_source_anchor_expansion_enabled(config.raw):
            multi_source_anchor_index = load_multi_source_anchor_index(
                connection,
                relation_version=str(config.raw.get("multi_source_anchor_relation_version") or "multi-source-anchor-v1"),
                relation_types=_multi_source_relation_types(config.raw),
                min_relation_score=config.raw.get("multi_source_anchor_min_score", 0.72),
            )

        sample_rows: list[dict[str, Any]] = []
        with connection.cursor() as cursor:
            cursor.execute(
                """
                DELETE FROM eval_judgments
                WHERE evaluator_type = 'rule'
                  AND notes = %s
                """,
                (f"answer_eval:{config.experiment_key}",),
            )

        evaluated: list[dict[str, Any]] = []
        if eval_concurrency <= 1 or len(samples) <= 1:
            for sample in samples:
                evaluated.append(
                    _evaluate_answer_sample(
                        sample=sample,
                        chunks=chunks,
                        memories=memories,
                        config=config,
                        rewrite_enabled=rewrite_enabled,
                        selective_rewrite=selective_rewrite,
                        gating_applied=gating_applied,
                        source_gating_run_id=source_gating_run_id,
                        memory_strategy_filters=memory_strategy_filters,
                        retrieval_adapter=retrieval_adapter,
                        multi_source_anchor_index=multi_source_anchor_index,
                    )
                )
        else:
            with ThreadPoolExecutor(max_workers=min(eval_concurrency, len(samples))) as pool:
                futures = [
                    pool.submit(
                        _evaluate_answer_sample,
                        sample=sample,
                        chunks=chunks,
                        memories=memories,
                        config=config,
                        rewrite_enabled=rewrite_enabled,
                        selective_rewrite=selective_rewrite,
                        gating_applied=gating_applied,
                        source_gating_run_id=source_gating_run_id,
                        memory_strategy_filters=memory_strategy_filters,
                        retrieval_adapter=retrieval_adapter,
                        multi_source_anchor_index=multi_source_anchor_index,
                    )
                    for sample in samples
                ]
                for future in as_completed(futures):
                    evaluated.append(future.result())

        evaluated.sort(key=lambda row: row["sample"].sample_id)
        sample_rows = [row["sample_row"] for row in evaluated]

        with connection.cursor() as cursor:
            for row in evaluated:
                sample = row["sample"]
                metrics = row["metrics"]
                reranked = row["reranked"]
                final_query = row["final_query"]
                cursor.execute(
                    """
                    INSERT INTO eval_judgments (
                        sample_id,
                        experiment_run_id,
                        evaluator_type,
                        metrics,
                        notes
                    ) VALUES (%s, %s, 'rule', %s, %s)
                    """,
                    (
                        sample.sample_id,
                        run_context.experiment_run_id,
                        Jsonb(metrics),
                        f"answer_eval:{config.experiment_key}",
                    ),
                )
                for rank, item in enumerate(reranked, start=1):
                    cursor.execute(
                        """
                        INSERT INTO rerank_results (
                            eval_sample_id,
                            rank,
                            document_id,
                            chunk_id,
                            model_name,
                            relevance_score,
                            metadata
                        ) VALUES (%s, %s, %s, %s, %s, %s, %s)
                        """,
                        (
                            sample.sample_id,
                            rank,
                            None,
                            None,
                            f"cohere-or-{runtime_retriever_label(retriever_config=config.retriever_config, retrieval_adapter=retrieval_adapter)}",
                            item.score,
                            Jsonb(
                                {
                                    "experiment_run_id": run_context.experiment_run_id,
                                    "final_query": final_query,
                                    "retrieved_document_id": item.document_id,
                                    "retrieved_chunk_id": item.chunk_id,
                                    **retriever_metadata,
                                }
                            ),
                        ),
                    )

        summary = {
            "correctness": _mean([row["correctness"] for row in sample_rows]),
            "grounding": _mean([row["grounding"] for row in sample_rows]),
            "hallucination_rate": _mean([row["hallucination_rate"] for row in sample_rows]),
            "keyword_overlap": _mean([row["keyword_overlap"] for row in sample_rows]),
            "answer_relevance": _mean([row["answer_relevance"] for row in sample_rows]),
            "faithfulness": _mean([row["faithfulness"] for row in sample_rows]),
            "context_precision": _mean([row["context_precision"] for row in sample_rows]),
            "context_recall": _mean([row["context_recall"] for row in sample_rows]),
            "rewrite_adoption_rate": _mean([1.0 if row["rewrite_applied"] else 0.0 for row in sample_rows]),
        }
        multi_source_anchor_diagnostics = {
            "expanded_anchor_count": sum(int(row.get("expanded_anchor_count") or 0) for row in sample_rows),
            "accepted_expanded_anchor_count": sum(
                int(row.get("accepted_expanded_anchor_count") or 0)
                for row in sample_rows
            ),
        }
        performance = _build_latency_summary(sample_rows)
        rewrite_generation_stats = {
            "rewrite_llm_attempted_count": sum(1 for row in sample_rows if row.get("rewrite_llm_attempted")),
            "rewrite_llm_success_count": sum(1 for row in sample_rows if row.get("rewrite_llm_succeeded")),
            "rewrite_llm_failure_count": sum(
                1 for row in sample_rows
                if row.get("rewrite_llm_attempted") and not row.get("rewrite_llm_succeeded")
            ),
            "rewrite_heuristic_fallback_count": sum(
                1 for row in sample_rows if row.get("rewrite_heuristic_fallback_used")
            ),
        }
        rewrite_generation_stats["llm_attempted_count"] = rewrite_generation_stats["rewrite_llm_attempted_count"]
        rewrite_generation_stats["llm_success_count"] = rewrite_generation_stats["rewrite_llm_success_count"]
        rewrite_generation_stats["llm_failure_count"] = rewrite_generation_stats["rewrite_llm_failure_count"]
        rewrite_generation_stats["heuristic_fallback_count"] = rewrite_generation_stats["rewrite_heuristic_fallback_count"]

        canonical_version_payload = canonical_anchor_version_payload(config.raw)
        summary_payload = {
            "experiment_key": config.experiment_key,
            "experiment_run_id": run_context.experiment_run_id,
            "dataset_id": dataset_id,
            **canonical_version_payload,
            "canonical_anchor_versions": canonical_version_payload,
            "source_gating_run_id": source_gating_run_id,
            "memory_generation_strategies": memory_strategy_filters,
            "synthetic_free_baseline": synthetic_free_baseline,
            "rewrite_enabled": rewrite_enabled,
            "selective_rewrite": selective_rewrite,
            "memory_experiment_key": config.experiment_key if rewrite_enabled else None,
            "memory_entry_count_loaded": None if retrieval_backend == "db_ann" else len(memories),
            **retriever_metadata,
            "rewrite_llm_attempted_count": int(rewrite_generation_stats["rewrite_llm_attempted_count"]),
            "rewrite_llm_success_count": int(rewrite_generation_stats["rewrite_llm_success_count"]),
            "rewrite_llm_failure_count": int(rewrite_generation_stats["rewrite_llm_failure_count"]),
            "rewrite_heuristic_fallback_count": int(rewrite_generation_stats["rewrite_heuristic_fallback_count"]),
            "rewrite_generation_stats": rewrite_generation_stats,
            "multi_source_anchor_expansion_enabled": _is_multi_source_anchor_expansion_enabled(config.raw),
            "multi_source_anchor_diagnostics": multi_source_anchor_diagnostics,
            "summary": summary,
            "performance": performance,
            "sample_count": len(sample_rows),
        }
        output_root.mkdir(parents=True, exist_ok=True)
        summary_json_path = output_root / f"answer_summary_{config.experiment_key}.json"
        summary_csv_path = output_root / f"answer_summary_{config.experiment_key}.csv"
        detail_csv_path = output_root / f"answer_detail_{config.experiment_key}.csv"
        summary_json_path.write_text(
            json.dumps(summary_payload, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        _write_csv(
            summary_csv_path,
            [{"metric": key, "value": value} for key, value in summary.items()],
            ["metric", "value"],
        )
        _write_csv(
            detail_csv_path,
            sample_rows,
            [
                "sample_id",
                "split",
                "category",
                "final_query",
                "answer_text",
                "correctness",
                "grounding",
                "hallucination_rate",
                "keyword_overlap",
                "answer_relevance",
                "faithfulness",
                "context_precision",
                "context_recall",
                "rewrite_applied",
                "query_eval_total_latency_ms",
                "final_rewrite_latency_ms",
                "pure_rewrite_latency_ms",
            ],
        )

        answer_report_path = docs_root / "latest_answer_report.md"
        answer_report_path.parent.mkdir(parents=True, exist_ok=True)
        answer_report_path.write_text(
            "# Latest Answer Report\n\n"
            f"- experiment_key: `{config.experiment_key}`\n"
            f"- sample_count: `{len(sample_rows)}`\n\n"
            "| metric | value |\n"
            "| --- | ---: |\n"
            + "\n".join([f"| {key} | {value:.4f} |" for key, value in summary.items()])
            + "\n",
            encoding="utf-8",
        )

        recorder.finish_run(
            run_context,
            status="completed",
            metrics={
                **summary_payload,
                "report_paths": {
                    "summary_json": str(summary_json_path),
                    "summary_csv": str(summary_csv_path),
                    "detail_csv": str(detail_csv_path),
                    "answer_report": str(answer_report_path),
                },
            },
        )
        connection.commit()
        return summary_payload
    except Exception as exception:  # noqa: BLE001
        connection.rollback()
        LOGGER.exception("Answer evaluation failed.")
        raise exception
    finally:
        connection.close()


def run_answer_eval_from_env(experiment: str) -> dict[str, Any]:
    defaults = default_database_args()
    return run_answer_eval(
        experiment=experiment,
        database_url=defaults["database_url"],
        db_host=defaults["db_host"],
        db_port=defaults["db_port"],
        db_name=defaults["db_name"],
        db_user=defaults["db_user"],
        db_password=defaults["db_password"],
    )
