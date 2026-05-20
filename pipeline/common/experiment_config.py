from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml

try:
    from common.local_retriever import RetrieverConfig, build_retriever_config
except ModuleNotFoundError:  # pragma: no cover
    from pipeline.common.local_retriever import RetrieverConfig, build_retriever_config


DEFAULT_QUERY_TYPE_DISTRIBUTION: dict[str, float] = {
    "definition": 0.19,
    "reason": 0.17,
    "procedure": 0.21,
    "comparison": 0.12,
    "short_user": 0.12,
    "code_mixed": 0.09,
    "follow_up": 0.09,
}

DEFAULT_ANSWERABILITY_DISTRIBUTION: dict[str, float] = {
    "single": 0.70,
    "near": 0.20,
    "far": 0.10,
}

DEFAULT_RETRIEVAL_UTILITY_WEIGHTS: dict[str, float] = {
    "target_top1": 1.00,
    "target_top3": 0.85,
    "target_top5": 0.70,
    "target_top10": 0.60,
    "same_doc_top3": 0.55,
    "same_doc_top5": 0.40,
    "outside_top5": 0.00,
    "multi_partial_bonus": 0.05,
    "multi_full_bonus": 0.12,
}

DEFAULT_GATING_WEIGHTS: dict[str, float] = {
    "utility": 0.50,
    "llm": 0.35,
    "novelty": 0.15,
}

DEFAULT_REWRITE_ADOPTION_POLICY: dict[str, Any] = {
    "weights": {
        "retrieval_gain": 0.60,
        "terminology_preservation": 0.28,
        "memory_alignment": 0.12,
    },
    "thresholds": {
        "min_improvement": 0.03,
        "preservation_floor": 0.72,
        "max_length_ratio": 1.85,
        "low_memory_similarity_cutoff": 0.45,
        "low_memory_extra_threshold": 0.02,
        "min_retrieval_gain_score": 0.0,
        "underspecified_memory_norm_cutoff": 0.72,
    },
    "penalties": {
        "verbosity_per_extra_ratio": 0.08,
        "critical_token_drop": 0.22,
        "anchor_overlap_drop": 0.12,
        "memory_target_missing": 0.10,
    },
    "bonuses": {
        "memory_target_presence": 0.06,
    },
    "shift_bonus_weight": 0.03,
    "category_overrides": {
        "short_user": {
            "thresholds": {
                "min_improvement": 0.02,
                "preservation_floor": 0.68,
                "max_length_ratio": 2.10,
                "underspecified_memory_norm_cutoff": 0.66,
            },
            "penalties": {
                "verbosity_per_extra_ratio": 0.035,
                "memory_target_missing": 0.06,
            },
            "bonuses": {
                "memory_target_presence": 0.10,
            },
        },
        "code_mixed": {
            "thresholds": {
                "min_improvement": 0.05,
                "preservation_floor": 0.86,
                "max_length_ratio": 1.60,
            },
            "weights": {
                "retrieval_gain": 0.56,
                "terminology_preservation": 0.34,
                "memory_alignment": 0.10,
            },
        },
        "troubleshooting": {
            "thresholds": {
                "min_improvement": 0.03,
                "max_length_ratio": 2.20,
            }
        },
    },
}


@dataclass(slots=True)
class ExperimentConfig:
    experiment_key: str
    category: str
    description: str
    generation_strategy: str
    enable_code_mixed: bool
    enable_rule_filter: bool
    enable_llm_self_eval: bool
    enable_retrieval_utility: bool
    enable_diversity: bool
    enable_anti_copy: bool
    memory_top_n: int
    rewrite_candidate_count: int
    rewrite_threshold: float
    retrieval_top_k: int
    rerank_top_n: int
    use_session_context: bool
    avg_queries_per_chunk: float
    query_type_distribution: dict[str, float]
    answerability_distribution: dict[str, float]
    gating_preset: str
    retrieval_utility_weights: dict[str, float]
    gating_weights: dict[str, float]
    final_score_threshold: float
    utility_threshold: float
    random_seed: int
    diversity_threshold_same_chunk: float
    diversity_threshold_same_doc: float
    limit_chunks: int | None
    retriever_config: RetrieverConfig
    rewrite_adoption_policy: dict[str, Any]
    config_path: Path
    raw: dict[str, Any]

    @property
    def config_hash(self) -> str:
        payload = json.dumps(self.raw, ensure_ascii=False, sort_keys=True)
        return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def _normalized_distribution(
    candidate: dict[str, float] | None,
    default: dict[str, float],
) -> dict[str, float]:
    values = dict(default)
    if candidate:
        for key, value in candidate.items():
            values[key] = float(value)
    total = sum(max(v, 0.0) for v in values.values())
    if total <= 0:
        return dict(default)
    return {key: max(value, 0.0) / total for key, value in values.items()}


