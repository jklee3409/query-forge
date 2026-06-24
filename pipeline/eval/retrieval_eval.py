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
    from common.anchor_normalization import canonical_anchor_version_payload
    from common.experiment_config import load_experiment_config
    from common.experiment_run import ExperimentRunRecorder
    from loaders.common import connect, default_database_args
    from eval.runtime import (
        DbAnnRuntimeRetrievalAdapter,
        EvalSample,
        RetrievalCandidate,
        build_memory_guided_query,
        derive_eval_corpus_scope,
        load_chunk_items,
        load_eval_samples,
        load_memory_items,
        load_multi_source_anchor_index,
        memory_top_n,
        normalize_retrieval_backend,
        retrieval_candidates_to_payload,
        retrieval_metrics,
        retrieve_top_k,
        route_strategy_router,
        run_agentic_multi_query,
        run_selective_rewrite,
        runtime_retriever_label,
        runtime_retriever_metadata,
    )
    from eval.java_retrieval_client import (
        JAVA_RETRIEVAL_AGENTIC_MODE,
        JAVA_RETRIEVAL_ENDPOINT_PATH,
        JAVA_RETRIEVAL_SUPPORTED_FORCED_MODES,
        JavaRetrievalClientError,
        JavaRetrievalEvalClient,
        JavaRetrievalEvalSettings,
        build_java_retrieval_client_from_config,
        java_retrieval_settings_from_config,
        normalize_forced_mode,
    )
except ModuleNotFoundError:  # pragma: no cover
    from pipeline.common.anchor_normalization import canonical_anchor_version_payload
    from pipeline.common.experiment_config import load_experiment_config
    from pipeline.common.experiment_run import ExperimentRunRecorder
    from pipeline.loaders.common import connect, default_database_args
    from pipeline.eval.runtime import (
        DbAnnRuntimeRetrievalAdapter,
        EvalSample,
        RetrievalCandidate,
        build_memory_guided_query,
        derive_eval_corpus_scope,
        load_chunk_items,
        load_eval_samples,
        load_memory_items,
        load_multi_source_anchor_index,
        memory_top_n,
        normalize_retrieval_backend,
        retrieval_candidates_to_payload,
        retrieval_metrics,
        retrieve_top_k,
        route_strategy_router,
        run_agentic_multi_query,
        run_selective_rewrite,
        runtime_retriever_label,
        runtime_retriever_metadata,
    )
    from pipeline.eval.java_retrieval_client import (
        JAVA_RETRIEVAL_AGENTIC_MODE,
        JAVA_RETRIEVAL_ENDPOINT_PATH,
        JAVA_RETRIEVAL_SUPPORTED_FORCED_MODES,
        JavaRetrievalClientError,
        JavaRetrievalEvalClient,
        JavaRetrievalEvalSettings,
        build_java_retrieval_client_from_config,
        java_retrieval_settings_from_config,
        normalize_forced_mode,
    )


LOGGER = logging.getLogger(__name__)

