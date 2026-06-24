from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from pipeline.eval.java_retrieval_client import JavaRetrievalClientError
from pipeline.eval.retrieval_eval_compare import (
    COMPARISON_SUPPORTED_MODES,
    RetrievalEvalComparisonError,
    build_sample_comparison_report,
    compute_metric_delta_report,
    normalize_comparison_modes,
    run_legacy_vs_java_retrieval_compare,
)


class _FakeJavaClient:
    def __init__(self) -> None:
        self.calls: list[dict] = []

    def retrieve(self, **kwargs):
        self.calls.append(kwargs)
        return {
            "retrievedChunkIds": ["chunk-1"],
        }


class RetrievalEvalCompareTests(unittest.TestCase):
    def test_comparison_uses_only_supported_non_agentic_modes(self) -> None:
        modes = normalize_comparison_modes(
            ["raw-only", "selective_rewrite", "anchor_aware_rewrite", "strategy_router"]
        )

        self.assertEqual(modes, list(COMPARISON_SUPPORTED_MODES))

    def test_agentic_mode_in_comparison_config_fails_fast(self) -> None:
        with self.assertRaises(RetrievalEvalComparisonError) as context:
            normalize_comparison_modes(["raw_only", "agentic_multi_query"])

        self.assertEqual(context.exception.code, "unsupported_agentic_eval")
        self.assertIn("agentic_multi_query", context.exception.detail)

    def test_legacy_and_java_rows_are_joined_by_sample_and_mode(self) -> None:
        rows = build_sample_comparison_report(
            legacy_rows=[
                _sample_row(sample_id="sample-1", mode="raw_only", retrieved=["chunk-1"]),
                _sample_row(sample_id="sample-2", mode="raw_only", retrieved=["chunk-2"]),
            ],
            java_rows=[
                _sample_row(sample_id="sample-2", mode="raw_only", retrieved=["chunk-2"]),
                _sample_row(sample_id="sample-1", mode="raw_only", retrieved=["chunk-1"]),
            ],
        )

        self.assertEqual([(row["sample_id"], row["mode"]) for row in rows], [
            ("sample-1", "raw_only"),
            ("sample-2", "raw_only"),
        ])

    def test_metric_delta_is_computed_correctly(self) -> None:
        rows = compute_metric_delta_report(
            legacy_summary_rows=[_summary_row("raw_only", recall=0.25, hit=1.0, mrr=0.5, ndcg=0.6)],
            java_summary_rows=[_summary_row("raw_only", recall=0.75, hit=1.0, mrr=0.25, ndcg=0.8)],
            modes=["raw_only"],
        )
        by_metric = {row["metric"]: row for row in rows}

        self.assertEqual(by_metric["recall@5"]["delta"], 0.5)
        self.assertEqual(by_metric["hit@5"]["delta"], 0.0)
        self.assertEqual(by_metric["mrr@10"]["delta"], -0.25)
        self.assertAlmostEqual(by_metric["ndcg@10"]["delta"], 0.2)

    def test_mismatch_report_detects_exact_match(self) -> None:
        rows = build_sample_comparison_report(
            legacy_rows=[_sample_row(sample_id="sample-1", mode="raw_only", retrieved=["chunk-1", "chunk-2"])],
            java_rows=[_sample_row(sample_id="sample-1", mode="raw_only", retrieved=["chunk-1", "chunk-2"])],
        )

        self.assertTrue(rows[0]["exact_match"])
        self.assertEqual(rows[0]["overlap_count"], 2)
        self.assertEqual(rows[0]["notes"], [])

    def test_mismatch_report_detects_different_order_and_ids(self) -> None:
        rows = build_sample_comparison_report(
            legacy_rows=[
                _sample_row(sample_id="sample-order", mode="raw_only", retrieved=["chunk-1", "chunk-2"]),
                _sample_row(sample_id="sample-ids", mode="raw_only", retrieved=["chunk-1", "chunk-2"]),
            ],
            java_rows=[
                _sample_row(sample_id="sample-order", mode="raw_only", retrieved=["chunk-2", "chunk-1"]),
                _sample_row(sample_id="sample-ids", mode="raw_only", retrieved=["chunk-1", "chunk-3"]),
            ],
        )
        by_sample = {row["sample_id"]: row for row in rows}

        self.assertFalse(by_sample["sample-order"]["exact_match"])
        self.assertIn("different_order", by_sample["sample-order"]["notes"])
        self.assertFalse(by_sample["sample-ids"]["exact_match"])
        self.assertIn("different_ids", by_sample["sample-ids"]["notes"])

    def test_comparison_report_does_not_include_full_content(self) -> None:
        rows = build_sample_comparison_report(
            legacy_rows=[
                {
                    **_sample_row(sample_id="sample-1", mode="raw_only", retrieved=["chunk-1"]),
                    "content": "full legacy content must not be copied",
                    "retrieved_docs": [{"chunkId": "chunk-1", "contentPreview": "preview"}],
                }
            ],
            java_rows=[
                {
                    **_sample_row(sample_id="sample-1", mode="raw_only", retrieved=["chunk-1"]),
                    "chunk_text": "full java content must not be copied",
                }
            ],
        )
        encoded = json.dumps(rows, ensure_ascii=False)

        self.assertNotIn("full legacy content", encoded)
        self.assertNotIn("full java content", encoded)
        self.assertNotIn("contentPreview", encoded)

    def test_java_client_error_fails_fast(self) -> None:
        def legacy_runner(**_kwargs):
            return _payload()

        def java_runner(**_kwargs):
            raise JavaRetrievalClientError(
                code="java_backend_unavailable",
                detail="connection refused",
            )

        with self.assertRaises(JavaRetrievalClientError) as context:
            run_legacy_vs_java_retrieval_compare(
                experiment="unit",
                modes=["raw_only"],
                legacy_runner=legacy_runner,
                java_runner=java_runner,
                write_report=False,
            )

        self.assertEqual(context.exception.code, "java_backend_unavailable")

    def test_comparison_runner_uses_fake_java_client_without_real_server(self) -> None:
        fake_client = _FakeJavaClient()

        def legacy_runner(**kwargs):
            self.assertEqual(kwargs["backend"], "python_legacy")
            return _payload(
                rows=[_sample_row(sample_id="sample-1", mode="raw_only", retrieved=["chunk-1"])],
            )

        def java_runner(**kwargs):
            self.assertEqual(kwargs["backend"], "java")
            kwargs["java_client"].retrieve(query="FilterChainProxy order")
            return _payload(
                rows=[_sample_row(sample_id="sample-1", mode="raw_only", retrieved=["chunk-1"])],
            )

        with tempfile.TemporaryDirectory() as temp_dir:
            report = run_legacy_vs_java_retrieval_compare(
                experiment="unit",
                output_root=Path(temp_dir),
                modes=["raw_only"],
                java_client=fake_client,
                legacy_runner=legacy_runner,
                java_runner=java_runner,
            )

        self.assertEqual(len(fake_client.calls), 1)
        self.assertEqual(report["mismatch_count"], 0)
        self.assertEqual(report["metric_delta"][0]["mode"], "raw_only")
        self.assertFalse(report["official_eval_switched"])