def _derive_gating_preset(config: dict[str, Any]) -> str:
    if str(config.get("gating_preset") or "").strip():
        return str(config["gating_preset"])
    rule = bool(config.get("enable_rule_filter", False))
    llm = bool(config.get("enable_llm_self_eval", False))
    utility = bool(config.get("enable_retrieval_utility", False))
    diversity = bool(config.get("enable_diversity", False))
    if not any((rule, llm, utility, diversity)):
        return "ungated"
    if rule and not any((llm, utility, diversity)):
        return "rule_only"
    if rule and llm and not any((utility, diversity)):
        return "rule_plus_llm"
    return "full_gating"


def _deep_merge_dict(base: dict[str, Any], override: dict[str, Any]) -> dict[str, Any]:
    merged: dict[str, Any] = {}
    for key, value in base.items():
        if isinstance(value, dict):
            merged[key] = _deep_merge_dict(value, {})
        elif isinstance(value, list):
            merged[key] = list(value)
        else:
            merged[key] = value
    for key, value in override.items():
        if isinstance(value, dict) and isinstance(merged.get(key), dict):
            merged[key] = _deep_merge_dict(dict(merged[key]), value)
        elif isinstance(value, list):
            merged[key] = list(value)
        else:
            merged[key] = value
    return merged


def _float_value(value: Any, default: float) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return float(default)


def _clamp_float(value: Any, *, default: float, min_value: float, max_value: float) -> float:
    parsed = _float_value(value, default)
    return max(min_value, min(max_value, parsed))


def resolve_rewrite_adoption_policy(raw_config: dict[str, Any] | None) -> dict[str, Any]:
    source = raw_config or {}
    override = source.get("rewrite_adoption_policy")
    merged = _deep_merge_dict(
        DEFAULT_REWRITE_ADOPTION_POLICY,
        override if isinstance(override, dict) else {},
    )

    thresholds = merged.get("thresholds")
    if isinstance(thresholds, dict):
        thresholds["min_improvement"] = _clamp_float(
            thresholds.get("min_improvement"),
            default=0.03,
            min_value=0.0,
            max_value=0.5,
        )
        thresholds["preservation_floor"] = _clamp_float(
            thresholds.get("preservation_floor"),
            default=0.72,
            min_value=0.0,
            max_value=1.0,
        )
        thresholds["max_length_ratio"] = _clamp_float(
            thresholds.get("max_length_ratio"),
            default=1.85,
            min_value=1.0,
            max_value=4.0,
        )
        thresholds["low_memory_similarity_cutoff"] = _clamp_float(
            thresholds.get("low_memory_similarity_cutoff"),
            default=0.45,
            min_value=0.0,
            max_value=1.0,
        )
        thresholds["low_memory_extra_threshold"] = _clamp_float(
            thresholds.get("low_memory_extra_threshold"),
            default=0.02,
            min_value=0.0,
            max_value=0.5,
        )
        thresholds["min_retrieval_gain_score"] = _clamp_float(
            thresholds.get("min_retrieval_gain_score"),
            default=0.0,
            min_value=0.0,
            max_value=1.0,
        )
        thresholds["underspecified_memory_norm_cutoff"] = _clamp_float(
            thresholds.get("underspecified_memory_norm_cutoff"),
            default=0.72,
            min_value=0.0,
            max_value=1.0,
        )

    penalties = merged.get("penalties")
    if isinstance(penalties, dict):
        penalties["verbosity_per_extra_ratio"] = _clamp_float(
            penalties.get("verbosity_per_extra_ratio"),
            default=0.08,
            min_value=0.0,
            max_value=0.8,
        )
        penalties["critical_token_drop"] = _clamp_float(
            penalties.get("critical_token_drop"),
            default=0.22,
            min_value=0.0,
            max_value=1.0,
        )
        penalties["anchor_overlap_drop"] = _clamp_float(
            penalties.get("anchor_overlap_drop"),
            default=0.12,
            min_value=0.0,
            max_value=1.0,
        )
        penalties["memory_target_missing"] = _clamp_float(
            penalties.get("memory_target_missing"),
            default=0.10,
            min_value=0.0,
            max_value=1.0,
        )

    weights = merged.get("weights")
    if isinstance(weights, dict):
        weights["retrieval_gain"] = _clamp_float(
            weights.get("retrieval_gain"),
            default=0.60,
            min_value=0.0,
            max_value=1.0,
        )
        weights["terminology_preservation"] = _clamp_float(
            weights.get("terminology_preservation"),
            default=0.28,
            min_value=0.0,
            max_value=1.0,
        )
        weights["memory_alignment"] = _clamp_float(
            weights.get("memory_alignment"),
            default=0.12,
            min_value=0.0,
            max_value=1.0,
        )

    bonuses = merged.get("bonuses")
    if isinstance(bonuses, dict):
        bonuses["memory_target_presence"] = _clamp_float(
            bonuses.get("memory_target_presence"),
            default=0.06,
            min_value=0.0,
            max_value=0.5,
        )

    merged["shift_bonus_weight"] = _clamp_float(
        merged.get("shift_bonus_weight"),
        default=0.03,
        min_value=0.0,
        max_value=0.5,
    )

    category_overrides = merged.get("category_overrides")
    if isinstance(category_overrides, dict):
        sanitized_overrides: dict[str, Any] = {}
        for key, value in category_overrides.items():
            if not isinstance(key, str) or not isinstance(value, dict):
                continue
            sanitized_overrides[key.strip().lower()] = value
        merged["category_overrides"] = sanitized_overrides

    return merged


