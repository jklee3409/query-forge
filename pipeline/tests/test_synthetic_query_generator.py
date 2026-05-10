from __future__ import annotations

import unittest

from pipeline.common.llm_client import _validate_json_schema
from pipeline.generation.synthetic_query_generator import _extract_query_text
from pipeline.generation.synthetic_query_generator import _query_response_schema_for_strategy


class SyntheticQueryGeneratorSchemaTests(unittest.TestCase):
    def test_strategy_required_fields(self) -> None:
        expected_required = {
            "A": ("query_en", "query_ko"),
            "B": ("query_ko",),
            "C": ("query_ko",),
            "D": ("query_ko", "query_code_mixed"),
            "E": ("query_en",),
            "F": ("query_ko", "query_en"),
            "G": ("query_ko",),
        }
        for strategy, required in expected_required.items():
            schema = _query_response_schema_for_strategy(strategy)
            self.assertEqual(tuple(schema.get("required") or ()), required)
            self.assertTrue(bool(schema.get("additionalProperties")))

    def test_e_schema_accepts_query_en_only(self) -> None:
        schema = _query_response_schema_for_strategy("E")
        errors = _validate_json_schema({"query_en": "How to configure Spring Security filter chain?"}, schema, path="$")
        self.assertEqual(errors, [])

    def test_f_schema_and_extraction_prefers_query_en(self) -> None:
        payload = {
            "query_ko": "Spring Security filter chain ?ㅼ젙 諛⑸쾿",
            "query_en": "How to configure Spring Security filter chain?",
        }
        schema = _query_response_schema_for_strategy("F")
        errors = _validate_json_schema(payload, schema, path="$")
        self.assertEqual(errors, [])

        query_text, trace = _extract_query_text(
            generation_strategy="F",
            query_type="procedure",
            response=payload,
        )
        self.assertEqual(query_text, payload["query_en"])
        self.assertEqual(trace.get("query_en"), payload["query_en"])
        self.assertEqual(trace.get("query_ko"), payload["query_ko"])

    def test_g_schema_accepts_query_ko(self) -> None:
        schema = _query_response_schema_for_strategy("G")
        errors = _validate_json_schema({"query_ko": "Spring Security ?ㅼ젙 ?ㅻ쪟 ?먯씤"}, schema, path="$")
        self.assertEqual(errors, [])

    def test_fallback_excludes_metadata_only_fields(self) -> None:
        query_text, _ = _extract_query_text(
            generation_strategy="C",
            query_type="reason",
            response={
                "query_type": "reason",
                "answerability_type": "single",
                "style_note": "troubleshooting-cause",
                "translated_chunk_ko": "x",
                "summary_ko": "y",
                "metadata": {"title": "sample"},
            },
        )
        self.assertEqual(query_text, "")

    def test_existing_strategy_a_behavior_prefers_query_ko(self) -> None:
        query_text, trace = _extract_query_text(
            generation_strategy="A",
            query_type="procedure",
            response={
                "query_ko": "A ko query",
                "query_en": "A en query",
            },
        )
        self.assertEqual(query_text, "A ko query")
        self.assertEqual(trace.get("query_en"), "A en query")

    def test_existing_strategy_b_and_c_behavior_prefers_query_ko(self) -> None:
        for strategy in ("B", "C"):
            query_text, _ = _extract_query_text(
                generation_strategy=strategy,
                query_type="procedure",
                response={
                    "query_ko": f"{strategy} ko query",
                    "query_en": f"{strategy} en query",
                },
            )
            self.assertEqual(query_text, f"{strategy} ko query")

    def test_existing_strategy_d_behavior_switches_with_query_type(self) -> None:
        code_mixed_query, code_mixed_trace = _extract_query_text(
            generation_strategy="D",
            query_type="code_mixed",
            response={
                "query_ko": "D ko query",
                "query_code_mixed": "D code mixed query",
            },
        )
        self.assertEqual(code_mixed_query, "D code mixed query")
        self.assertEqual(code_mixed_trace.get("query_ko"), "D ko query")
        self.assertEqual(code_mixed_trace.get("query_code_mixed"), "D code mixed query")

        ko_query, ko_trace = _extract_query_text(
            generation_strategy="D",
            query_type="procedure",
            response={
                "query_ko": "D ko query",
                "query_code_mixed": "D code mixed query",
            },
        )
        self.assertEqual(ko_query, "D ko query")
        self.assertEqual(ko_trace.get("query_ko"), "D ko query")
        self.assertEqual(ko_trace.get("query_code_mixed"), "D code mixed query")


if __name__ == "__main__":
    unittest.main()