METRIC_KEYS = ("recall@5", "hit@5", "mrr@10", "ndcg@10")
LEGACY_DEFAULT_MODES = (
    "raw_only",
    "memory_only_ungated",
    "memory_only_rule_only",
    "memory_only_full_gating",
    "memory_only_gated",
    "rewrite_always",
    "selective_rewrite",
    "selective_rewrite_with_session",
)
MODES = (
    *LEGACY_DEFAULT_MODES,
    "anchor_aware_rewrite",
    "agentic_multi_query",
    "strategy_router",
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


def _int_config(raw_config: dict[str, Any], *keys: str, default: int, max_value: int | None = None) -> int:
    value = None
    for key in keys:
        if key in raw_config:
            value = raw_config.get(key)
            break
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        parsed = default
    if max_value is not None:
        parsed = min(parsed, max_value)
    return max(1, parsed)


def _mean(rows: list[float]) -> float:
    if not rows:
        return 0.0
    return float(statistics.fmean(rows))


def _selected_strategy_for_mode(mode: str) -> str | None:
    if mode in {"raw_only", "selective_rewrite", "anchor_aware_rewrite", "agentic_multi_query"}:
        return mode
    if mode in {"rewrite_always", "selective_rewrite_with_session"}:
        return "selective_rewrite"
    return None


def _with_llm_trace_defaults(mode: str, rewrite_info: dict[str, Any]) -> dict[str, Any]:
    selected_strategy = str(
        rewrite_info.get("selected_strategy")
        or _selected_strategy_for_mode(mode)
        or ""
    ).strip() or None
    router_reason = str(rewrite_info.get("router_reason") or f"mode_{mode}").strip()
    try:
        planner_call_count = int(rewrite_info.get("planner_call_count") or 0)
    except (TypeError, ValueError):
        planner_call_count = 0
    try:
        rewrite_call_count = int(rewrite_info.get("rewrite_call_count") or 0)
    except (TypeError, ValueError):
        rewrite_call_count = 0
    try:
        llm_call_count = int(rewrite_info.get("llm_call_count"))
    except (TypeError, ValueError):
        llm_call_count = planner_call_count + rewrite_call_count
    return {
        **rewrite_info,
        "selected_strategy": selected_strategy,
        "router_reason": router_reason,
        "planner_call_count": planner_call_count,
        "rewrite_call_count": rewrite_call_count,
        "llm_call_count": llm_call_count,
    }


def _strategy_selection_counts(rows: list[dict[str, Any]]) -> dict[str, int]:
    return {
        "selected_raw_count": sum(1 for row in rows if row.get("selected_strategy") == "raw_only"),
        "selected_selective_count": sum(1 for row in rows if row.get("selected_strategy") == "selective_rewrite"),
        "selected_anchor_count": sum(1 for row in rows if row.get("selected_strategy") == "anchor_aware_rewrite"),
        "selected_agentic_count": sum(1 for row in rows if row.get("selected_strategy") == "agentic_multi_query"),
    }


def _llm_call_summary(rows: list[dict[str, Any]]) -> dict[str, Any]:
    return {
        "avg_llm_calls": _mean([float(row.get("llm_call_count") or 0) for row in rows]),
        "llm_call_count": sum(int(row.get("llm_call_count") or 0) for row in rows),
        "planner_call_count": sum(int(row.get("planner_call_count") or 0) for row in rows),
        "rewrite_call_count": sum(int(row.get("rewrite_call_count") or 0) for row in rows),
        **_strategy_selection_counts(rows),
    }


def _multi_source_anchor_diagnostics(payload: Any) -> dict[str, Any]:
    if not isinstance(payload, dict):
        return {}
    diagnostics = payload.get("diagnostics")
    return diagnostics if isinstance(diagnostics, dict) else {}


def _aggregate_multi_source_anchor_diagnostics(rows: list[dict[str, Any]]) -> dict[str, Any]:
    total_candidates = 0
    total_accepted = 0
    total_seed_count = 0
    source_distribution: dict[str, int] = defaultdict(int)
    type_distribution: dict[str, int] = defaultdict(int)
    filtered: dict[str, int] = defaultdict(int)
    enabled_rows = 0
    for row in rows:
        diagnostics = _multi_source_anchor_diagnostics(row.get("multi_source_anchor_hints"))
        if not diagnostics:
            continue
        if diagnostics.get("enabled"):
            enabled_rows += 1
        total_seed_count += int(diagnostics.get("seed_anchor_count") or 0)
        total_candidates += int(diagnostics.get("candidate_expanded_anchor_count") or 0)
        total_accepted += int(diagnostics.get("accepted_expanded_anchor_count") or 0)
        for key, value in (diagnostics.get("relation_source_distribution") or {}).items():
            source_distribution[str(key)] += int(value or 0)
        for key, value in (diagnostics.get("relation_type_distribution") or {}).items():
            type_distribution[str(key)] += int(value or 0)
        for key, value in (diagnostics.get("filtered") or {}).items():
            filtered[str(key)] += int(value or 0)
    return {
        "enabled_sample_count": enabled_rows,
        "seed_anchor_count": total_seed_count,
        "expanded_anchor_count": total_candidates,
        "accepted_expanded_anchor_count": total_accepted,
        "anchor_dedup_rate": 1.0 - (total_accepted / total_candidates) if total_candidates else 0.0,
        "relation_source_distribution": dict(source_distribution),
        "relation_type_distribution": dict(type_distribution),
        "filtered": dict(filtered),
    }


def _write_csv(path: Path, rows: list[dict[str, Any]], fieldnames: list[str]) -> None:
    resolved_fieldnames = list(fieldnames or [])
    known = set(resolved_fieldnames)
    for row in rows:
        if not isinstance(row, dict):
            continue
        for key in row.keys():
            if key in known:
                continue
            resolved_fieldnames.append(key)
            known.add(key)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as target:
        writer = csv.DictWriter(target, fieldnames=resolved_fieldnames, extrasaction="ignore")
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
) -> None:
    lines = [
        "# Latest Retrieval Report",
        "",
        "## Mode Summary",
        "",
        "| mode | recall@5 | hit@5 | mrr@10 | ndcg@10 | avg_latency_ms | degraded_query_count | adoption_rate | bad_rewrite_rate |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for row in summary_rows:
        lines.append(
            f"| {row['mode']} | {row['recall@5']:.4f} | {row['hit@5']:.4f} | {row['mrr@10']:.4f} | "
            f"{row['ndcg@10']:.4f} | {row.get('avg_latency_ms', 0.0):.2f} | "
            f"{int(row.get('degraded_query_count', 0))} | {row.get('adoption_rate', 0.0):.4f} | "
            f"{row.get('bad_rewrite_rate', 0.0):.4f} |"
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

    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def _dedup_candidates(
    rows: list[RetrievalCandidate],
    *,
    top_k: int,
) -> list[RetrievalCandidate]:
    deduped: list[RetrievalCandidate] = []
    seen_chunks: set[str] = set()
    for row in rows:
        chunk_id = str(row.chunk_id or "").strip()
        if not chunk_id or chunk_id in seen_chunks:
            continue
        seen_chunks.add(chunk_id)
        deduped.append(row)
        if len(deduped) >= top_k:
            break
    return deduped


def _merge_retrieval_results(
    *,
    strategy: str,
    raw_retrieval: list[RetrievalCandidate],
    guided_retrieval: list[RetrievalCandidate],
    top_k: int,
) -> list[RetrievalCandidate]:
    # Legacy/ablation-only merge used by explicit memory_only_* modes. The
    # default rewrite modes never merge raw, memory, or rewritten retrievals.
    normalized = str(strategy or "max_score").strip().lower()
    if normalized == "replace":
        return guided_retrieval[:top_k]
    if normalized == "interleave":
        interleaved: list[RetrievalCandidate] = []
        max_len = max(len(raw_retrieval), len(guided_retrieval))
        for index in range(max_len):
            if index < len(raw_retrieval):
                interleaved.append(raw_retrieval[index])
            if index < len(guided_retrieval):
                interleaved.append(guided_retrieval[index])
        return _dedup_candidates(interleaved, top_k=top_k)

    by_chunk: dict[str, RetrievalCandidate] = {}
    for row in [*raw_retrieval, *guided_retrieval]:
        chunk_id = str(row.chunk_id or "").strip()
        if not chunk_id:
            continue
        existing = by_chunk.get(chunk_id)
        if existing is None or row.score > existing.score:
            by_chunk[chunk_id] = row
    ranked = sorted(by_chunk.values(), key=lambda item: item.score, reverse=True)
    return ranked[:top_k]


def _first_expected_rank(
    *,
    sample: EvalSample,
    retrieval: list[RetrievalCandidate],
) -> int | None:
    expected_chunks = {str(chunk_id) for chunk_id in sample.expected_chunk_ids if str(chunk_id).strip()}
    expected_docs = {str(doc_id) for doc_id in sample.expected_doc_ids if str(doc_id).strip()}
    for rank, item in enumerate(retrieval, start=1):
        if expected_chunks and item.chunk_id in expected_chunks:
            return rank
        if not expected_chunks and expected_docs and item.document_id in expected_docs:
            return rank
    return None


def _raw_retrieval_cache_path(output_root: Path, experiment_key: str) -> Path:
    return output_root / f"raw_retrieval_cache_{experiment_key}.json"


def _is_raw_retrieval_required(raw_config: dict[str, Any]) -> bool:
    value = raw_config.get("raw_retrieval_required", True)
    if isinstance(value, bool):
        return value
    return str(value or "").strip().lower() not in {"0", "false", "no", "off"}


def _java_forced_mode_for_eval_mode(
    *,
    mode: str,
    settings: JavaRetrievalEvalSettings | None,
) -> str | None:
    if settings is None or not settings.enabled:
        return None
    normalized_mode = normalize_forced_mode(mode)
    if normalized_mode not in JAVA_RETRIEVAL_SUPPORTED_FORCED_MODES:
        return None
    return settings.forced_mode or normalized_mode


def _unsupported_java_modes(modes: list[str] | tuple[str, ...]) -> list[str]:
    unsupported: list[str] = []
    for mode in modes:
        normalized = normalize_forced_mode(mode)
        if normalized not in JAVA_RETRIEVAL_SUPPORTED_FORCED_MODES:
            unsupported.append(normalized)
    return unsupported


def _raise_unsupported_java_modes(modes: list[str] | tuple[str, ...]) -> None:
    unsupported = _unsupported_java_modes(modes)
    if not unsupported:
        return
    unique_unsupported = sorted(set(unsupported))
    supported = ", ".join(sorted(JAVA_RETRIEVAL_SUPPORTED_FORCED_MODES))
    if JAVA_RETRIEVAL_AGENTIC_MODE in unique_unsupported:
        raise JavaRetrievalClientError(
            code="unsupported_agentic_eval",
            detail=(
                "Java-backed retrieval eval supports only "
                f"{supported}; unsupported modes: {', '.join(unique_unsupported)}. "
                "agentic_multi_query remains blocked in Phase 8B."
            ),
        )
    raise JavaRetrievalClientError(
        code="unsupported_forced_mode",
        detail=(
            "Java-backed retrieval eval supports only "
            f"{supported}; unsupported modes: {', '.join(unique_unsupported)}."
        ),
    )


def _validate_java_backend_modes(
    *,
    active_modes: list[str],
    settings: JavaRetrievalEvalSettings | None,
) -> None:
    if settings is None or not settings.enabled:
        return
    _raise_unsupported_java_modes(active_modes)
    if settings.forced_mode:
        _raise_unsupported_java_modes([settings.forced_mode])


def _java_llm_call_counts(response: dict[str, Any]) -> tuple[int, int, int]:
    raw_counts = response.get("llmCallCount")
    if not isinstance(raw_counts, dict):
        return 0, 0, 0
    planner_calls = _int_or_zero(raw_counts.get("plannerCalls"))
    rewrite_calls = _int_or_zero(raw_counts.get("rewriteCalls"))
    total_calls = _int_or_zero(raw_counts.get("totalCalls"))
    if total_calls <= 0:
        total_calls = planner_calls + rewrite_calls
    return planner_calls, rewrite_calls, total_calls


def _java_rewrite_info(
    *,
    mode: str,
    sample: EvalSample,
    forced_mode: str,
    result: Any,
) -> dict[str, Any]:
    selected_mode = normalize_forced_mode(result.selected_mode or forced_mode)
    planner_calls, rewrite_calls, total_calls = _java_llm_call_counts(result.raw_response)
    final_query = result.final_query or sample.query_text
    rewrite_applied = selected_mode in {"selective_rewrite", "anchor_aware_rewrite"} and final_query != sample.query_text
    return {
        "rewrite_applied": rewrite_applied,
        "raw_confidence": 0.0,
        "best_candidate_confidence": 0.0,
        "final_query": final_query,
        "rewrite_reason": f"java_retrieval_eval:{selected_mode}",
        "memory_top_n": [],
        "candidates": [],
        "selected_rewrite": None,
        "anchor_candidates": [],
        "terminology_hints": None,
        "canonical_anchor_hints": None,
        "multi_source_anchor_hints": None,
        "memory_hint_query": None,
        "memory_hint_retrieval_applied": False,
        "rewrite_llm_attempted": rewrite_calls > 0,
        "rewrite_llm_succeeded": rewrite_calls > 0,
        "rewrite_heuristic_fallback_used": False,
        "planner_call_count": planner_calls,
        "rewrite_call_count": rewrite_calls,
        "llm_call_count": total_calls,
        "final_rewrite_latency_ms": None,
        "pure_rewrite_latency_ms": None,
        "selected_strategy": selected_mode if mode == "strategy_router" else None,
        "router_reason": "java_retrieval_eval" if mode == "strategy_router" else None,
        "strategy_router_metadata": (
            {
                "java_forced_mode": forced_mode,
                "java_selected_mode": selected_mode,
                "java_endpoint": JAVA_RETRIEVAL_ENDPOINT_PATH,
            }
            if mode == "strategy_router"
            else None
        ),
        "backend": "java",
        "java_backend_enabled": True,
        "java_endpoint": JAVA_RETRIEVAL_ENDPOINT_PATH,
        "java_forced_mode": forced_mode,
        "java_selected_mode": selected_mode,
        "java_warnings": result.warnings,
    }


def _int_or_zero(value: Any) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return 0


def _precompute_raw_retrieval(
    *,
    samples: list[EvalSample],
    chunks: Any,
    config: Any,
    retrieval_adapter: DbAnnRuntimeRetrievalAdapter | None,
    retrieval_backend: str,
) -> tuple[dict[str, list[RetrievalCandidate]], list[str]]:
    raw_retrieval_by_sample: dict[str, list[RetrievalCandidate]] = {}
    empty_sample_ids: list[str] = []
    for sample in samples:
        retrieval = retrieve_top_k(
            sample.query_text,
            chunks,
            top_k=config.retrieval_top_k,
            retriever_config=config.retriever_config,
            retrieval_adapter=retrieval_adapter,
        )
        raw_retrieval_by_sample[sample.sample_id] = retrieval
        if not retrieval:
            empty_sample_ids.append(sample.sample_id)

    candidate_scope_available = retrieval_backend == "db_ann" or bool(chunks)
    if candidate_scope_available and empty_sample_ids and _is_raw_retrieval_required(config.raw):
        preview = ", ".join(empty_sample_ids[:10])
        suffix = "..." if len(empty_sample_ids) > 10 else ""
        raise RuntimeError(
            "raw retrieval returned no candidates for "
            f"{len(empty_sample_ids)} sample(s): {preview}{suffix}"
        )
    return raw_retrieval_by_sample, empty_sample_ids


def _write_raw_retrieval_cache(
    *,
    path: Path,
    experiment_key: str,
    samples: list[EvalSample],
    raw_retrieval_by_sample: dict[str, list[RetrievalCandidate]],
    retriever_metadata: dict[str, Any],
    empty_sample_ids: list[str],
) -> None:
    sample_lookup = {sample.sample_id: sample for sample in samples}
    rows = []
    for sample_id in sorted(raw_retrieval_by_sample.keys()):
        sample = sample_lookup.get(sample_id)
        rows.append(
            {
                "sample_id": sample_id,
                "raw_query": sample.query_text if sample else "",
                "query_language": sample.query_language if sample else "",
                "retrieval": retrieval_candidates_to_payload(raw_retrieval_by_sample[sample_id]),
            }
        )
    payload = {
        "experiment_key": experiment_key,
        "retriever_metadata": retriever_metadata,
        "empty_sample_ids": empty_sample_ids,
        "samples": rows,
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


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
    retrieval_adapter: DbAnnRuntimeRetrievalAdapter | None,
    multi_source_anchor_index: Any | None,
    raw_retrieval: list[RetrievalCandidate],
    java_client: JavaRetrievalEvalClient | None = None,
    java_settings: JavaRetrievalEvalSettings | None = None,
) -> tuple[dict[str, float], dict[str, Any], list[Any]]:
    if java_settings is not None and java_settings.enabled:
        _validate_java_backend_modes(active_modes=[mode], settings=java_settings)
        if java_client is None:
            raise JavaRetrievalClientError(
                code="java_backend_client_required",
                detail="Java retrieval eval client is required when Java backend is enabled",
            )
    java_forced_mode = _java_forced_mode_for_eval_mode(mode=mode, settings=java_settings)
    if java_client is not None and java_settings is not None and java_forced_mode is not None:
        if not java_settings.domain_id:
            raise JavaRetrievalClientError(
                code="java_backend_domain_id_required",
                detail="domain_id or java_backend_domain_id is required when Java retrieval eval is enabled",
            )
        result = java_client.retrieve(
            domain_id=java_settings.domain_id,
            query=sample.query_text,
            forced_mode=java_forced_mode,
            top_k=config.retrieval_top_k,
            include_trace=java_settings.include_trace,
            include_scores=java_settings.include_scores,
            include_metadata=java_settings.include_metadata,
        )
        retrieval = result.to_retrieval_candidates()
        metrics = retrieval_metrics(
            expected_chunk_ids=sample.expected_chunk_ids,
            expected_doc_ids=sample.expected_doc_ids,
            retrieved=retrieval,
        )
        return (
            metrics,
            _java_rewrite_info(
                mode=mode,
                sample=sample,
                forced_mode=java_forced_mode,
                result=result,
            ),
            retrieval,
        )

    if mode == "raw_only":
        retrieval = raw_retrieval
        rewrite_info = {
            "rewrite_applied": False,
            "raw_confidence": 0.0,
            "best_candidate_confidence": 0.0,
            "final_query": sample.query_text,
            "rewrite_reason": "raw_only",
            "memory_top_n": [],
            "candidates": [],
            "selected_rewrite": None,
            "anchor_candidates": [],
            "terminology_hints": None,
            "canonical_anchor_hints": None,
            "multi_source_anchor_hints": None,
            "memory_hint_query": None,
            "memory_hint_retrieval_applied": False,
            "rewrite_llm_attempted": False,
            "rewrite_llm_succeeded": False,
            "rewrite_heuristic_fallback_used": False,
            "planner_call_count": 0,
            "rewrite_call_count": 0,
            "llm_call_count": 0,
            "final_rewrite_latency_ms": None,
            "pure_rewrite_latency_ms": None,
        }
        metrics = retrieval_metrics(
            expected_chunk_ids=sample.expected_chunk_ids,
            expected_doc_ids=sample.expected_doc_ids,
            retrieved=retrieval,
        )
        return metrics, rewrite_info, retrieval

    if mode in {"memory_only_ungated", "memory_only_rule_only", "memory_only_full_gating", "memory_only_gated"}:
        # Legacy/ablation path for measuring memory-guided retrieval separately.
        # It is intentionally isolated from rewrite_always/selective_rewrite.
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
            retrieval_adapter=retrieval_adapter,
        )
        memory_lookup_direct_enabled = str(
            config.raw.get("memory_lookup_direct_enabled", False)
        ).strip().lower() in {"1", "true", "yes", "on"}
        memory_lookup_intent_preserving_enabled = str(
            config.raw.get("memory_lookup_intent_preserving_enabled", True)
        ).strip().lower() in {"1", "true", "yes", "on"}
        memory_hint_query = None
        memory_hint_retrieval_applied = False
        if top_memory and memory_lookup_direct_enabled:
            final_query = top_memory[0]["query_text"]
            retrieval = retrieve_top_k(
                final_query,
                chunks,
                top_k=config.retrieval_top_k,
                retriever_config=config.retriever_config,
                retrieval_adapter=retrieval_adapter,
            )
            reason = f"memory_lookup_direct:{preset_filter}"
            rewrite_applied = True
        elif top_memory and memory_lookup_intent_preserving_enabled:
            memory_hint_query = build_memory_guided_query(
                sample.query_text,
                top_memory,
                query_language=sample.query_language,
                max_hint_tokens=config.raw.get("memory_lookup_hint_token_max", 3),
            )
            guided_retrieval = retrieve_top_k(
                memory_hint_query,
                chunks,
                top_k=config.retrieval_top_k,
                retriever_config=config.retriever_config,
                retrieval_adapter=retrieval_adapter,
            )
            memory_hint_retrieval_applied = bool(guided_retrieval)
            retrieval = _merge_retrieval_results(
                strategy=str(config.raw.get("memory_lookup_retrieval_strategy") or "max_score"),
                raw_retrieval=raw_retrieval,
                guided_retrieval=guided_retrieval,
                top_k=config.retrieval_top_k,
            )
            reason = f"memory_lookup_intent_guided:{preset_filter}"
            final_query = sample.query_text
            rewrite_applied = False
        else:
            final_query = sample.query_text
            retrieval = raw_retrieval
            reason = f"memory_lookup_raw:{preset_filter}"
            rewrite_applied = False
        metrics = retrieval_metrics(
            expected_chunk_ids=sample.expected_chunk_ids,
            expected_doc_ids=sample.expected_doc_ids,
            retrieved=retrieval,
        )
        return (
            metrics,
            {
                "rewrite_applied": rewrite_applied,
                "raw_confidence": 0.0,
                "best_candidate_confidence": 0.0,
                "final_query": final_query,
                "rewrite_reason": reason,
                "memory_top_n": top_memory,
                "candidates": [],
                "selected_rewrite": None,
                "anchor_candidates": [],
                "terminology_hints": None,
                "canonical_anchor_hints": None,
                "multi_source_anchor_hints": None,
                "memory_hint_query": memory_hint_query,
                "memory_hint_retrieval_applied": memory_hint_retrieval_applied,
                "rewrite_llm_attempted": False,
                "rewrite_llm_succeeded": False,
                "rewrite_heuristic_fallback_used": False,
                "planner_call_count": 0,
                "rewrite_call_count": 0,
                "llm_call_count": 0,
                "final_rewrite_latency_ms": None,
                "pure_rewrite_latency_ms": None,
            },
            retrieval,
        )

    if mode == "strategy_router":
        memory_candidates_known = retrieval_adapter is None
        decision = route_strategy_router(
            raw_query=sample.query_text,
            raw_config=config.raw,
            runtime_mode=mode,
            rewrite_ready=True,
            memory_candidates_known=memory_candidates_known,
            memory_candidates_available=(bool(memories) if memory_candidates_known else True),
            anchor_injection_enabled=_is_rewrite_anchor_injection_enabled(config.raw),
            rewrite_query_profile=str(config.raw.get("rewrite_query_profile") or "compact_anchor"),
            query_language=sample.query_language,
            query_category=sample.query_category,
        )
        metrics, rewrite_info, retrieval = _evaluate_mode(
            mode=decision.selected_strategy,
            sample=sample,
            chunks=chunks,
            memories=memories,
            config=config,
            memory_strategy_filters=memory_strategy_filters,
            source_gating_run_id=source_gating_run_id,
            comparison_source_runs=comparison_source_runs,
            retrieval_adapter=retrieval_adapter,
            multi_source_anchor_index=multi_source_anchor_index,
            raw_retrieval=raw_retrieval,
            java_client=java_client,
            java_settings=java_settings,
        )
        return (
            metrics,
            _with_llm_trace_defaults(
                "strategy_router",
                {
                    **rewrite_info,
                    "selected_strategy": decision.selected_strategy,
                    "router_reason": decision.router_reason,
                    "strategy_router_metadata": decision.metadata,
                },
            ),
            retrieval,
        )

    if mode == "agentic_multi_query":
        rewrite_info, retrieval = run_agentic_multi_query(
            raw_query=sample.query_text,
            query_language=sample.query_language,
            query_category=sample.query_category,
            session_context={},
            chunks=chunks,
            memories=memories,
            memory_top_n_value=config.memory_top_n,
            candidate_count=config.rewrite_candidate_count,
            threshold=config.rewrite_threshold,
            retrieval_top_k=config.retrieval_top_k,
            preset_filter=config.gating_preset,
            source_gate_run_id=source_gating_run_id,
            strategy_filters=memory_strategy_filters,
            rewrite_anchor_injection_enabled=_is_rewrite_anchor_injection_enabled(config.raw),
            rewrite_terminology_hints_max_count=config.raw.get("rewrite_terminology_hints_max_count", 12),
            multi_source_anchor_expansion_enabled=_is_multi_source_anchor_expansion_enabled(config.raw),
            multi_source_anchor_index=multi_source_anchor_index,
            multi_source_anchor_relation_types=_multi_source_relation_types(config.raw),
            multi_source_anchor_min_score=config.raw.get("multi_source_anchor_min_score", 0.72),
            multi_source_anchor_max_per_seed=config.raw.get("multi_source_anchor_max_per_seed", 2),
            multi_source_anchor_max_total=config.raw.get("multi_source_anchor_max_total", 8),
            rewrite_query_profile=str(config.raw.get("rewrite_query_profile") or "compact_anchor"),
            rewrite_failure_policy=str(config.raw.get("rewrite_failure_policy") or "fail_run"),
            rewrite_retrieval_strategy=str(config.raw.get("rewrite_retrieval_strategy") or "replace"),
            rewrite_adoption_policy=config.rewrite_adoption_policy,
            raw_config=config.raw,
            retriever_config=config.retriever_config,
            retrieval_adapter=retrieval_adapter,
            raw_retrieval=raw_retrieval,
            source_product=sample.source_product,
            memory_candidate_pool_n=config.raw.get("rewrite_memory_candidate_pool_n"),
            max_subqueries=_int_config(
                config.raw,
                "strategy_router_agentic_max_subqueries",
                "agentic_max_subqueries",
                "maxSubqueries",
                "max_subqueries",
                default=3,
                max_value=4,
            ),
            rrf_k=_int_config(
                config.raw,
                "agentic_rrf_k",
                "rrfK",
                "rrf_k",
                default=60,
            ),
        )
        metrics = retrieval_metrics(
            expected_chunk_ids=sample.expected_chunk_ids,
            expected_doc_ids=sample.expected_doc_ids,
            retrieved=retrieval,
        )
        return metrics, rewrite_info, retrieval

    force_rewrite = mode == "rewrite_always"
    use_context = mode == "selective_rewrite_with_session"
    rewrite_anchor_injection_enabled = (
        True
        if mode == "anchor_aware_rewrite"
        else _is_rewrite_anchor_injection_enabled(config.raw)
    )
    rewrite_outcome, retrieval = run_selective_rewrite(
        raw_query=sample.query_text,
        query_language=sample.query_language,
        query_category=sample.query_category,
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
        rewrite_anchor_injection_enabled=rewrite_anchor_injection_enabled,
        rewrite_terminology_hints_max_count=config.raw.get("rewrite_terminology_hints_max_count", 12),
        multi_source_anchor_expansion_enabled=_is_multi_source_anchor_expansion_enabled(config.raw),
        multi_source_anchor_index=multi_source_anchor_index,
        multi_source_anchor_relation_types=_multi_source_relation_types(config.raw),
        multi_source_anchor_min_score=config.raw.get("multi_source_anchor_min_score", 0.72),
        multi_source_anchor_max_per_seed=config.raw.get("multi_source_anchor_max_per_seed", 2),
        multi_source_anchor_max_total=config.raw.get("multi_source_anchor_max_total", 8),
        rewrite_query_profile=str(config.raw.get("rewrite_query_profile") or "compact_anchor"),
        rewrite_failure_policy=str(config.raw.get("rewrite_failure_policy") or "fail_run"),
        rewrite_retrieval_strategy=str(config.raw.get("rewrite_retrieval_strategy") or "replace"),
        rewrite_adoption_policy=config.rewrite_adoption_policy,
        raw_config=config.raw,
        retriever_config=config.retriever_config,
        retrieval_adapter=retrieval_adapter,
        raw_retrieval=raw_retrieval,
        source_product=sample.source_product,
        memory_candidate_pool_n=config.raw.get("rewrite_memory_candidate_pool_n"),
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
            "selected_rewrite": rewrite_outcome.selected_rewrite,
            "anchor_candidates": rewrite_outcome.anchor_candidates,
            "terminology_hints": rewrite_outcome.terminology_hints,
            "canonical_anchor_hints": rewrite_outcome.canonical_anchor_hints,
            "rewrite_query_profile": str(config.raw.get("rewrite_query_profile") or "compact_anchor"),
            "rewrite_llm_attempted": rewrite_outcome.rewrite_llm_attempted,
            "rewrite_llm_succeeded": rewrite_outcome.rewrite_llm_succeeded,
            "rewrite_heuristic_fallback_used": rewrite_outcome.rewrite_heuristic_fallback_used,
            "planner_call_count": 0,
            "rewrite_call_count": int(rewrite_outcome.rewrite_llm_call_count or 0),
            "llm_call_count": int(rewrite_outcome.rewrite_llm_call_count or 0),
            "final_rewrite_latency_ms": rewrite_outcome.final_rewrite_latency_ms,
            "pure_rewrite_latency_ms": rewrite_outcome.pure_rewrite_latency_ms,
            "multi_source_anchor_hints": rewrite_outcome.multi_source_anchor_hints,
            "memory_hint_query": rewrite_outcome.memory_hint_query,
            "memory_hint_retrieval_applied": rewrite_outcome.memory_hint_retrieval_applied,
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
    retrieval_adapter: DbAnnRuntimeRetrievalAdapter | None,
    multi_source_anchor_index: Any | None,
    raw_retrieval_by_sample: dict[str, list[RetrievalCandidate]],
    java_client: JavaRetrievalEvalClient | None = None,
    java_settings: JavaRetrievalEvalSettings | None = None,
) -> dict[str, Any]:
    started = time.perf_counter()
    raw_retrieval = raw_retrieval_by_sample.get(sample.sample_id, [])
    metrics, rewrite_info, retrieval = _evaluate_mode(
        mode=mode,
        sample=sample,
        chunks=chunks,
        memories=memories,
        config=config,
        memory_strategy_filters=memory_strategy_filters,
        source_gating_run_id=source_gating_run_id,
        comparison_source_runs=comparison_source_runs,
        retrieval_adapter=retrieval_adapter,
        multi_source_anchor_index=multi_source_anchor_index,
        raw_retrieval=raw_retrieval,
        java_client=java_client,
        java_settings=java_settings,
    )
    rewrite_info = _with_llm_trace_defaults(mode, rewrite_info)
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
                "retrieval_backend": normalize_retrieval_backend(str(config.raw.get("retrieval_backend") or "")),
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
        active_modes = [mode for mode in configured_modes if mode in MODES] or list(LEGACY_DEFAULT_MODES)
        if synthetic_free_baseline:
            active_modes = ["raw_only"]
        eval_concurrency = _resolve_eval_concurrency(config.raw)
        retrieval_backend = normalize_retrieval_backend(str(config.raw.get("retrieval_backend") or "local"))
        java_settings = java_retrieval_settings_from_config(config.raw)
        if java_settings:
            _validate_java_backend_modes(active_modes=active_modes, settings=java_settings)
            LOGGER.info(
                "retrieval_eval_java_backend enabled=true base_url=%s supported_modes=%s",
                java_settings.base_url,
                sorted(JAVA_RETRIEVAL_SUPPORTED_FORCED_MODES),
            )
        java_client = build_java_retrieval_client_from_config(config.raw) if java_settings else None

        eval_query_language = str(config.raw.get("eval_query_language") or "ko").strip().lower()
        samples = load_eval_samples(connection, dataset_id=dataset_id, query_language=eval_query_language)
        LOGGER.info(
            "retrieval_eval_parallelism samples=%s modes=%s concurrency=%s",
            len(samples),
            len(active_modes),
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
                "retrieval_eval_dataset_scope_missing_source_products dataset_id=%s expected_doc_ids=%s; "
                "falling back to expected_doc_ids-only chunk scope",
                dataset_id,
                len(expected_doc_ids),
            )
        elif dataset_id and not allowed_products and not expected_doc_ids:
            LOGGER.warning(
                "retrieval_eval_dataset_scope_empty dataset_id=%s; falling back to full corpus",
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
                "retrieval_eval_dataset_scope dataset_id=%s source_products=%s product_filters=%s expected_doc_ids=%s loaded_chunks=%s",
                dataset_id,
                len(dataset_scope["source_products"]),
                len(allowed_products),
                len(expected_doc_ids),
                len(chunks),
            )
        needs_memory = any(mode != "raw_only" for mode in active_modes)
        memories = []
        if needs_memory and retrieval_backend != "db_ann":
            memories = load_memory_items(connection, memory_experiment_key=config.experiment_key)
        retriever_metadata = runtime_retriever_metadata(
            retriever_config=config.retriever_config,
            retrieval_adapter=retrieval_adapter,
        )
        raw_retrieval_by_sample, empty_raw_sample_ids = _precompute_raw_retrieval(
            samples=samples,
            chunks=chunks,
            config=config,
            retrieval_adapter=retrieval_adapter,
            retrieval_backend=retrieval_backend,
        )
        raw_cache_path = _raw_retrieval_cache_path(output_root, config.experiment_key)
        _write_raw_retrieval_cache(
            path=raw_cache_path,
            experiment_key=config.experiment_key,
            samples=samples,
            raw_retrieval_by_sample=raw_retrieval_by_sample,
            retriever_metadata=retriever_metadata,
            empty_sample_ids=empty_raw_sample_ids,
        )
        multi_source_anchor_index = None
        if _is_multi_source_anchor_expansion_enabled(config.raw):
            multi_source_anchor_index = load_multi_source_anchor_index(
                connection,
                relation_version=str(config.raw.get("multi_source_anchor_relation_version") or "multi-source-anchor-v1"),
                relation_types=_multi_source_relation_types(config.raw),
                min_relation_score=config.raw.get("multi_source_anchor_min_score", 0.72),
            )
        raw_metrics_by_sample: dict[str, dict[str, float]] = {
            sample.sample_id: retrieval_metrics(
                expected_chunk_ids=sample.expected_chunk_ids,
                expected_doc_ids=sample.expected_doc_ids,
                retrieved=raw_retrieval_by_sample.get(sample.sample_id, []),
            )
            for sample in samples
        }
        mode_scores: dict[str, list[dict[str, Any]]] = defaultdict(list)
        rewrite_rows: dict[str, list[dict[str, Any]]] = defaultdict(list)
        rewrite_generation_stats_by_mode: dict[str, dict[str, int]] = defaultdict(
            lambda: {
                "rewrite_llm_attempted_count": 0,
                "rewrite_llm_success_count": 0,
                "rewrite_llm_failure_count": 0,
                "rewrite_heuristic_fallback_count": 0,
                "planner_call_count": 0,
                "rewrite_call_count": 0,
                "llm_call_count": 0,
            }
        )

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
                        retrieval_adapter=retrieval_adapter,
                        multi_source_anchor_index=multi_source_anchor_index,
                        raw_retrieval_by_sample=raw_retrieval_by_sample,
                        java_client=java_client,
                        java_settings=java_settings,
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
                        retrieval_adapter=retrieval_adapter,
                        multi_source_anchor_index=multi_source_anchor_index,
                        raw_retrieval_by_sample=raw_retrieval_by_sample,
                        java_client=java_client,
                        java_settings=java_settings,
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

        raw_metrics_by_sample.update(
            {
                row["sample"].sample_id: row["metrics"]
                for row in evaluated
                if row["mode"] == "raw_only"
            }
        )

        for row in evaluated:
            sample = row["sample"]
            mode = row["mode"]
            metrics = row["metrics"]
            rewrite_info = row["rewrite_info"]
            retrieval = row["retrieval"]
            backend = str(
                rewrite_info.get("backend")
                or ("java" if rewrite_info.get("java_backend_enabled") else "python_legacy")
            )
            raw_metrics = raw_metrics_by_sample.get(sample.sample_id, {})
            raw_rank = _first_expected_rank(
                sample=sample,
                retrieval=raw_retrieval_by_sample.get(sample.sample_id, []),
            )
            final_rank = _first_expected_rank(sample=sample, retrieval=retrieval)
            degraded_vs_raw = float(metrics.get("mrr@10", 0.0)) < float(raw_metrics.get("mrr@10", 0.0))
            rewrite_generation_stats = rewrite_generation_stats_by_mode[mode]
            rewrite_generation_stats["rewrite_llm_attempted_count"] += (
                1 if rewrite_info.get("rewrite_llm_attempted") else 0
            )
            rewrite_generation_stats["rewrite_llm_success_count"] += (
                1 if rewrite_info.get("rewrite_llm_succeeded") else 0
            )
            rewrite_generation_stats["rewrite_heuristic_fallback_count"] += (
                1 if rewrite_info.get("rewrite_heuristic_fallback_used") else 0
            )
            rewrite_generation_stats["rewrite_llm_failure_count"] += (
                1
                if rewrite_info.get("rewrite_llm_attempted") and not rewrite_info.get("rewrite_llm_succeeded")
                else 0
            )
            rewrite_generation_stats["planner_call_count"] += int(rewrite_info.get("planner_call_count") or 0)
            rewrite_generation_stats["rewrite_call_count"] += int(rewrite_info.get("rewrite_call_count") or 0)
            rewrite_generation_stats["llm_call_count"] += int(rewrite_info.get("llm_call_count") or 0)
            mode_scores[mode].append(
                {
                    "sample_id": sample.sample_id,
                    "split": sample.split,
                    "category": sample.query_category,
                    "mode": mode,
                    "backend": backend,
                    "java_endpoint": rewrite_info.get("java_endpoint"),
                    "java_forced_mode": rewrite_info.get("java_forced_mode"),
                    "java_selected_mode": rewrite_info.get("java_selected_mode"),
                    "java_warnings": rewrite_info.get("java_warnings"),
                    "selected_strategy": rewrite_info.get("selected_strategy"),
                    "router_reason": rewrite_info.get("router_reason"),
                    "llm_call_count": int(rewrite_info.get("llm_call_count") or 0),
                    "planner_call_count": int(rewrite_info.get("planner_call_count") or 0),
                    "rewrite_call_count": int(rewrite_info.get("rewrite_call_count") or 0),
                    **metrics,
                    "rewrite_applied": bool(rewrite_info["rewrite_applied"]),
                    "confidence_delta": float(
                        rewrite_info.get("best_candidate_confidence", 0.0)
                        - rewrite_info.get("raw_confidence", 0.0)
                    ),
                    "elapsed_ms": float(row.get("elapsed_ms", 0.0)),
                    "degraded_vs_raw": degraded_vs_raw,
                    "final_rewrite_latency_ms": rewrite_info.get("final_rewrite_latency_ms"),
                    "pure_rewrite_latency_ms": rewrite_info.get("pure_rewrite_latency_ms"),
                    "memory_hint_retrieval_applied": bool(
                        rewrite_info.get("memory_hint_retrieval_applied")
                    ),
                }
            )
            rewrite_rows[mode].append(
                {
                    "sample_id": sample.sample_id,
                    "mode": mode,
                    "backend": backend,
                    "java_endpoint": rewrite_info.get("java_endpoint"),
                    "java_forced_mode": rewrite_info.get("java_forced_mode"),
                    "java_selected_mode": rewrite_info.get("java_selected_mode"),
                    "java_warnings": rewrite_info.get("java_warnings"),
                    "raw_query": sample.query_text,
                    "selected_strategy": rewrite_info.get("selected_strategy"),
                    "router_reason": rewrite_info.get("router_reason"),
                    "llm_call_count": int(rewrite_info.get("llm_call_count") or 0),
                    "planner_call_count": int(rewrite_info.get("planner_call_count") or 0),
                    "rewrite_call_count": int(rewrite_info.get("rewrite_call_count") or 0),
                    "strategy_router_metadata": rewrite_info.get("strategy_router_metadata"),
                    "rewrite_applied": bool(rewrite_info["rewrite_applied"]),
                    "raw_confidence": rewrite_info["raw_confidence"],
                    "best_candidate_confidence": rewrite_info["best_candidate_confidence"],
                    "confidence_delta": rewrite_info["best_candidate_confidence"] - rewrite_info["raw_confidence"],
                    "final_query": rewrite_info["final_query"],
                    "rewrite_reason": rewrite_info["rewrite_reason"],
                    "selected_rewrite": rewrite_info.get("selected_rewrite"),
                    "raw_rank": raw_rank,
                    "final_rank": final_rank,
                    "raw_retrieval_rank": raw_rank,
                    "final_retrieval_rank": final_rank,
                    "elapsed_ms": float(row.get("elapsed_ms", 0.0)),
                    "degraded_vs_raw": degraded_vs_raw,
                    "raw_metrics": raw_metrics,
                    "final_metrics": metrics,
                    "rewrite_metrics": (
                        metrics
                        if mode in {
                            "rewrite_always",
                            "selective_rewrite",
                            "selective_rewrite_with_session",
                            "anchor_aware_rewrite",
                            "agentic_multi_query",
                            "strategy_router",
                        }
                        and bool(rewrite_info["rewrite_applied"])
                        else None
                    ),
                    "memory_hint_query": rewrite_info.get("memory_hint_query"),
                    "memory_hint_retrieval_applied": bool(
                        rewrite_info.get("memory_hint_retrieval_applied")
                    ),
                    "rewrite_llm_attempted": bool(rewrite_info.get("rewrite_llm_attempted")),
                    "rewrite_llm_succeeded": bool(rewrite_info.get("rewrite_llm_succeeded")),
                    "rewrite_heuristic_fallback_used": bool(rewrite_info.get("rewrite_heuristic_fallback_used")),
                    "final_rewrite_latency_ms": rewrite_info.get("final_rewrite_latency_ms"),
                    "pure_rewrite_latency_ms": rewrite_info.get("pure_rewrite_latency_ms"),
                    "raw_mrr": raw_metrics.get("mrr@10", 0.0),
                    "mode_mrr": metrics["mrr@10"],
                    "raw_ndcg": raw_metrics.get("ndcg@10", 0.0),
                    "mode_ndcg": metrics["ndcg@10"],
                    "memory_top_n": rewrite_info.get("memory_top_n", []),
                    "top_memory_candidates": rewrite_info.get("memory_top_n", []),
                    "rewrite_candidates": rewrite_info.get("candidates", []),
                    "anchor_candidates": rewrite_info.get("anchor_candidates", []),
                    "terminology_hints": rewrite_info.get("terminology_hints"),
                    "canonical_anchor_hints": rewrite_info.get("canonical_anchor_hints"),
                    "multi_source_anchor_hints": rewrite_info.get("multi_source_anchor_hints"),
                    "agentic_plan": rewrite_info.get("agentic_plan"),
                    "subquery_traces": rewrite_info.get("subquery_traces", []),
                    "merge_strategy": rewrite_info.get("merge_strategy"),
                    "max_subqueries": rewrite_info.get("max_subqueries"),
                    "rrf_k": rewrite_info.get("rrf_k"),
                    "planner_fallback_applied": rewrite_info.get("planner_fallback_applied"),
                    "agentic_planner_latency_ms": rewrite_info.get("agentic_planner_latency_ms"),
                    "agentic_total_latency_ms": rewrite_info.get("agentic_total_latency_ms"),
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
                rewrite_info = row["rewrite_info"]
                java_backend_row = bool(rewrite_info.get("java_backend_enabled"))
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
                            (
                                "java-retrieval-eval"
                                if java_backend_row
                                else runtime_retriever_label(
                                    retriever_config=config.retriever_config,
                                    retrieval_adapter=retrieval_adapter,
                                )
                            ),
                            item.score,
                            Jsonb(
                                {
                                    "mode": mode,
                                    "experiment_run_id": run_context.experiment_run_id,
                                    "retrieved_document_id": item.document_id,
                                    "retrieved_chunk_id": item.chunk_id,
                                    "backend": "java" if java_backend_row else "python_legacy",
                                    "java_backend_enabled": java_backend_row,
                                    "java_endpoint": (
                                        JAVA_RETRIEVAL_ENDPOINT_PATH if java_backend_row else None
                                    ),
                                    "java_forced_mode": rewrite_info.get("java_forced_mode"),
                                    "java_selected_mode": rewrite_info.get("java_selected_mode"),
                                    "java_warnings": rewrite_info.get("java_warnings"),
                                    **retriever_metadata,
                                }
                            ),
                        ),
                    )

        summary_rows: list[dict[str, Any]] = []
        category_rows: list[dict[str, Any]] = []
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
                if mode in {
                    "rewrite_always",
                    "selective_rewrite",
                    "selective_rewrite_with_session",
                    "anchor_aware_rewrite",
                    "strategy_router",
                }
                else 0.0
            )
            avg_confidence_delta = _mean([float(row.get("confidence_delta", 0.0)) for row in rows])
            avg_latency_ms = _mean([float(row.get("elapsed_ms", 0.0)) for row in rows])
            degraded_query_count = sum(1 for row in rows if bool(row.get("degraded_vs_raw")))
            llm_summary = _llm_call_summary(rows)

            summary_rows.append(
                {
                    "mode": mode,
                    **aggregates,
                    "avg_latency_ms": avg_latency_ms,
                    **llm_summary,
                    "degraded_query_count": degraded_query_count,
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

        summary_path = output_root / f"retrieval_summary_{config.experiment_key}.json"
        summary_csv_path = output_root / f"retrieval_summary_{config.experiment_key}.csv"
        category_csv_path = output_root / f"retrieval_by_category_{config.experiment_key}.csv"
        rewrite_case_path = output_root / f"rewrite_cases_{config.experiment_key}.json"
        summary_path.parent.mkdir(parents=True, exist_ok=True)
        rewrite_stats_by_mode_payload = {
            mode: {
                "rewrite_llm_attempted_count": int(stats.get("rewrite_llm_attempted_count", 0)),
                "rewrite_llm_success_count": int(stats.get("rewrite_llm_success_count", 0)),
                "rewrite_llm_failure_count": int(stats.get("rewrite_llm_failure_count", 0)),
                "rewrite_heuristic_fallback_count": int(stats.get("rewrite_heuristic_fallback_count", 0)),
                "planner_call_count": int(stats.get("planner_call_count", 0)),
                "rewrite_call_count": int(stats.get("rewrite_call_count", 0)),
                "llm_call_count": int(stats.get("llm_call_count", 0)),
                # backward-compatible aliases
                "llm_attempted_count": int(stats.get("rewrite_llm_attempted_count", 0)),
                "llm_success_count": int(stats.get("rewrite_llm_success_count", 0)),
                "llm_failure_count": int(stats.get("rewrite_llm_failure_count", 0)),
                "heuristic_fallback_count": int(stats.get("rewrite_heuristic_fallback_count", 0)),
            }
            for mode, stats in rewrite_generation_stats_by_mode.items()
        }
        rewrite_llm_attempted_total = sum(
            int(stats.get("rewrite_llm_attempted_count", 0)) for stats in rewrite_generation_stats_by_mode.values()
        )
        rewrite_llm_success_total = sum(
            int(stats.get("rewrite_llm_success_count", 0)) for stats in rewrite_generation_stats_by_mode.values()
        )
        rewrite_llm_failure_total = sum(
            int(stats.get("rewrite_llm_failure_count", 0)) for stats in rewrite_generation_stats_by_mode.values()
        )
        rewrite_heuristic_fallback_total = sum(
            int(stats.get("rewrite_heuristic_fallback_count", 0)) for stats in rewrite_generation_stats_by_mode.values()
        )
        planner_call_total = sum(
            int(stats.get("planner_call_count", 0)) for stats in rewrite_generation_stats_by_mode.values()
        )
        rewrite_call_total = sum(
            int(stats.get("rewrite_call_count", 0)) for stats in rewrite_generation_stats_by_mode.values()
        )
        llm_call_total = sum(
            int(stats.get("llm_call_count", 0)) for stats in rewrite_generation_stats_by_mode.values()
        )

        rewrite_flat_rows = [row for rows in rewrite_rows.values() for row in rows]
        llm_metric_rows = rewrite_rows.get("strategy_router") or rewrite_flat_rows
        llm_summary_payload = _llm_call_summary(llm_metric_rows)
        canonical_version_payload = canonical_anchor_version_payload(config.raw)
        summary_payload = {
            "experiment_key": config.experiment_key,
            "experiment_run_id": run_context.experiment_run_id,
            "dataset_id": dataset_id,
            **canonical_version_payload,
            "canonical_anchor_versions": canonical_version_payload,
            "source_gating_run_id": source_gating_run_id,
            "comparison_source_runs": comparison_source_runs,
            "memory_generation_strategies": memory_strategy_filters,
            "synthetic_free_baseline": synthetic_free_baseline,
            "memory_experiment_key": config.experiment_key if needs_memory else None,
            "memory_entry_count_loaded": None if retrieval_backend == "db_ann" else len(memories),
            **retriever_metadata,
            "backend": "java" if java_settings else "python_legacy",
            "java_backend_enabled": bool(java_settings),
            "java_endpoint": JAVA_RETRIEVAL_ENDPOINT_PATH if java_settings else None,
            "java_backend_base_url": java_settings.base_url if java_settings else None,
            "java_backend_supported_modes": sorted(JAVA_RETRIEVAL_SUPPORTED_FORCED_MODES),
            "java_error_policy": "fail_fast_run_level" if java_settings else None,
            "active_modes": active_modes,
            "raw_retrieval_cache_path": str(raw_cache_path),
            "raw_retrieval_empty_sample_ids": empty_raw_sample_ids,
            "rewrite_llm_attempted_count": rewrite_llm_attempted_total,
            "rewrite_llm_success_count": rewrite_llm_success_total,
            "rewrite_llm_failure_count": rewrite_llm_failure_total,
            "rewrite_heuristic_fallback_count": rewrite_heuristic_fallback_total,
            "llm_metric_scope": "strategy_router" if rewrite_rows.get("strategy_router") else "all_modes",
            "avg_llm_calls": llm_summary_payload["avg_llm_calls"],
            "planner_call_count": llm_summary_payload["planner_call_count"],
            "rewrite_call_count": llm_summary_payload["rewrite_call_count"],
            "llm_call_count": llm_summary_payload["llm_call_count"],
            "total_planner_call_count": planner_call_total,
            "total_rewrite_call_count": rewrite_call_total,
            "total_llm_call_count": llm_call_total,
            "selected_raw_count": llm_summary_payload["selected_raw_count"],
            "selected_selective_count": llm_summary_payload["selected_selective_count"],
            "selected_anchor_count": llm_summary_payload["selected_anchor_count"],
            "selected_agentic_count": llm_summary_payload["selected_agentic_count"],
            "rewrite_generation_stats": rewrite_stats_by_mode_payload,
            "multi_source_anchor_expansion_enabled": _is_multi_source_anchor_expansion_enabled(config.raw),
            "multi_source_anchor_diagnostics": _aggregate_multi_source_anchor_diagnostics(rewrite_flat_rows),
            "degraded_query_count_definition": "per-sample mode mrr@10 lower than the same sample raw_only mrr@10",
            "summary": summary_rows,
            "category_summary": category_rows,
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
                "avg_latency_ms",
                "avg_llm_calls",
                "llm_call_count",
                "planner_call_count",
                "rewrite_call_count",
                "selected_raw_count",
                "selected_selective_count",
                "selected_anchor_count",
                "selected_agentic_count",
                "degraded_query_count",
                "adoption_rate",
                "rewrite_rejection_rate",
                "avg_confidence_delta",
                "rewrite_gain_mrr",
                "rewrite_gain_ndcg",
                "bad_rewrite_rate",
            ],
        )
        _write_csv(category_csv_path, category_rows, ["mode", "category", *METRIC_KEYS])

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
        )

        recorder.finish_run(
            run_context,
            status="completed",
            metrics={
                "summary_rows": summary_rows,
                "category_rows": category_rows[:25],
                "report_paths": {
                    "summary_json": str(summary_path),
                    "summary_csv": str(summary_csv_path),
                    "category_csv": str(category_csv_path),
                    "raw_retrieval_cache": str(raw_cache_path),
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
