from __future__ import annotations

import unittest

from pipeline.eval.answer_eval import _build_latency_summary


class AnswerEvalLatencyTests(unittest.TestCase):
    def test_build_latency_summary_computes_eval_average(self) -> None:
        rows = [
            {"query_eval_total_latency_ms": 1000},
            {"query_eval_total_latency_ms": 2000},
            {"query_eval_total_latency_ms": 3000},
        ]

        summary = _build_latency_summary(rows)

        self.assertEqual(summary["avg_query_eval_total_latency_ms"], 2000.0)
        self.assertEqual(summary["eval_sample_count"], 3)
        self.assertEqual(summary["excluded_sample_count"], 0)

    def test_build_latency_summary_excludes_skipped_or_missing_rewrite_samples(self) -> None:
        rows = [
            {
                "query_eval_total_latency_ms": 1000,
                "final_rewrite_latency_ms": None,
                "pure_rewrite_latency_ms": 400,
            },
            {
                "query_eval_total_latency_ms": 2000,
                "final_rewrite_latency_ms": 1500,
                "pure_rewrite_latency_ms": 300,
            },
            {
                "query_eval_total_latency_ms": None,
                "final_rewrite_latency_ms": -25,
                "pure_rewrite_latency_ms": None,
            },
        ]

        summary = _build_latency_summary(rows)

        self.assertEqual(summary["avg_query_eval_total_latency_ms"], 1500.0)
        self.assertEqual(summary["eval_sample_count"], 2)
        self.assertEqual(summary["avg_final_rewrite_latency_ms"], 1500.0)
        self.assertEqual(summary["rewrite_sample_count"], 1)
        self.assertEqual(summary["avg_pure_rewrite_latency_ms"], 350.0)
        self.assertEqual(summary["pure_rewrite_sample_count"], 2)
        self.assertEqual(summary["excluded_sample_count"], 1)


if __name__ == "__main__":
    unittest.main()
