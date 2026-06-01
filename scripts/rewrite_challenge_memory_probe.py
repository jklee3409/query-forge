from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[1]
if str(REPO_ROOT / "pipeline") not in sys.path:
    sys.path.insert(0, str(REPO_ROOT / "pipeline"))
if str(REPO_ROOT / "scripts") not in sys.path:
    sys.path.insert(0, str(REPO_ROOT / "scripts"))

from eval.runtime import (  # noqa: E402
    DbAnnRuntimeRetrievalAdapter,
    _rerank_rewrite_memory_candidates,
    _trusted_rewrite_memory_items,
    derive_eval_corpus_scope,
    load_eval_samples,
    retrieval_metrics,
)
from rewrite_challenge_retrieval_probe import (  # noqa: E402
    DATASETS,
    _connect,
    _fetch_chunk_hints,
    _fetch_synthetic_queries,
    _first_rank,
    _retriever_config,
    _sample_rows_by_id,
    _variant_queries,
)


DEFAULT_EXPERIMENT_KEYS = {
    ("spring_kr", "A"): "admin_eval_563e571c52fa",
    ("spring_kr", "C"): "admin_eval_679b4166cb68",
    ("spring_en", "A"): "admin_eval_563e571c52fa",
    ("spring_en", "C"): "admin_eval_679b4166cb68",
}


def _target_memory_hit(memory_items: list[dict[str, Any]], expected_chunks: set[str], expected_docs: set[str]) -> bool:
    for item in memory_items:
        target_chunks = {str(chunk_id) for chunk_id in item.get("target_chunk_ids") or [] if str(chunk_id).strip()}
        target_doc = str(item.get("target_doc_id") or "").strip()
        if target_chunks & expected_chunks:
            return True
        if expected_docs and target_doc in expected_docs:
            return True
    return False