def _payload(rows: list[dict] | None = None) -> dict:
    return {
        "experiment_key": "unit",
        "summary": [
            _summary_row("raw_only", recall=1.0, hit=1.0, mrr=1.0, ndcg=1.0),
        ],
        "sample_rows": rows or [
            _sample_row(sample_id="sample-1", mode="raw_only", retrieved=["chunk-1"]),
        ],
    }


def _summary_row(mode: str, *, recall: float, hit: float, mrr: float, ndcg: float) -> dict:
    return {
        "mode": mode,
        "recall@5": recall,
        "hit@5": hit,
        "mrr@10": mrr,
        "ndcg@10": ndcg,
    }


def _sample_row(*, sample_id: str, mode: str, retrieved: list[str]) -> dict:
    return {
        "sample_id": sample_id,
        "query": "FilterChainProxy order",
        "mode": mode,
        "expected_chunk_ids": ["chunk-1"],
        "retrieved_chunk_ids": retrieved,
        "recall@5": 1.0 if "chunk-1" in retrieved[:5] else 0.0,
        "hit@5": 1.0 if "chunk-1" in retrieved[:5] else 0.0,
        "mrr@10": 1.0 if retrieved and retrieved[0] == "chunk-1" else 0.5,
        "ndcg@10": 1.0 if retrieved and retrieved[0] == "chunk-1" else 0.6309,
    }


if __name__ == "__main__":
    unittest.main()
