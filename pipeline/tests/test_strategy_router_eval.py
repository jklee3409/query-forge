from __future__ import annotations

import unittest

from pipeline.eval import retrieval_eval, runtime
from pipeline.eval.runtime import EvalSample, RetrievalCandidate


class StrategyRouterDecisionTests(unittest.TestCase):
    def test_agentic_is_disabled_by_default(self) -> None:
        decision = runtime.route_strategy_router(
            raw_query="배포 환경에서 연결 오류 원인과 확인해야 할 설정 차이를 비교",
            raw_config={},
            memory_candidates_available=True,
            anchor_injection_enabled=True,
            query_language="ko",
        )

        self.assertEqual(decision.selected_strategy, "selective_rewrite")
        self.assertEqual(decision.router_reason, "rewrite_backed_mode_ready")

    def test_agentic_requires_feature_flag_and_complex_query(self) -> None:
        decision = runtime.route_strategy_router(
            raw_query="Spring Boot 배포 환경에서 PostgreSQL 연결 오류 원인과 확인해야 할 설정 차이를 비교",
            raw_config={"strategy_router_agentic_enabled": True, "strategy_router_agentic_min_tokens": 8},
            memory_candidates_available=True,
            anchor_injection_enabled=True,
            query_language="ko",
        )

        self.assertEqual(decision.selected_strategy, "agentic_multi_query")
        self.assertTrue(decision.router_reason.startswith("agentic_complex_query:"))

    def test_anchor_injection_prefers_anchor_strategy_for_technical_query(self) -> None:
        decision = runtime.route_strategy_router(
            raw_query="@Transactional rollbackFor 설정 확인",
            raw_config={},
            memory_candidates_available=True,
            anchor_injection_enabled=True,
            query_language="ko",
        )

        self.assertEqual(decision.selected_strategy, "anchor_aware_rewrite")
        self.assertEqual(decision.router_reason, "anchor_injection_enabled_and_technical_anchor_detected")

    def test_missing_memory_candidates_falls_back_to_raw(self) -> None:
        decision = runtime.route_strategy_router(
            raw_query="@Transactional rollbackFor 설정 확인",
            raw_config={},
            memory_candidates_known=True,
            memory_candidates_available=False,
            anchor_injection_enabled=True,
            query_language="ko",
        )

        self.assertEqual(decision.selected_strategy, "raw_only")
        self.assertEqual(decision.router_reason, "memory_candidates_unavailable")


class RetrievalEvalStrategyRouterModeTests(unittest.TestCase):
    def test_strategy_router_mode_records_raw_fallback_trace(self) -> None:
        class Config:
            raw = {}

        sample = EvalSample(
            sample_id="sample-1",
            split="test",
            query_text="@Transactional rollbackFor 설정 확인",
            query_language="ko",
            dialog_context={},
            expected_doc_ids=["doc-1"],
            expected_chunk_ids=["chunk-1"],
            expected_answer_key_points=[],
            query_category="short_user",
            single_or_multi_chunk=None,
        )
        raw_retrieval = [
            RetrievalCandidate(
                chunk_id="chunk-1",
                document_id="doc-1",
                score=1.0,
                text="rollbackFor reference",
            )
        ]

        metrics, rewrite_info, retrieval = retrieval_eval._evaluate_mode(
            mode="strategy_router",
            sample=sample,
            chunks=[],
            memories=[],
            config=Config(),
            memory_strategy_filters=[],
            source_gating_run_id=None,
            comparison_source_runs={},
            retrieval_adapter=None,
            multi_source_anchor_index=None,
            raw_retrieval=raw_retrieval,
        )
        rewrite_info = retrieval_eval._with_llm_trace_defaults("strategy_router", rewrite_info)

        self.assertEqual(metrics["hit@5"], 1.0)
        self.assertEqual(retrieval, raw_retrieval)
        self.assertEqual(rewrite_info["selected_strategy"], "raw_only")
        self.assertEqual(rewrite_info["router_reason"], "memory_candidates_unavailable")
        self.assertEqual(rewrite_info["llm_call_count"], 0)
        self.assertEqual(rewrite_info["planner_call_count"], 0)
        self.assertEqual(rewrite_info["rewrite_call_count"], 0)


if __name__ == "__main__":
    unittest.main()
