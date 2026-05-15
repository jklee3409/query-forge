from __future__ import annotations

import unittest
from pathlib import Path

from pipeline.eval.llm_stability_runner import _strategy_specs
from pipeline.eval.llm_stability_runner import _style_match


class LlmStabilityRunnerStrategyTests(unittest.TestCase):
    def test_strategy_specs_include_e_f_g(self) -> None:
        specs = _strategy_specs(Path("configs/prompts"))
        self.assertTrue(all(strategy in specs for strategy in ("A", "B", "C", "D", "E", "F", "G")))
        self.assertEqual(
            specs["B"].required_keys,
            ("query_ko", "query_type", "answerability_type"),
        )
        self.assertEqual(
            specs["E"].required_keys,
            ("query_en", "query_type", "answerability_type", "style_note"),
        )
        self.assertEqual(
            specs["F"].required_keys,
            ("query_ko", "query_en", "query_type", "answerability_type", "style_note"),
        )
        self.assertEqual(
            specs["G"].required_keys,
            ("query_ko", "query_type", "answerability_type", "style_note"),
        )

    def test_code_mixed_style_for_e_accepts_english_query(self) -> None:
        self.assertTrue(_style_match("E", "code_mixed", "Spring Security filter order troubleshooting"))
        self.assertFalse(_style_match("E", "code_mixed", "설정 방법 알려줘"))


if __name__ == "__main__":
    unittest.main()
