from __future__ import annotations

import csv
import json
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable

import yaml

try:
    from common.experiment_config import load_experiment_config
    from eval.java_retrieval_client import (
        JAVA_RETRIEVAL_AGENTIC_MODE,
        JAVA_RETRIEVAL_ENDPOINT_PATH,
        JavaRetrievalEvalClient,
        JavaRetrievalEvalSettings,
        normalize_forced_mode,
    )
    from eval.retrieval_eval import METRIC_KEYS, run_retrieval_eval
except ModuleNotFoundError:  # pragma: no cover
    from pipeline.common.experiment_config import load_experiment_config
    from pipeline.eval.java_retrieval_client import (
        JAVA_RETRIEVAL_AGENTIC_MODE,
        JAVA_RETRIEVAL_ENDPOINT_PATH,
        JavaRetrievalEvalClient,
        JavaRetrievalEvalSettings,
        normalize_forced_mode,
    )
    from pipeline.eval.retrieval_eval import METRIC_KEYS, run_retrieval_eval


COMPARISON_SUPPORTED_MODES = (
    "raw_only",
    "selective_rewrite",
    "anchor_aware_rewrite",
    "strategy_router",
)
COMPARISON_BLOCKED_MODES = (JAVA_RETRIEVAL_AGENTIC_MODE,)
COMPARISON_METRIC_KEYS = tuple(METRIC_KEYS)
COMPARISON_REPORT_SCHEMA_VERSION = "retrieval-comparison-report-v1"
COMPARISON_REPORT_REQUIRED_KEYS = frozenset(
    {
        "schema_version",
        "generated_at",
        "legacy_summary",
        "java_summary",
        "metric_delta_rows",
        "mismatch_rows",
        "compared_modes",
        "blocked_modes",
        "java_endpoint",
        "java_backend",
    }
)
METRIC_DELTA_ROW_REQUIRED_KEYS = frozenset(
    {"mode", "metric", "legacy_value", "java_value", "delta"}
)
MISMATCH_ROW_REQUIRED_KEYS = frozenset(
    {
        "sample_id",
        "query",
        "mode",
        "expected_chunk_ids",
        "legacy_retrieved_chunk_ids",
        "java_retrieved_chunk_ids",
        "overlap_count",
        "exact_match",
        "notes",
    }
)
PHASE_9_READINESS_CRITERIA = (
    "Java endpoint smoke tests pass.",
    "Comparison runner passes for supported non-agentic modes.",
    "Metric delta report is reviewed.",
    "Mismatch sample report is reviewed.",
    "agentic_multi_query remains blocked or has a separate approved policy.",
    "Python legacy eval remains available as fallback/regression path.",
    "Admin GUI impact is none or explicitly tested.",
)

Runner = Callable[..., dict[str, Any]]


class RetrievalEvalComparisonError(RuntimeError):
    def __init__(self, *, code: str, detail: str) -> None:
        super().__init__(f"{code}: {detail}")
        self.code = code
        self.detail = detail


def normalize_comparison_modes(modes: list[str] | tuple[str, ...] | None) -> list[str]:
    raw_modes = list(modes or COMPARISON_SUPPORTED_MODES)
    normalized: list[str] = []
    unsupported: list[str] = []
    for mode in raw_modes:
        normalized_mode = normalize_forced_mode(mode)
        if normalized_mode in normalized:
            continue
        if normalized_mode not in COMPARISON_SUPPORTED_MODES:
            unsupported.append(normalized_mode)
            continue
        normalized.append(normalized_mode)

    if unsupported:
        supported = ", ".join(COMPARISON_SUPPORTED_MODES)
        unsupported_unique = ", ".join(sorted(set(unsupported)))
        if JAVA_RETRIEVAL_AGENTIC_MODE in set(unsupported):
            raise RetrievalEvalComparisonError(
                code="unsupported_agentic_eval",
                detail=(
                    "legacy vs Java retrieval comparison supports only "
                    f"{supported}; unsupported modes: {unsupported_unique}. "
                    "agentic_multi_query remains blocked in Phase 8C."
                ),
            )
        raise RetrievalEvalComparisonError(
            code="unsupported_comparison_mode",
            detail=(
                "legacy vs Java retrieval comparison supports only "
                f"{supported}; unsupported modes: {unsupported_unique}."
            ),
        )
    if not normalized:
        raise RetrievalEvalComparisonError(
            code="comparison_modes_required",
            detail="At least one supported retrieval comparison mode is required.",
        )
    return normalized


