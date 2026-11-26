from __future__ import annotations

from typing import Any

from .java_retrieval_client import (
    OFFICIAL_EVAL_POLICY_VERSION,
    JavaRetrievalClientError,
    JavaRetrievalEvalResult,
    normalize_forced_mode,
)


GATING_COMPARISON_MODES = {
    "selective_rewrite_ungated": "ungated",
    "selective_rewrite_rule_only": "rule_only",
    "selective_rewrite_full_gating": "full_gating",
}
OFFICIAL_MODES = frozenset({
    "raw_only",
    "selective_rewrite",
    "anchor_aware_rewrite",
    *GATING_COMPARISON_MODES,
})


def official_forced_mode(mode: str) -> str | None:
    normalized = normalize_forced_mode(mode)
    if normalized in GATING_COMPARISON_MODES:
        return "selective_rewrite"
    return normalized if normalized in OFFICIAL_MODES else None


def official_runtime_config(
    source: dict[str, Any], mode: str, forced_mode: str,
) -> dict[str, Any]:
    runtime = {
        **source,
        "eval_policy_version": OFFICIAL_EVAL_POLICY_VERSION,
        "mode": forced_mode,
        "forced_retrieval_mode": forced_mode,
    }
    gating_preset = GATING_COMPARISON_MODES.get(mode)
    if gating_preset is None:
        return runtime
    snapshots = source.get("comparison_snapshots")
    snapshot = snapshots.get(gating_preset) if isinstance(snapshots, dict) else None
    snapshot = snapshot if isinstance(snapshot, dict) else {}
    batch_id = snapshot.get("gating_batch_id")
    run_id = snapshot.get("source_gating_run_id")
    if not isinstance(batch_id, str) or not batch_id.strip() or not isinstance(run_id, str) or not run_id.strip():
        raise JavaRetrievalClientError(
            code="official_comparison_snapshot_required",
            detail=f"Official {mode} requires a gating batch and run from comparison_snapshots.",
        )
    # The HTTP resolver also accepts camelCase, so set its higher-priority aliases.
    runtime.update({
        "gatingPreset": gating_preset,
        "sourceGatingBatchId": batch_id,
        "sourceGatingRunId": run_id,
        "sourceGatingBatchIds": [batch_id],
        "sourceGatingRunIds": [run_id],
    })
    return runtime


def official_rewrite_diagnostics(
    result: JavaRetrievalEvalResult,
    *,
    mode: str,
    runtime: dict[str, Any],
) -> dict[str, Any]:
    response_trace = result.raw_response.get("trace")
    trace = response_trace.get("retrievalTrace") if isinstance(response_trace, dict) else None
    if (
        not isinstance(trace, dict)
        or trace.get("evalPolicyVersion") != OFFICIAL_EVAL_POLICY_VERSION
        or not isinstance(trace.get("rewriteApplied"), bool)
        or not isinstance(trace.get("decisionReason"), str)
    ):
        raise JavaRetrievalClientError(
            code="invalid_official_retrieval_trace",
            detail=f"Java did not return the required evaluation policy trace for {mode}.",
        )
    snapshot = trace.get("snapshot")
    gating_preset = GATING_COMPARISON_MODES.get(mode)
    if gating_preset is not None and (
        not isinstance(snapshot, dict)
        or snapshot.get("gatingPreset") != gating_preset
        or snapshot.get("sourceGatingBatchIds") != runtime["sourceGatingBatchIds"]
        or snapshot.get("sourceGatingRunIds") != runtime["sourceGatingRunIds"]
    ):
        raise JavaRetrievalClientError(
            code="java_retrieval_snapshot_mismatch",
            detail=f"Java did not execute {mode} against its configured gating batch and run.",
        )
    if mode != "raw_only" and gating_preset is None:
        declared_batch = runtime.get("sourceGatingBatchId") or runtime.get("source_gating_batch_id")
        declared_run = runtime.get("sourceGatingRunId") or runtime.get("source_gating_run_id")
        if (declared_batch or declared_run) and (
            not isinstance(snapshot, dict)
            or (declared_batch and snapshot.get("sourceGatingBatchIds") != [declared_batch])
            or (declared_run and snapshot.get("sourceGatingRunIds") != [declared_run])
        ):
            raise JavaRetrievalClientError(
                code="java_retrieval_snapshot_mismatch",
                detail=f"Java did not execute {mode} against its configured gating batch and run.",
            )
    generation = trace.get("generation")
    generation = generation if isinstance(generation, dict) else {}
    return {
        "eval_policy_version": OFFICIAL_EVAL_POLICY_VERSION,
        "rewrite_applied": trace["rewriteApplied"],
        "rewrite_reason": trace["decisionReason"],
        "rejected_reason": trace.get("rejectedReason"),
        "raw_confidence": trace.get("rawConfidence"),
        "best_candidate_confidence": trace.get("bestCandidateConfidence"),
        "memory_top_n": trace.get("memoryCandidates") or [],
        "candidates": trace.get("rewriteCandidates") or [],
        "selected_rewrite": trace.get("selectedCandidate"),
        "anchor_candidates": trace.get("anchorCandidates") or [],
        "anchor_injection_enabled": trace.get("anchorInjectionEnabled"),
        "rewrite_llm_attempted": generation.get("attempted"),
        "rewrite_llm_succeeded": generation.get("succeeded"),
        "rewrite_heuristic_fallback_used": generation.get("heuristicFallback"),
        "rewrite_generation": generation or None,
        "final_rewrite_latency_ms": trace.get("rewriteLatencyMs"),
        "pure_rewrite_latency_ms": generation.get("latencyMs"),
        "server_latency_ms": result.raw_response.get("latencyMs"),
        "raw_retrieval_latency_ms": trace.get("rawLatencyMs"),
        "snapshot": snapshot,
    }
