from __future__ import annotations

import json
import math
from pathlib import Path
from tempfile import TemporaryDirectory
from typing import Any
from uuid import UUID

from .official_retrieval_eval import GATING_COMPARISON_MODES


POLICY_VERSION = 2
_REWRITE_FIELDS = {
    "rewrite_applied",
    "rewrite_reason",
    "raw_confidence",
    "best_candidate_confidence",
    "rewrite_llm_attempted",
    "rewrite_llm_succeeded",
    "rewrite_heuristic_fallback_used",
    "final_rewrite_latency_ms",
    "pure_rewrite_latency_ms",
}


def answer_snapshot(config: dict[str, Any], mode: str) -> str | None:
    if mode == "raw_only":
        return None
    preset = GATING_COMPARISON_MODES.get(mode)
    if preset is not None:
        snapshots = config.get("comparison_snapshots") or {}
        return (snapshots.get(preset) or {}).get("gating_batch_id")
    return config.get("sourceGatingBatchId") or config.get("source_gating_batch_id") or config.get("snapshot_id")


def _context_path(output_root: Path, retrieval_run_id: str) -> Path:
    return output_root / "retrieval_context" / f"{UUID(retrieval_run_id)}.json"


def _identity(
    retrieval_run_id: str,
    experiment_key: str,
    config_hash: str,
    dataset_id: str | None,
    mode: str,
    snapshot: str | None,
) -> dict[str, Any]:
    if not experiment_key or not config_hash or not mode:
        raise ValueError("Retrieval context requires experiment key, configuration hash and answer mode.")
    return {
        "eval_policy_version": POLICY_VERSION,
        "retrieval_run_id": str(UUID(retrieval_run_id)),
        "experiment_key": experiment_key,
        "config_hash": config_hash,
        "dataset_id": dataset_id,
        "mode": mode,
        "snapshot": snapshot,
    }


def write_retrieval_context(
    output_root: Path,
    *,
    retrieval_run_id: str,
    experiment_key: str,
    config_hash: str,
    dataset_id: str | None,
    mode: str,
    snapshot: str | None,
    rows: list[dict[str, Any]],
) -> Path:
    path = _context_path(output_root, retrieval_run_id)
    identity = _identity(retrieval_run_id, experiment_key, config_hash, dataset_id, mode, snapshot)
    projected_rows = [
        {
            "sample_id": row["sample_id"],
            "query": row["query"],
            "final_query": row["final_query"],
            "snapshot": row.get("snapshot"),
            "retrieved": [
                {"chunk_id": item["chunk_id"], "score": item["score"]} for item in row["retrieved"]
            ],
            "rewrite": {key: value for key, value in row.get("rewrite", {}).items() if key in _REWRITE_FIELDS},
        }
        for row in rows
    ]
    _index_rows(projected_rows, snapshot)
    path.parent.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory(dir=path.parent) as temporary:
        pending_path = Path(temporary) / path.name
        pending_path.write_text(
            json.dumps({**identity, "rows": projected_rows}, ensure_ascii=False, allow_nan=False),
            encoding="utf-8",
        )
        pending_path.replace(path)
    return path


def load_retrieval_context(
    output_root: Path,
    *,
    retrieval_run_id: str,
    experiment_key: str,
    config_hash: str,
    dataset_id: str | None,
    mode: str,
    snapshot: str | None,
    sample_queries: dict[str, str],
) -> dict[str, dict[str, Any]]:
    path = _context_path(output_root, retrieval_run_id)
    identity = _identity(retrieval_run_id, experiment_key, config_hash, dataset_id, mode, snapshot)
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exception:
        raise ValueError(f"Retrieval context unavailable for run {retrieval_run_id}.") from exception
    if not isinstance(payload, dict):
        raise ValueError(f"Invalid retrieval context for run {retrieval_run_id}.")
    for key, value in identity.items():
        if key not in payload or payload[key] != value:
            raise ValueError(f"Retrieval context {key} mismatch for run {retrieval_run_id}.")
    rows = _index_rows(payload.get("rows"), snapshot)
    if {sample_id: row["query"] for sample_id, row in rows.items()} != sample_queries:
        raise ValueError(f"Retrieval context samples mismatch for run {retrieval_run_id}.")
    return rows


def _index_rows(rows: Any, snapshot: str | None) -> dict[str, dict[str, Any]]:
    if not isinstance(rows, list):
        raise ValueError("Retrieval context rows must be an array.")
    indexed = {}
    for row in rows:
        if not isinstance(row, dict) or any(
            not isinstance(row.get(key), str) or not row[key].strip()
            for key in ("sample_id", "query", "final_query")
        ):
            raise ValueError("Invalid retrieval context sample.")
        sample_id = row["sample_id"]
        if sample_id in indexed:
            raise ValueError(f"Duplicate retrieval context sample {sample_id}.")
        effective_snapshot = row.get("snapshot")
        if snapshot is not None and (
            not isinstance(effective_snapshot, dict)
            or effective_snapshot.get("sourceGatingBatchIds") != [snapshot]
        ):
            raise ValueError(f"Retrieval context snapshot mismatch for sample {sample_id}.")
        if not isinstance(row.get("retrieved"), list) or not isinstance(row.get("rewrite"), dict):
            raise ValueError(f"Invalid retrieval context result for sample {sample_id}.")
        for item in row["retrieved"]:
            if not isinstance(item, dict) or not isinstance(item.get("chunk_id"), str) or not item["chunk_id"].strip():
                raise ValueError(f"Invalid retrieved chunk for sample {sample_id}.")
            score = item.get("score")
            if type(score) not in (float, int) or not math.isfinite(score):
                raise ValueError(f"Invalid retrieved score for sample {sample_id}.")
        indexed[sample_id] = row
    return indexed