def compute_metric_delta_report(
    *,
    legacy_summary_rows: list[dict[str, Any]],
    java_summary_rows: list[dict[str, Any]],
    modes: list[str] | tuple[str, ...],
    metric_keys: tuple[str, ...] = COMPARISON_METRIC_KEYS,
) -> list[dict[str, Any]]:
    legacy_by_mode = _summary_rows_by_mode(legacy_summary_rows)
    java_by_mode = _summary_rows_by_mode(java_summary_rows)
    rows: list[dict[str, Any]] = []
    for mode in modes:
        legacy_row = legacy_by_mode.get(mode)
        java_row = java_by_mode.get(mode)
        if legacy_row is None or java_row is None:
            continue
        for metric_name in metric_keys:
            legacy_value = _float_or_zero(legacy_row.get(metric_name))
            java_value = _float_or_zero(java_row.get(metric_name))
            rows.append(
                {
                    "mode": mode,
                    "metric": metric_name,
                    "legacy_value": legacy_value,
                    "java_value": java_value,
                    "delta": java_value - legacy_value,
                }
            )
    return rows


def build_sample_comparison_report(
    *,
    legacy_rows: list[dict[str, Any]],
    java_rows: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    legacy_by_key = _sample_rows_by_key(legacy_rows)
    java_by_key = _sample_rows_by_key(java_rows)
    keys = sorted(set(legacy_by_key.keys()) | set(java_by_key.keys()))
    rows: list[dict[str, Any]] = []
    for sample_id, mode in keys:
        legacy_row = legacy_by_key.get((sample_id, mode))
        java_row = java_by_key.get((sample_id, mode))
        notes: list[str] = []
        legacy_ids = _retrieved_chunk_ids(legacy_row) if legacy_row else []
        java_ids = _retrieved_chunk_ids(java_row) if java_row else []
        if legacy_row is None:
            notes.append("missing_legacy_row")
        if java_row is None:
            notes.append("missing_java_row")
        exact_match = bool(legacy_row and java_row and legacy_ids == java_ids)
        if legacy_row and java_row and not exact_match:
            if set(legacy_ids) == set(java_ids):
                notes.append("different_order")
            else:
                notes.append("different_ids")

        rows.append(
            {
                "sample_id": sample_id,
                "query": _query_text(legacy_row, java_row),
                "mode": mode,
                "expected_chunk_ids": _expected_chunk_ids(legacy_row, java_row),
                "legacy_retrieved_chunk_ids": legacy_ids,
                "java_retrieved_chunk_ids": java_ids,
                "overlap_count": len(set(legacy_ids) & set(java_ids)),
                "exact_match": exact_match,
                "legacy_metrics": _sample_metrics(legacy_row),
                "java_metrics": _sample_metrics(java_row),
                "metric_delta": _sample_metric_delta(legacy_row, java_row),
                "notes": notes,
            }
        )
    return rows


def run_legacy_vs_java_retrieval_compare(
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
    modes: list[str] | tuple[str, ...] | None = None,
    java_client: JavaRetrievalEvalClient | None = None,
    java_settings: JavaRetrievalEvalSettings | None = None,
    legacy_runner: Runner | None = None,
    java_runner: Runner | None = None,
    fail_fast: bool = True,
    write_report: bool = True,
    report_path: Path | None = None,
) -> dict[str, Any]:
    """Run a Phase 8D audit report without switching the official eval path."""
    base_config = None
    base_raw: dict[str, Any] = {}
    base_experiment_key = experiment
    if legacy_runner is None or java_runner is None or modes is None:
        base_config = load_experiment_config(experiment, experiment_root=experiment_root)
        base_raw = dict(base_config.raw)
        base_experiment_key = base_config.experiment_key

    configured_modes = _configured_modes(base_raw)
    comparison_modes = normalize_comparison_modes(modes or configured_modes or None)
    if not fail_fast:
        raise RetrievalEvalComparisonError(
            code="unsupported_error_policy",
            detail="Phase 8C comparison supports only fail_fast=true.",
        )

    if legacy_runner is None or java_runner is None:
        if base_config is None:
            base_config = load_experiment_config(experiment, experiment_root=experiment_root)
            base_raw = dict(base_config.raw)
            base_experiment_key = base_config.experiment_key
        legacy_payload, java_payload = _run_default_comparison(
            base_raw=base_raw,
            base_experiment_key=base_experiment_key,
            modes=comparison_modes,
            output_root=output_root,
            docs_root=docs_root,
            database_url=database_url,
            db_host=db_host,
            db_port=db_port,
            db_name=db_name,
            db_user=db_user,
            db_password=db_password,
            java_client=java_client,
            java_settings=java_settings,
        )
    else:
        legacy_payload = legacy_runner(
            experiment=experiment,
            backend="python_legacy",
            modes=comparison_modes,
            fail_fast=fail_fast,
        )
        java_payload = java_runner(
            experiment=experiment,
            backend="java",
            modes=comparison_modes,
            fail_fast=fail_fast,
            java_client=java_client,
            java_settings=java_settings,
        )

    legacy_summary_rows = _extract_summary_rows(legacy_payload)
    java_summary_rows = _extract_summary_rows(java_payload)
    metric_delta = compute_metric_delta_report(
        legacy_summary_rows=legacy_summary_rows,
        java_summary_rows=java_summary_rows,
        modes=comparison_modes,
    )

    legacy_sample_rows = _extract_sample_rows(legacy_payload, output_root=output_root)
    java_sample_rows = _extract_sample_rows(java_payload, output_root=output_root)
    sample_comparisons = build_sample_comparison_report(
        legacy_rows=legacy_sample_rows,
        java_rows=java_sample_rows,
    )
    mismatch_samples = [row for row in sample_comparisons if not row["exact_match"]]

    payload: dict[str, Any] = {
        "schema_version": COMPARISON_REPORT_SCHEMA_VERSION,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "phase": "8C",
        "contract_phase": "8D",
        "comparison": "legacy_vs_java_retrieval",
        "experiment_key": base_experiment_key,
        "modes": comparison_modes,
        "compared_modes": comparison_modes,
        "supported_modes": list(COMPARISON_SUPPORTED_MODES),
        "blocked_modes": list(COMPARISON_BLOCKED_MODES),
        "metric_keys": list(COMPARISON_METRIC_KEYS),
        "java_error_policy": "fail_fast_run_level",
        "legacy_backend": "python_legacy",
        "java_backend": "java",
        "java_endpoint": JAVA_RETRIEVAL_ENDPOINT_PATH,
        "legacy_summary": legacy_summary_rows,
        "java_summary": java_summary_rows,
        "official_eval_switched": False,
        "legacy_eval_deleted": False,
        "metric_delta": metric_delta,
        "metric_delta_rows": metric_delta,
        "sample_comparison_count": len(sample_comparisons),
        "mismatch_count": len(mismatch_samples),
        "mismatch_samples": mismatch_samples,
        "mismatch_rows": mismatch_samples,
        "phase_9_readiness_criteria": list(PHASE_9_READINESS_CRITERIA),
        "report_paths": {},
    }
    if write_report:
        payload["report_paths"] = _write_report_files(
            payload=payload,
            output_root=output_root,
            report_path=report_path,
            experiment_key=base_experiment_key,
        )
    return payload


def run_legacy_vs_java_retrieval_compare_from_env(experiment: str) -> dict[str, Any]:
    try:
        from loaders.common import default_database_args
    except ModuleNotFoundError:  # pragma: no cover
        from pipeline.loaders.common import default_database_args

    defaults = default_database_args()
    return run_legacy_vs_java_retrieval_compare(
        experiment=experiment,
        database_url=defaults["database_url"],
        db_host=defaults["db_host"],
        db_port=defaults["db_port"],
        db_name=defaults["db_name"],
        db_user=defaults["db_user"],
        db_password=defaults["db_password"],
    )


def _run_default_comparison(
    *,
    base_raw: dict[str, Any],
    base_experiment_key: str,
    modes: list[str],
    output_root: Path,
    docs_root: Path,
    database_url: str | None,
    db_host: str,
    db_port: int,
    db_name: str,
    db_user: str,
    db_password: str,
    java_client: JavaRetrievalEvalClient | None,
    java_settings: JavaRetrievalEvalSettings | None,
) -> tuple[dict[str, Any], dict[str, Any]]:
    legacy_name = f"{base_experiment_key}__legacy_compare"
    java_name = f"{base_experiment_key}__java_compare"
    legacy_raw = _variant_raw(
        base_raw,
        experiment_key=legacy_name,
        modes=modes,
        use_java_backend=False,
    )
    java_raw = _variant_raw(
        base_raw,
        experiment_key=java_name,
        modes=modes,
        use_java_backend=True,
    )
    if java_settings is not None:
        java_raw.update(_java_settings_raw(java_settings))

    with tempfile.TemporaryDirectory(prefix="query_forge_eval_compare_") as temp_dir:
        variant_root = Path(temp_dir)
        _write_variant_config(variant_root, legacy_name, legacy_raw)
        _write_variant_config(variant_root, java_name, java_raw)
        common_kwargs = {
            "output_root": output_root,
            "docs_root": docs_root,
            "database_url": database_url,
            "db_host": db_host,
            "db_port": db_port,
            "db_name": db_name,
            "db_user": db_user,
            "db_password": db_password,
        }
        legacy_payload = run_retrieval_eval(
            experiment=legacy_name,
            experiment_root=variant_root,
            **common_kwargs,
        )
        java_payload = run_retrieval_eval(
            experiment=java_name,
            experiment_root=variant_root,
            java_client=java_client,
            java_settings=java_settings,
            **common_kwargs,
        )
    return legacy_payload, java_payload


def _variant_raw(
    source: dict[str, Any],
    *,
    experiment_key: str,
    modes: list[str],
    use_java_backend: bool,
) -> dict[str, Any]:
    raw = dict(source)
    raw["experiment_key"] = experiment_key
    raw["retrieval_modes"] = list(modes)
    raw["use_java_backend"] = use_java_backend
    if not use_java_backend:
        raw["java_backend_enabled"] = False
        raw["retrieval_eval_java_backend_enabled"] = False
        raw["java_retrieval_eval_enabled"] = False
    return raw


def _java_settings_raw(settings: JavaRetrievalEvalSettings) -> dict[str, Any]:
    payload = {
        "java_backend_base_url": settings.base_url,
        "java_backend_timeout_seconds": settings.timeout_seconds,
        "java_include_trace": settings.include_trace,
        "java_include_scores": settings.include_scores,
        "java_include_metadata": settings.include_metadata,
    }
    if settings.domain_id:
        payload["java_backend_domain_id"] = settings.domain_id
    if settings.forced_mode:
        payload["java_forced_mode"] = settings.forced_mode
    return payload


def _write_variant_config(root: Path, name: str, raw: dict[str, Any]) -> None:
    root.mkdir(parents=True, exist_ok=True)
    (root / f"{name}.yaml").write_text(
        yaml.safe_dump(raw, allow_unicode=True, sort_keys=False),
        encoding="utf-8",
    )


def _write_report_files(
    *,
    payload: dict[str, Any],
    output_root: Path,
    report_path: Path | None,
    experiment_key: str,
) -> dict[str, str]:
    safe_key = _safe_name(experiment_key)
    comparison_path = report_path or (output_root / f"retrieval_compare_{safe_key}.json")
    metric_delta_csv_path = output_root / f"retrieval_compare_metric_delta_{safe_key}.csv"
    mismatch_path = output_root / f"retrieval_compare_mismatches_{safe_key}.json"
    comparison_path.parent.mkdir(parents=True, exist_ok=True)
    metric_delta_csv_path.parent.mkdir(parents=True, exist_ok=True)
    mismatch_path.parent.mkdir(parents=True, exist_ok=True)

    report_payload = dict(payload)
    report_payload["report_paths"] = {
        "comparison_json": str(comparison_path),
        "metric_delta_csv": str(metric_delta_csv_path),
        "mismatch_json": str(mismatch_path),
    }
    comparison_path.write_text(
        json.dumps(report_payload, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    _write_csv(
        metric_delta_csv_path,
        payload["metric_delta"],
        ["mode", "metric", "legacy_value", "java_value", "delta"],
    )
    mismatch_path.write_text(
        json.dumps(payload["mismatch_samples"], ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    return report_payload["report_paths"]


def _extract_summary_rows(payload: dict[str, Any]) -> list[dict[str, Any]]:
    rows = payload.get("summary")
    if isinstance(rows, list):
        return [dict(row) for row in rows if isinstance(row, dict)]
    rows = payload.get("summary_rows")
    if isinstance(rows, list):
        return [dict(row) for row in rows if isinstance(row, dict)]
    return []


def _extract_sample_rows(payload: dict[str, Any], *, output_root: Path) -> list[dict[str, Any]]:
    for key in ("sample_rows", "rows", "samples"):
        rows = payload.get(key)
        if isinstance(rows, list):
            return [dict(row) for row in rows if isinstance(row, dict)]
    report_paths = payload.get("report_paths")
    if isinstance(report_paths, dict):
        for key in ("rewrite_cases_json", "rewrite_case_json", "rewrite_cases"):
            path_value = report_paths.get(key)
            if path_value:
                return _load_json_rows(Path(path_value))
    experiment_key = str(payload.get("experiment_key") or "").strip()
    if experiment_key:
        return _load_json_rows(output_root / f"rewrite_cases_{experiment_key}.json")
    return []


def _load_json_rows(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, list):
        return []
    return [dict(row) for row in payload if isinstance(row, dict)]


def _configured_modes(raw: dict[str, Any]) -> list[str]:
    value = raw.get("retrieval_modes")
    if isinstance(value, list):
        return [str(item).strip() for item in value if str(item).strip()]
    return []


def _summary_rows_by_mode(rows: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    return {
        str(row.get("mode") or "").strip(): row
        for row in rows
        if str(row.get("mode") or "").strip()
    }


def _sample_rows_by_key(rows: list[dict[str, Any]]) -> dict[tuple[str, str], dict[str, Any]]:
    by_key: dict[tuple[str, str], dict[str, Any]] = {}
    for row in rows:
        sample_id = str(row.get("sample_id") or row.get("sampleId") or "").strip()
        mode = normalize_forced_mode(row.get("mode"))
        if sample_id and mode:
            by_key[(sample_id, mode)] = row
    return by_key


def _retrieved_chunk_ids(row: dict[str, Any] | None) -> list[str]:
    if not row:
        return []
    for key in ("retrieved_chunk_ids", "retrievedChunkIds", "retrieved_ids"):
        value = row.get(key)
        if isinstance(value, list):
            return [str(item).strip() for item in value if str(item or "").strip()]
    for key in ("retrieved_top_k", "retrieval", "retrieved"):
        value = row.get(key)
        if isinstance(value, list):
            chunk_ids: list[str] = []
            for item in value:
                if isinstance(item, dict):
                    chunk_id = str(item.get("chunk_id") or item.get("chunkId") or "").strip()
                else:
                    chunk_id = str(item or "").strip()
                if chunk_id:
                    chunk_ids.append(chunk_id)
            if chunk_ids:
                return chunk_ids
    return []


def _expected_chunk_ids(*rows: dict[str, Any] | None) -> list[str]:
    for row in rows:
        if not row:
            continue
        for key in ("expected_chunk_ids", "relevant_chunk_ids", "expectedChunkIds"):
            value = row.get(key)
            if isinstance(value, list):
                return [str(item).strip() for item in value if str(item or "").strip()]
    return []


def _query_text(*rows: dict[str, Any] | None) -> str:
    for row in rows:
        if not row:
            continue
        for key in ("query", "raw_query", "user_query", "query_text"):
            value = row.get(key)
            if value is not None and str(value).strip():
                return str(value)
    return ""


def _sample_metrics(row: dict[str, Any] | None) -> dict[str, float]:
    if not row:
        return {}
    return {
        key: _float_or_zero(row.get(key))
        for key in COMPARISON_METRIC_KEYS
        if row.get(key) is not None
    }


def _sample_metric_delta(
    legacy_row: dict[str, Any] | None,
    java_row: dict[str, Any] | None,
) -> dict[str, float]:
    if not legacy_row or not java_row:
        return {}
    delta: dict[str, float] = {}
    for key in COMPARISON_METRIC_KEYS:
        if legacy_row.get(key) is None or java_row.get(key) is None:
            continue
        delta[key] = _float_or_zero(java_row.get(key)) - _float_or_zero(legacy_row.get(key))
    return delta


def _float_or_zero(value: Any) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return 0.0


def _write_csv(path: Path, rows: list[dict[str, Any]], fieldnames: list[str]) -> None:
    with path.open("w", encoding="utf-8", newline="") as target:
        writer = csv.DictWriter(target, fieldnames=fieldnames, extrasaction="ignore")
        writer.writeheader()
        for row in rows:
            writer.writerow(row)


def _safe_name(value: str) -> str:
    normalized = "".join(char if char.isalnum() or char in {"-", "_"} else "_" for char in value)
    return normalized.strip("_") or "comparison"


__all__ = [
    "COMPARISON_BLOCKED_MODES",
    "COMPARISON_METRIC_KEYS",
    "COMPARISON_REPORT_REQUIRED_KEYS",
    "COMPARISON_REPORT_SCHEMA_VERSION",
    "COMPARISON_SUPPORTED_MODES",
    "METRIC_DELTA_ROW_REQUIRED_KEYS",
    "MISMATCH_ROW_REQUIRED_KEYS",
    "PHASE_9_READINESS_CRITERIA",
    "RetrievalEvalComparisonError",
    "build_sample_comparison_report",
    "compute_metric_delta_report",
    "normalize_comparison_modes",
    "run_legacy_vs_java_retrieval_compare",
    "run_legacy_vs_java_retrieval_compare_from_env",
]
