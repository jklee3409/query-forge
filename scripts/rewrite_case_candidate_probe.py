from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

import psycopg
from psycopg.rows import dict_row

REPO_ROOT = Path(__file__).resolve().parents[1]
if str(REPO_ROOT / "pipeline") not in sys.path:
    sys.path.insert(0, str(REPO_ROOT / "pipeline"))

from common.local_retriever import build_retriever_config  # noqa: E402
from eval.runtime import (  # noqa: E402
    DbAnnRuntimeRetrievalAdapter,
    derive_eval_corpus_scope,
    load_eval_samples,
    retrieval_metrics,
)


def _retriever_config() -> Any:
    return build_retriever_config(
        {
            "retriever_mode": "hybrid",
            "dense_embedding_model": "intfloat/multilingual-e5-small",
            "dense_embedding_required": True,
            "dense_fallback_enabled": False,
            "dense_embedding_device": "cpu",
            "dense_embedding_batch_size": 32,
            "rerank_enabled": False,
            "retriever_candidate_pool_k": 50,
            "candidate_pool_k": 50,
            "retriever_fusion_weights": {"dense": 0.6, "bm25": 0.32, "technical": 0.08},
        }
    )


def _connect(args: argparse.Namespace) -> psycopg.Connection[Any]:
    return psycopg.connect(
        host=args.db_host,
        port=args.db_port,
        dbname=args.db_name,
        user=args.db_user,
        password=args.db_password,
        row_factory=dict_row,
        autocommit=False,
    )


def run(args: argparse.Namespace) -> dict[str, Any]:
    report = json.loads(Path(args.rewrite_cases).read_text(encoding="utf-8"))
    with _connect(args) as connection:
        samples = load_eval_samples(connection, dataset_id=args.dataset_id, query_language=args.query_language)
        sample_by_id = {sample.sample_id: sample for sample in samples}
        scope = derive_eval_corpus_scope(samples)
        adapter = DbAnnRuntimeRetrievalAdapter(
            connection,
            allowed_products=sorted(scope["product_filters"]),
            include_document_ids=sorted(scope["expected_doc_ids"]),
            memory_experiment_key=None,
            retriever_config=_retriever_config(),
        )

        rows: list[dict[str, Any]] = []
        for case in report:
            if case.get("mode") != args.mode:
                continue
            sample = sample_by_id.get(str(case.get("sample_id") or ""))
            if sample is None:
                continue
            for candidate in case.get("rewrite_candidates") or []:
                query = str(candidate.get("query") or "").strip()
                if not query:
                    continue
                retrieved = adapter.retrieve_top_k(query, top_k=5)
                metrics = retrieval_metrics(
                    expected_chunk_ids=sample.expected_chunk_ids,
                    expected_doc_ids=sample.expected_doc_ids,
                    retrieved=retrieved,
                )
                rows.append(
                    {
                        "sample_id": sample.sample_id,
                        "raw_hit@5": int((case.get("raw_metrics") or {}).get("hit@5") or 0),
                        "final_hit@5": int((case.get("final_metrics") or {}).get("hit@5") or 0),
                        "rewrite_applied": bool(case.get("rewrite_applied")),
                        "selected_label": (case.get("selected_rewrite") or {}).get("label"),
                        "candidate_label": candidate.get("label"),
                        "candidate_query": query,
                        "candidate_hit@5": int(metrics["hit@5"] > 0),
                        "candidate_recall@5": metrics["recall@5"],
                        "candidate_mrr@10": metrics["mrr@10"],
                        "candidate_ndcg@10": metrics["ndcg@10"],
                        "candidate_eligible": bool(candidate.get("eligible")),
                        "candidate_rejection_reason": candidate.get("rejection_reason"),
                        "source_memory_target_chunk_hit": bool(candidate.get("source_memory_target_chunk_hit")),
                        "source_memory_target_doc_hit": bool(candidate.get("source_memory_target_doc_hit")),
                    }
                )

    raw_miss_rows = [row for row in rows if row["raw_hit@5"] == 0]
    by_label: dict[str, dict[str, int]] = {}
    for row in rows:
        label = str(row.get("candidate_label") or "")
        stats = by_label.setdefault(label, {"rows": 0, "hit": 0, "rawmiss_hit": 0, "eligible_hit": 0})
        stats["rows"] += 1
        stats["hit"] += int(row["candidate_hit@5"] == 1)
        stats["rawmiss_hit"] += int(row["raw_hit@5"] == 0 and row["candidate_hit@5"] == 1)
        stats["eligible_hit"] += int(row["candidate_eligible"] and row["candidate_hit@5"] == 1)

    return {
        "rewrite_cases": str(args.rewrite_cases),
        "dataset_id": args.dataset_id,
        "mode": args.mode,
        "candidate_rows": len(rows),
        "rawmiss_candidate_rows": len(raw_miss_rows),
        "rawmiss_any_candidate_hit_sample_count": len(
            {
                row["sample_id"]
                for row in raw_miss_rows
                if row["candidate_hit@5"] == 1
            }
        ),
        "by_label": by_label,
        "rows": rows if args.include_rows else [],
    }


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Score rewrite candidates in a rewrite_cases report against eval targets.")
    parser.add_argument("--rewrite-cases", required=True)
    parser.add_argument("--dataset-id", required=True)
    parser.add_argument("--query-language", default="ko")
    parser.add_argument("--mode", default="selective_rewrite")
    parser.add_argument("--include-rows", action="store_true")
    parser.add_argument("--output", type=Path, default=None)
    parser.add_argument("--quiet", action="store_true")
    parser.add_argument("--db-host", default="localhost")
    parser.add_argument("--db-port", type=int, default=5432)
    parser.add_argument("--db-name", default="query_forge")
    parser.add_argument("--db-user", default="query_forge")
    parser.add_argument("--db-password", default="query_forge")
    return parser


def main() -> int:
    args = build_parser().parse_args()
    result = run(args)
    payload = json.dumps(result, ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(payload + "\n", encoding="utf-8")
    if not args.quiet:
        print(payload)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
