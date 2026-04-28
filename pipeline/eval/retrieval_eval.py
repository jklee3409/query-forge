from __future__ import annotations

import csv
import json
import logging
import os
import statistics
import time
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Any

import psycopg
from psycopg.types.json import Jsonb

try:
    from common.experiment_config import load_experiment_config
    from common.experiment_run import ExperimentRunRecorder
    from loaders.common import connect, default_database_args
    from eval.runtime import (
        EvalSample,
        load_chunk_items,
        load_eval_samples,
        load_memory_items,
        local_retriever_label,
        memory_top_n,
        retrieval_metrics,
        retrieve_top_k,
        run_selective_rewrite,
    )
except ModuleNotFoundError:  # pragma: no cover
    from pipeline.common.experiment_config import load_experiment_config
    from pipeline.common.experiment_run import ExperimentRunRecorder
    from pipeline.loaders.common import connect, default_database_args
    from pipeline.eval.runtime import (
        EvalSample,
        load_chunk_items,
        load_eval_samples,
        load_memory_items,
        local_retriever_label,
        memory_top_n,
        retrieval_metrics,
        retrieve_top_k,
        run_selective_rewrite,
    )


LOGGER = logging.getLogger(__name__)

METRIC_KEYS = ("recall@5", "hit@5", "mrr@10", "ndcg@10")
MODES = (
    "raw_only",
    "memory_only_ungated",
    "memory_only_rule_only",
    "memory_only_full_gating",
    "memory_only_gated",
    "rewrite_always",
    "selective_rewrite",
    "selective_rewrite_with_session",
)


def _resolve_eval_concurrency(raw_config: dict[str, Any]) -> int:
    value = (
        raw_config.get("retrieval_eval_concurrency")
        or raw_config.get("eval_concurrency")
        or os.getenv("QUERY_FORGE_RETRIEVAL_EVAL_CONCURRENCY")
        or os.getenv("QUERY_FORGE_EVAL_CONCURRENCY")
        or os.getenv("QUERY_FORGE_LLM_CONCURRENCY_LIMIT")
        or 4
    )
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        parsed = 4
    return max(1, min(parsed, 32))


def _mean(rows: list[float]) -> float:
    if not rows:
        return 0.0
    return float(statistics.fmean(rows))


