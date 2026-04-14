from __future__ import annotations

import csv
import json
import logging
import os
import re
import statistics
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Any

import psycopg
from psycopg.types.json import Jsonb

try:
    from common.experiment_config import load_experiment_config
    from common.experiment_run import ExperimentRunRecorder
    from common.text_utils import copy_ratio, extract_extractive_summary
    from loaders.common import connect, default_database_args
    from eval.runtime import (
        RewriteOutcome,
        load_chunk_items,
        load_eval_samples,
        load_memory_items,
        rerank_retrieval_candidates,
        retrieve_top_k,
        run_selective_rewrite,
    )
except ModuleNotFoundError:  # pragma: no cover
    from pipeline.common.experiment_config import load_experiment_config
    from pipeline.common.experiment_run import ExperimentRunRecorder
    from pipeline.common.text_utils import copy_ratio, extract_extractive_summary
    from pipeline.loaders.common import connect, default_database_args
    from pipeline.eval.runtime import (
        RewriteOutcome,
        load_chunk_items,
        load_eval_samples,
        load_memory_items,
        rerank_retrieval_candidates,
        retrieve_top_k,
        run_selective_rewrite,
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


def _write_csv(path: Path, rows: list[dict[str, Any]], fieldnames: list[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as target:
        writer = csv.DictWriter(target, fieldnames=fieldnames)
        writer.writeheader()
        for row in rows:
            writer.writerow(row)


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
) -> dict[str, Any]:
    if rewrite_enabled:
        rewrite_outcome, retrieval = run_selective_rewrite(
            raw_query=sample.query_text,
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
        )
    else:
        retrieval = retrieve_top_k(sample.query_text, chunks, top_k=config.retrieval_top_k)
        rewrite_outcome = RewriteOutcome(
            final_query=sample.query_text,
            rewrite_applied=False,
            rewrite_reason="rewrite_disabled",
            raw_confidence=0.0,
            best_candidate_confidence=0.0,
            memory_top_n=[],
            candidates=[],
        )
    reranked = rerank_retrieval_candidates(
        rewrite_outcome.final_query if rewrite_enabled else sample.query_text,
        retrieval,
        top_n=config.rerank_top_n,
    )
    answer_segments = [
        extract_extractive_summary(item.text, max_sentences=1)
        for item in reranked[:2]
    ]
    answer_text = " ".join(segment for segment in answer_segments if segment).strip()

    expected_points = " ".join(
        [point for point in sample.dialog_context.get("expected_answer_key_points", [])]
    )
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
    return {
        "sample": sample,
        "metrics": metrics,
        "sample_row": {
            "sample_id": sample.sample_id,
            "split": sample.split,
            "category": sample.query_category,
            "final_query": rewrite_outcome.final_query,
            "answer_text": answer_text,
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
            parameters={"stage": "eval-answer"},
            run_label="eval-answer",
        )

        dataset_id = str(config.raw.get("dataset_id") or "").strip() or None
        memory_strategy_filters = [
            str(item).upper()
            for item in (config.raw.get("memory_generation_strategies") or [])
            if str(item).strip()
        ]
        source_gating_run_id = str(config.raw.get("source_gating_run_id") or "").strip() or None
        rewrite_enabled = bool(config.raw.get("rewrite_enabled", True))
        selective_rewrite = bool(config.raw.get("selective_rewrite", True))
        gating_applied = bool(config.raw.get("gating_applied", True))
        eval_concurrency = _resolve_eval_concurrency(config.raw)
        samples = load_eval_samples(connection, dataset_id=dataset_id)
        LOGGER.info(
            "answer_eval_parallelism samples=%s concurrency=%s",
            len(samples),
            eval_concurrency,
        )
        chunks = load_chunk_items(connection)
        memories = load_memory_items(connection)

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
                            "cohere-rerank-hybrid",
                            item.score,
                            Jsonb(
                                {
                                    "experiment_run_id": run_context.experiment_run_id,
                                    "final_query": final_query,
                                    "retrieved_document_id": item.document_id,
                                    "retrieved_chunk_id": item.chunk_id,
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

        summary_payload = {
            "experiment_key": config.experiment_key,
            "experiment_run_id": run_context.experiment_run_id,
            "dataset_id": dataset_id,
            "source_gating_run_id": source_gating_run_id,
            "memory_generation_strategies": memory_strategy_filters,
            "rewrite_enabled": rewrite_enabled,
            "selective_rewrite": selective_rewrite,
            "summary": summary,
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