def _score(args: argparse.Namespace) -> dict[str, Any]:
    spec = DATASETS[args.dataset]
    strategy = args.strategy.upper()
    snapshot = spec.snapshots[strategy]
    experiment_key = args.memory_experiment_key or DEFAULT_EXPERIMENT_KEYS.get((args.dataset, strategy))
    if not experiment_key:
        raise SystemExit(f"--memory-experiment-key is required for {args.dataset} strategy {strategy}")

    with _connect(args) as connection:
        samples = load_eval_samples(connection, dataset_id=spec.dataset_id, query_language=spec.language)
        rows_by_id = _sample_rows_by_id(spec.path)
        scope = derive_eval_corpus_scope(samples)
        adapter = DbAnnRuntimeRetrievalAdapter(
            connection,
            allowed_products=sorted(scope["product_filters"]),
            include_document_ids=sorted(scope["expected_doc_ids"]),
            memory_experiment_key=experiment_key,
            retriever_config=_retriever_config(),
        )
        chunk_hints = _fetch_chunk_hints(connection, samples)
        synthetic = {
            item_strategy: _fetch_synthetic_queries(connection, snapshot=item_snapshot, samples=samples)
            for item_strategy, item_snapshot in spec.snapshots.items()
        }
        variants_by_sample = {
            sample.sample_id: _variant_queries(
                sample=sample,
                row=rows_by_id.get(sample.sample_id),
                chunk_hints=chunk_hints,
                synthetic=synthetic,
            )
            for sample in samples
        }

        all_variants = sorted({variant for variants in variants_by_sample.values() for variant in variants})
        selected_variants = args.variants or all_variants
        summaries: dict[str, dict[str, Any]] = {}
        detail_rows: list[dict[str, Any]] = []

        for variant in selected_variants:
            raw_hit_count = 0
            raw_found10_count = 0
            trusted_target_count = 0
            memory_target_count = 0
            rawmiss_memory_target_count = 0
            rawmiss_trusted_target_count = 0
            rawmiss_found10_trusted_target_count = 0
            missing_variant_count = 0
            ranks: list[int] = []

            for sample in samples:
                query = variants_by_sample[sample.sample_id].get(variant, "")
                if not query:
                    missing_variant_count += 1
                    continue

                raw_retrieval = adapter.retrieve_top_k(query, top_k=10)
                metrics = retrieval_metrics(
                    expected_chunk_ids=sample.expected_chunk_ids,
                    expected_doc_ids=sample.expected_doc_ids,
                    retrieved=raw_retrieval,
                )
                raw_hit = int(metrics["hit@5"] > 0)
                rank = _first_rank(sample, raw_retrieval)
                raw_found10 = int(rank is not None)
                raw_hit_count += raw_hit
                raw_found10_count += raw_found10
                if rank is not None:
                    ranks.append(rank)

                memory_pool = adapter.memory_top_n(
                    query,
                    top_n=args.memory_pool_n,
                    preset_filter="full_gating",
                    source_gate_run_id=snapshot.source_gating_run_id,
                    strategy_filters=[strategy],
                )
                reranked_memory = _rerank_rewrite_memory_candidates(
                    raw_query=query,
                    memory_items=memory_pool,
                    raw_retrieval=raw_retrieval,
                    query_language=sample.query_language,
                    source_product=sample.source_product,
                    top_n=args.memory_top_n,
                )
                trusted_memory = _trusted_rewrite_memory_items(reranked_memory)

                expected_chunks = {str(chunk_id) for chunk_id in sample.expected_chunk_ids if str(chunk_id).strip()}
                expected_docs = {str(doc_id) for doc_id in sample.expected_doc_ids if str(doc_id).strip()}
                memory_target = _target_memory_hit(reranked_memory, expected_chunks, expected_docs)
                trusted_target = _target_memory_hit(trusted_memory, expected_chunks, expected_docs)

                memory_target_count += int(memory_target)
                trusted_target_count += int(trusted_target)
                if not raw_hit and memory_target:
                    rawmiss_memory_target_count += 1
                if not raw_hit and trusted_target:
                    rawmiss_trusted_target_count += 1
                if not raw_hit and raw_found10 and trusted_target:
                    rawmiss_found10_trusted_target_count += 1

                if args.include_rows:
                    detail_rows.append(
                        {
                            "sample_id": sample.sample_id,
                            "variant": variant,
                            "query": query,
                            "raw_hit@5": raw_hit,
                            "raw_first_rank": rank,
                            "raw_found@10": raw_found10,
                            "memory_target_in_reranked_top_n": memory_target,
                            "trusted_target_in_top_n": trusted_target,
                            "trusted_count": len(trusted_memory),
                            "top_memory_targets": [
                                {
                                    "target_doc_id": item.get("target_doc_id"),
                                    "target_chunk_ids": item.get("target_chunk_ids"),
                                    "query_text": item.get("query_text"),
                                    "target_title": item.get("target_title"),
                                }
                                for item in reranked_memory[:3]
                            ],
                        }
                    )

            summaries[variant] = {
                "raw_hit_count": raw_hit_count,
                "raw_found10_count": raw_found10_count,
                "missing_variant_count": missing_variant_count,
                "ranked_count": len(ranks),
                "mean_rank_when_found": sum(ranks) / len(ranks) if ranks else None,
                "memory_target_count": memory_target_count,
                "trusted_target_count": trusted_target_count,
                "rawmiss_memory_target_count": rawmiss_memory_target_count,
                "rawmiss_trusted_target_count": rawmiss_trusted_target_count,
                "rawmiss_found10_trusted_target_count": rawmiss_found10_trusted_target_count,
            }

    return {
        "dataset": args.dataset,
        "dataset_key": spec.dataset_key,
        "dataset_id": spec.dataset_id,
        "strategy": strategy,
        "memory_experiment_key": experiment_key,
        "source_gating_run_id": snapshot.source_gating_run_id,
        "sample_count": len(samples),
        "memory_pool_n": args.memory_pool_n,
        "memory_top_n": args.memory_top_n,
        "summaries": summaries,
        "rows": detail_rows,
    }


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Score rewrite challenge query variants against synthetic memory targets.")
    parser.add_argument("--dataset", required=True, choices=sorted(DATASETS))
    parser.add_argument("--strategy", required=True, choices=["A", "C", "a", "c"])
    parser.add_argument("--memory-experiment-key", default=None)
    parser.add_argument("--variants", nargs="*", default=None)
    parser.add_argument("--memory-pool-n", type=int, default=20)
    parser.add_argument("--memory-top-n", type=int, default=5)
    parser.add_argument("--include-rows", action="store_true")
    parser.add_argument("--quiet", action="store_true", help="Write output file without printing the full JSON payload.")
    parser.add_argument("--output", type=Path, default=None)
    parser.add_argument("--db-host", default="localhost")
    parser.add_argument("--db-port", type=int, default=5432)
    parser.add_argument("--db-name", default="query_forge")
    parser.add_argument("--db-user", default="query_forge")
    parser.add_argument("--db-password", default="query_forge")
    return parser.parse_args()


def main() -> None:
    args = _parse_args()
    result = _score(args)
    payload = json.dumps(result, ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(payload + "\n", encoding="utf-8")
    if not args.quiet:
        print(payload)


if __name__ == "__main__":
    main()
