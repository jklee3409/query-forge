from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml


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
        config_path=config_path,
        raw=raw,
    )