def _write_csv(path: Path, rows: list[dict[str, Any]], fieldnames: list[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as target:
        writer = csv.DictWriter(target, fieldnames=fieldnames)
        writer.writeheader()
        for row in rows:
            writer.writerow(row)


def _bar(value: float) -> str:
    length = max(0, min(20, int(round(value * 20))))
    return "█" * length + "·" * (20 - length)


def _render_report(
    *,
    report_path: Path,
    summary_rows: list[dict[str, Any]],
    category_rows: list[dict[str, Any]],
    latency_rows: list[dict[str, Any]],
) -> None:
    lines = [
        "# Latest Retrieval Report",
        "",
        "## Mode Summary",
        "",
        "| mode | recall@5 | hit@5 | mrr@10 | ndcg@10 | adoption_rate | bad_rewrite_rate |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for row in summary_rows:
        lines.append(
            f"| {row['mode']} | {row['recall@5']:.4f} | {row['hit@5']:.4f} | {row['mrr@10']:.4f} | "
            f"{row['ndcg@10']:.4f} | {row.get('adoption_rate', 0.0):.4f} | {row.get('bad_rewrite_rate', 0.0):.4f} |"
        )

    lines.extend(
        [
            "",
            "## Quick Graph (MRR@10)",
            "",
        ]
    )
    for row in summary_rows:
        lines.append(f"- {row['mode']}: `{_bar(float(row['mrr@10']))}` {row['mrr@10']:.4f}")

    lines.extend(
        [
            "",
            "## Category Summary",
            "",
            "| mode | category | recall@5 | hit@5 | mrr@10 | ndcg@10 |",
            "| --- | --- | ---: | ---: | ---: | ---: |",
        ]
    )
    for row in category_rows:
        lines.append(
            f"| {row['mode']} | {row['category']} | {row['recall@5']:.4f} | {row['hit@5']:.4f} | "
            f"{row['mrr@10']:.4f} | {row['ndcg@10']:.4f} |"
        )

    lines.extend(
        [
            "",
            "## Latency",
            "",
            "| mode | avg_latency_ms | p95_latency_ms |",
            "| --- | ---: | ---: |",
        ]
    )
    for row in latency_rows:
        lines.append(
            f"| {row['mode']} | {row['avg_latency_ms']:.2f} | {row['p95_latency_ms']:.2f} |"
        )

    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def _evaluate_mode(
    *,
    mode: str,
    sample: EvalSample,
    chunks: Any,
    memories: Any,
    config: Any,
    memory_strategy_filters: list[str],
    source_gating_run_id: str | None,
    comparison_source_runs: dict[str, str],
) -> tuple[dict[str, float], dict[str, Any], list[Any]]:
    if mode == "raw_only":
        retrieval = retrieve_top_k(
            sample.query_text,
            chunks,
            top_k=config.retrieval_top_k,
            retriever_config=config.retriever_config,
        )
        rewrite_info = {
            "rewrite_applied": False,
            "raw_confidence": 0.0,
            "best_candidate_confidence": 0.0,
            "final_query": sample.query_text,
            "rewrite_reason": "raw_only",
            "memory_top_n": [],
            "candidates": [],
        }
        metrics = retrieval_metrics(
            expected_chunk_ids=sample.expected_chunk_ids,
            expected_doc_ids=sample.expected_doc_ids,
            retrieved=retrieval,
        )
        return metrics, rewrite_info, retrieval

    if mode in {"memory_only_ungated", "memory_only_rule_only", "memory_only_full_gating", "memory_only_gated"}:
        preset_by_mode = {
            "memory_only_ungated": "ungated",
            "memory_only_rule_only": "rule_only",
            "memory_only_full_gating": "full_gating",
            "memory_only_gated": config.gating_preset,
        }
        preset_filter = preset_by_mode.get(mode, config.gating_preset)
        source_run_filter = comparison_source_runs.get(preset_filter, source_gating_run_id)
        top_memory = memory_top_n(
            sample.query_text,
            memories,
            top_n=config.memory_top_n,
            preset_filter=preset_filter,
            source_gate_run_id=source_run_filter,
            strategy_filters=memory_strategy_filters,
            retriever_config=config.retriever_config,
        )
        final_query = top_memory[0]["query_text"] if top_memory else sample.query_text
        retrieval = retrieve_top_k(
            final_query,
            chunks,
            top_k=config.retrieval_top_k,
            retriever_config=config.retriever_config,
        )
        metrics = retrieval_metrics(
            expected_chunk_ids=sample.expected_chunk_ids,
            expected_doc_ids=sample.expected_doc_ids,
            retrieved=retrieval,
        )
        return (
            metrics,
            {
                "rewrite_applied": bool(top_memory),
                "raw_confidence": 0.0,
                "best_candidate_confidence": 0.0,
                "final_query": final_query,
                "rewrite_reason": f"memory_lookup:{preset_filter}",
                "memory_top_n": top_memory,
                "candidates": [],
            },
            retrieval,
        )

    force_rewrite = mode == "rewrite_always"
    use_context = mode == "selective_rewrite_with_session"
    rewrite_outcome, retrieval = run_selective_rewrite(
        raw_query=sample.query_text,
        query_language=sample.query_language,
        session_context=sample.dialog_context if use_context else {},
        chunks=chunks,
        memories=memories,
        memory_top_n_value=config.memory_top_n,
        candidate_count=config.rewrite_candidate_count,
        threshold=config.rewrite_threshold,
        retrieval_top_k=config.retrieval_top_k,
        preset_filter=config.gating_preset,
        source_gate_run_id=source_gating_run_id,
        strategy_filters=memory_strategy_filters,
        force_rewrite=force_rewrite,
        rewrite_retrieval_strategy=str(config.raw.get("rewrite_retrieval_strategy") or "replace"),
        retriever_config=config.retriever_config,
    )
    metrics = retrieval_metrics(
        expected_chunk_ids=sample.expected_chunk_ids,
        expected_doc_ids=sample.expected_doc_ids,
        retrieved=retrieval,
    )
    return (
        metrics,
        {
            "rewrite_applied": rewrite_outcome.rewrite_applied,
            "raw_confidence": rewrite_outcome.raw_confidence,
            "best_candidate_confidence": rewrite_outcome.best_candidate_confidence,
            "final_query": rewrite_outcome.final_query,
            "rewrite_reason": rewrite_outcome.rewrite_reason,
            "memory_top_n": rewrite_outcome.memory_top_n,
            "candidates": rewrite_outcome.candidates,
        },
        retrieval,
    )


def _evaluate_sample_mode(
    *,
    sample: EvalSample,
    mode: str,
    chunks: Any,
    memories: Any,
    config: Any,
    memory_strategy_filters: list[str],
    source_gating_run_id: str | None,
    comparison_source_runs: dict[str, str],
) -> dict[str, Any]:
    started = time.perf_counter()
    metrics, rewrite_info, retrieval = _evaluate_mode(
        mode=mode,
        sample=sample,
        chunks=chunks,
        memories=memories,
        config=config,
        memory_strategy_filters=memory_strategy_filters,
        source_gating_run_id=source_gating_run_id,
        comparison_source_runs=comparison_source_runs,
    )
    elapsed_ms = (time.perf_counter() - started) * 1000.0
    return {
        "sample": sample,
        "mode": mode,
        "metrics": metrics,
        "rewrite_info": rewrite_info,
        "retrieval": retrieval,
        "elapsed_ms": elapsed_ms,
    }


def run_retrieval_eval(
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
                "stage": "eval-retrieval",
                "retriever_config": config.retriever_config.to_metadata(),
            },
            run_label="eval-retrieval",
        )

        dataset_id = str(config.raw.get("dataset_id") or "").strip() or None
        synthetic_free_baseline = bool(config.raw.get("synthetic_free_baseline", False))
        memory_strategy_filters = [
            str(item).upper()
            for item in (config.raw.get("memory_generation_strategies") or [])
            if str(item).strip()
        ]
        source_gating_run_id = str(config.raw.get("source_gating_run_id") or "").strip() or None
        comparison_source_runs: dict[str, str] = {}
        comparison_snapshots = config.raw.get("comparison_snapshots")
        if isinstance(comparison_snapshots, dict):
            for preset, payload in comparison_snapshots.items():
                if not isinstance(preset, str) or not isinstance(payload, dict):
                    continue
                run_id = str(payload.get("source_gating_run_id") or "").strip()
                if run_id:
                    comparison_source_runs[preset.strip().lower()] = run_id
        if synthetic_free_baseline:
            memory_strategy_filters = []
            source_gating_run_id = None
            comparison_source_runs = {}
        configured_modes = [
            str(item).strip()
            for item in (config.raw.get("retrieval_modes") or [])
            if str(item).strip()
        ]
        active_modes = [mode for mode in configured_modes if mode in MODES] or list(MODES)
        if synthetic_free_baseline:
            active_modes = ["raw_only"]
        eval_concurrency = _resolve_eval_concurrency(config.raw)

        eval_query_language = str(config.raw.get("eval_query_language") or "ko").strip().lower()
        samples = load_eval_samples(connection, dataset_id=dataset_id, query_language=eval_query_language)
        LOGGER.info(
            "retrieval_eval_parallelism samples=%s modes=%s concurrency=%s",
            len(samples),
            len(active_modes),
            eval_concurrency,
        )
        chunks = load_chunk_items(connection)
        needs_memory = any(mode != "raw_only" for mode in active_modes)
        memories = (
            load_memory_items(connection, memory_experiment_key=config.experiment_key)
            if needs_memory
            else []
        )
        raw_metrics_by_sample: dict[str, dict[str, float]] = {}
        mode_scores: dict[str, list[dict[str, Any]]] = defaultdict(list)
        latency_by_mode: dict[str, list[float]] = defaultdict(list)
        rewrite_rows: dict[str, list[dict[str, Any]]] = defaultdict(list)

        with connection.cursor() as cursor:
            cursor.execute(
                """
                DELETE FROM retrieval_results
                WHERE result_scope = 'eval'
                  AND metadata ->> 'experiment_run_id' = %s
                """,
                (run_context.experiment_run_id,),
            )

        work_items = [
            (sample, mode)
            for sample in samples
            for mode in active_modes
        ]
        evaluated: list[dict[str, Any]] = []
        if eval_concurrency <= 1 or len(work_items) <= 1:
            for sample, mode in work_items:
                evaluated.append(
                    _evaluate_sample_mode(
                        sample=sample,
                        mode=mode,
                        chunks=chunks,
                        memories=memories,
                        config=config,
                        memory_strategy_filters=memory_strategy_filters,
                        source_gating_run_id=source_gating_run_id,
                        comparison_source_runs=comparison_source_runs,
                    )
                )
        else:
            with ThreadPoolExecutor(max_workers=min(eval_concurrency, len(work_items))) as pool:
                futures = [
                    pool.submit(
                        _evaluate_sample_mode,
                        sample=sample,
                        mode=mode,
                        chunks=chunks,
                        memories=memories,
                        config=config,
                        memory_strategy_filters=memory_strategy_filters,
                        source_gating_run_id=source_gating_run_id,
                        comparison_source_runs=comparison_source_runs,
                    )
                    for sample, mode in work_items
                ]
                for future in as_completed(futures):
                    evaluated.append(future.result())

        mode_rank = {mode: index for index, mode in enumerate(active_modes)}
        evaluated.sort(
            key=lambda row: (
                row["sample"].sample_id,
                mode_rank.get(row["mode"], 999),
            )
        )

        raw_metrics_by_sample = {
            row["sample"].sample_id: row["metrics"]
            for row in evaluated
            if row["mode"] == "raw_only"
        }

        for row in evaluated:
            sample = row["sample"]
            mode = row["mode"]
            metrics = row["metrics"]
            rewrite_info = row["rewrite_info"]
            retrieval = row["retrieval"]
            elapsed_ms = float(row["elapsed_ms"])

            latency_by_mode[mode].append(elapsed_ms)
            mode_scores[mode].append(
                {
                    "sample_id": sample.sample_id,
                    "split": sample.split,
                    "category": sample.query_category,
                    "mode": mode,
                    **metrics,
                    "rewrite_applied": bool(rewrite_info["rewrite_applied"]),
                    "confidence_delta": float(
                        rewrite_info.get("best_candidate_confidence", 0.0)
                        - rewrite_info.get("raw_confidence", 0.0)
                    ),
                    "latency_ms": elapsed_ms,
                }
            )
            rewrite_rows[mode].append(
                {
                    "sample_id": sample.sample_id,
                    "mode": mode,
                    "rewrite_applied": bool(rewrite_info["rewrite_applied"]),
                    "raw_confidence": rewrite_info["raw_confidence"],
                    "best_candidate_confidence": rewrite_info["best_candidate_confidence"],
                    "confidence_delta": rewrite_info["best_candidate_confidence"] - rewrite_info["raw_confidence"],
                    "final_query": rewrite_info["final_query"],
                    "rewrite_reason": rewrite_info["rewrite_reason"],
                    "raw_mrr": raw_metrics_by_sample.get(sample.sample_id, {}).get("mrr@10", 0.0),
                    "mode_mrr": metrics["mrr@10"],
                    "raw_ndcg": raw_metrics_by_sample.get(sample.sample_id, {}).get("ndcg@10", 0.0),
                    "mode_ndcg": metrics["ndcg@10"],
                    "memory_top_n": rewrite_info.get("memory_top_n", []),
                    "rewrite_candidates": rewrite_info.get("candidates", []),
                    "retrieved_top_k": [
                        {
                            "chunk_id": item.chunk_id,
                            "document_id": item.document_id,
                            "score": item.score,
                        }
                        for item in retrieval[:5]
                    ],
                }
            )

        with connection.cursor() as cursor:
            for row in evaluated:
                sample = row["sample"]
                mode = row["mode"]
                retrieval = row["retrieval"]
                for rank, item in enumerate(retrieval, start=1):
                    cursor.execute(
                        """
                        INSERT INTO retrieval_results (
                            eval_sample_id,
                            result_scope,
                            rank,
                            document_id,
                            chunk_id,
                            retriever_name,
                            score,
                            metadata
                        ) VALUES (%s, 'eval', %s, %s, %s, %s, %s, %s)
                        """,
                        (
                            sample.sample_id,
                            rank,
                            None,
                            None,
                            local_retriever_label(config.retriever_config),
                            item.score,
                            Jsonb(
                                {
                                    "mode": mode,
                                    "experiment_run_id": run_context.experiment_run_id,
                                    "retrieved_document_id": item.document_id,
                                    "retrieved_chunk_id": item.chunk_id,
                                    "retriever_config": config.retriever_config.to_metadata(),
                                }
                            ),
                        ),
                    )

        summary_rows: list[dict[str, Any]] = []
        category_rows: list[dict[str, Any]] = []
        latency_rows: list[dict[str, Any]] = []

        raw_baseline_mrr = _mean([float(row["mrr@10"]) for row in mode_scores.get("raw_only", [])])
        raw_baseline_ndcg = _mean([float(row["ndcg@10"]) for row in mode_scores.get("raw_only", [])])

        for mode in active_modes:
            rows = mode_scores.get(mode, [])
            if not rows:
                continue
            aggregates = {metric: _mean([float(row[metric]) for row in rows]) for metric in METRIC_KEYS}
            rewrite_applied = sum(1 for row in rows if row["rewrite_applied"])
            adoption_rate = rewrite_applied / len(rows)

            bad_rewrite_cases = 0
            rewrite_total = 0
            for row in rewrite_rows.get(mode, []):
                if row["rewrite_applied"]:
                    rewrite_total += 1
                    if row["mode_mrr"] < row["raw_mrr"]:
                        bad_rewrite_cases += 1
            bad_rewrite_rate = bad_rewrite_cases / rewrite_total if rewrite_total else 0.0
            rewrite_rejection_rate = (
                sum(1 for row in rows if not row["rewrite_applied"]) / len(rows)
                if mode in {"rewrite_always", "selective_rewrite", "selective_rewrite_with_session"}
                else 0.0
            )
            avg_confidence_delta = _mean([float(row.get("confidence_delta", 0.0)) for row in rows])

            summary_rows.append(
                {
                    "mode": mode,
                    **aggregates,
                    "adoption_rate": adoption_rate,
                    "rewrite_rejection_rate": rewrite_rejection_rate,
                    "avg_confidence_delta": avg_confidence_delta,
                    "rewrite_gain_mrr": aggregates["mrr@10"] - raw_baseline_mrr,
                    "rewrite_gain_ndcg": aggregates["ndcg@10"] - raw_baseline_ndcg,
                    "bad_rewrite_rate": bad_rewrite_rate,
                }
            )

            by_category: dict[str, list[dict[str, Any]]] = defaultdict(list)
            for row in rows:
                by_category[row["category"]].append(row)
            for category, category_samples in by_category.items():
                category_rows.append(
                    {
                        "mode": mode,
                        "category": category,
                        **{
                            metric: _mean([float(item[metric]) for item in category_samples])
                            for metric in METRIC_KEYS
                        },
                    }
                )

            sorted_latency = sorted(latency_by_mode.get(mode, []))
            p95_index = max(0, int(round(len(sorted_latency) * 0.95)) - 1)
            latency_rows.append(
                {
                    "mode": mode,
                    "avg_latency_ms": _mean(sorted_latency),
                    "p95_latency_ms": sorted_latency[p95_index] if sorted_latency else 0.0,
                }
            )

        summary_path = output_root / f"retrieval_summary_{config.experiment_key}.json"
        summary_csv_path = output_root / f"retrieval_summary_{config.experiment_key}.csv"
        category_csv_path = output_root / f"retrieval_by_category_{config.experiment_key}.csv"
        latency_csv_path = output_root / f"latency_{config.experiment_key}.csv"
        rewrite_case_path = output_root / f"rewrite_cases_{config.experiment_key}.json"
        summary_path.parent.mkdir(parents=True, exist_ok=True)
        summary_payload = {
            "experiment_key": config.experiment_key,
            "experiment_run_id": run_context.experiment_run_id,
            "dataset_id": dataset_id,
            "source_gating_run_id": source_gating_run_id,
            "comparison_source_runs": comparison_source_runs,
            "memory_generation_strategies": memory_strategy_filters,
            "synthetic_free_baseline": synthetic_free_baseline,
            "memory_experiment_key": config.experiment_key if needs_memory else None,
            "memory_entry_count_loaded": len(memories),
            "retriever_config": config.retriever_config.to_metadata(),
            "retriever_name": local_retriever_label(config.retriever_config),
            "active_modes": active_modes,
            "summary": summary_rows,
            "category_summary": category_rows,
            "latency_summary": latency_rows,
        }
        summary_path.write_text(
            json.dumps(summary_payload, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        _write_csv(
            summary_csv_path,
            summary_rows,
            [
                "mode",
                *METRIC_KEYS,
                "adoption_rate",
                "rewrite_rejection_rate",
                "avg_confidence_delta",
                "rewrite_gain_mrr",
                "rewrite_gain_ndcg",
                "bad_rewrite_rate",
            ],
        )
        _write_csv(category_csv_path, category_rows, ["mode", "category", *METRIC_KEYS])
        _write_csv(latency_csv_path, latency_rows, ["mode", "avg_latency_ms", "p95_latency_ms"])

        rewrite_flat_rows = [row for rows in rewrite_rows.values() for row in rows]
        rewrite_case_path.write_text(
            json.dumps(rewrite_flat_rows, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

        bad_cases = [
            row
            for row in rewrite_flat_rows
            if row["rewrite_applied"] and row["mode_mrr"] < row["raw_mrr"]
        ][:40]
        best_cases = [
            row
            for row in rewrite_flat_rows
            if row["rewrite_applied"] and row["mode_mrr"] > row["raw_mrr"]
        ][:40]
        bad_case_md = docs_root / "bad_rewrite_cases.md"
        best_case_md = docs_root / "best_rewrite_cases.md"
        bad_case_md.parent.mkdir(parents=True, exist_ok=True)
        bad_case_md.write_text(
            "# Bad Rewrite Cases\n\n"
            + "\n".join(
                [
                    f"- sample={row['sample_id']} mode={row['mode']} raw_mrr={row['raw_mrr']:.4f} "
                    f"mode_mrr={row['mode_mrr']:.4f} reason={row['rewrite_reason']}"
                    for row in bad_cases
                ]
            )
            + "\n",
            encoding="utf-8",
        )
        best_case_md.write_text(
            "# Best Rewrite Cases\n\n"
            + "\n".join(
                [
                    f"- sample={row['sample_id']} mode={row['mode']} raw_mrr={row['raw_mrr']:.4f} "
                    f"mode_mrr={row['mode_mrr']:.4f} reason={row['rewrite_reason']}"
                    for row in best_cases
                ]
            )
            + "\n",
            encoding="utf-8",
        )

        latest_report_path = docs_root / "latest_report.md"
        _render_report(
            report_path=latest_report_path,
            summary_rows=summary_rows,
            category_rows=category_rows,
            latency_rows=latency_rows,
        )

        recorder.finish_run(
            run_context,
            status="completed",
            metrics={
                "summary_rows": summary_rows,
                "category_rows": category_rows[:25],
                "latency_rows": latency_rows,
                "report_paths": {
                    "summary_json": str(summary_path),
                    "summary_csv": str(summary_csv_path),
                    "category_csv": str(category_csv_path),
                    "latency_csv": str(latency_csv_path),
                    "latest_report_md": str(latest_report_path),
                    "bad_rewrite_md": str(bad_case_md),
                    "best_rewrite_md": str(best_case_md),
                },
            },
        )
        connection.commit()
        return summary_payload
    except Exception as exception:  # noqa: BLE001
        connection.rollback()
        LOGGER.exception("Retrieval evaluation failed.")
        raise exception
    finally:
        connection.close()


def run_retrieval_eval_from_env(experiment: str) -> dict[str, Any]:
    defaults = default_database_args()
    return run_retrieval_eval(
        experiment=experiment,
        database_url=defaults["database_url"],
        db_host=defaults["db_host"],
        db_port=defaults["db_port"],
        db_name=defaults["db_name"],
        db_user=defaults["db_user"],
        db_password=defaults["db_password"],
    )