def load_experiment_config(
    experiment: str,
    *,
    experiment_root: Path = Path("configs/experiments"),
) -> ExperimentConfig:
    config_path = experiment_root / f"{experiment}.yaml"
    if not config_path.exists():
        raise FileNotFoundError(f"Experiment config was not found: {config_path}")

    with config_path.open("r", encoding="utf-8") as source:
        raw: dict[str, Any] = yaml.safe_load(source) or {}

    distribution = _normalized_distribution(
        raw.get("query_type_distribution"), DEFAULT_QUERY_TYPE_DISTRIBUTION
    )
    answerability_distribution = _normalized_distribution(
        raw.get("answerability_distribution"), DEFAULT_ANSWERABILITY_DISTRIBUTION
    )
    utility_weights = dict(DEFAULT_RETRIEVAL_UTILITY_WEIGHTS)
    utility_weights.update(raw.get("retrieval_utility_weights") or {})
    gating_weights = dict(DEFAULT_GATING_WEIGHTS)
    gating_weights.update(raw.get("gating_weights") or {})
    rewrite_adoption_policy = resolve_rewrite_adoption_policy(raw)

    return ExperimentConfig(
        experiment_key=str(raw.get("experiment_key") or experiment),
        category=str(raw.get("category") or "scaffold"),
        description=str(raw.get("description") or ""),
        generation_strategy=str(raw.get("generation_strategy") or "C").upper(),
        enable_code_mixed=bool(raw.get("enable_code_mixed", False)),
        enable_rule_filter=bool(raw.get("enable_rule_filter", False)),
        enable_llm_self_eval=bool(raw.get("enable_llm_self_eval", False)),
        enable_retrieval_utility=bool(raw.get("enable_retrieval_utility", False)),
        enable_diversity=bool(raw.get("enable_diversity", False)),
        enable_anti_copy=bool(raw.get("enable_anti_copy", False)),
        memory_top_n=int(raw.get("memory_top_n", 5)),
        rewrite_candidate_count=int(raw.get("rewrite_candidate_count", 3)),
        rewrite_threshold=float(raw.get("rewrite_threshold", 0.05)),
        retrieval_top_k=int(raw.get("retrieval_top_k", 20)),
        rerank_top_n=int(raw.get("rerank_top_n", 5)),
        use_session_context=bool(raw.get("use_session_context", False)),
        avg_queries_per_chunk=float(raw.get("avg_queries_per_chunk", 4.2)),
        query_type_distribution=distribution,
        answerability_distribution=answerability_distribution,
        gating_preset=_derive_gating_preset(raw),
        retrieval_utility_weights=utility_weights,
        gating_weights=gating_weights,
        final_score_threshold=float(raw.get("final_score_threshold", 0.75)),
        utility_threshold=float(raw.get("utility_threshold", 0.70)),
        random_seed=int(raw.get("random_seed", 13)),
        diversity_threshold_same_chunk=float(
            raw.get("diversity_threshold_same_chunk", 0.93)
        ),
        diversity_threshold_same_doc=float(raw.get("diversity_threshold_same_doc", 0.96)),
        limit_chunks=int(raw["limit_chunks"]) if raw.get("limit_chunks") else None,
        retriever_config=build_retriever_config(raw),
        rewrite_adoption_policy=rewrite_adoption_policy,
        config_path=config_path,
        raw=raw,
    )
