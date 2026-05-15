from __future__ import annotations

import unittest

from pipeline.common.llm_client import _validate_json_schema
from pipeline.generation.synthetic_query_generator import ChunkRow
from pipeline.generation.synthetic_query_generator import _b_summary_max_chars
from pipeline.generation.synthetic_query_generator import _b_query_payload_limits
from pipeline.generation.synthetic_query_generator import _build_query_payload
from pipeline.generation.synthetic_query_generator import _extract_query_text
from pipeline.generation.synthetic_query_generator import _generation_strategy_for_query_type
from pipeline.generation.synthetic_query_generator import _is_max_tokens_truncation_error
from pipeline.generation.synthetic_query_generator import _deterministic_summary_template_version
from pipeline.generation.synthetic_query_generator import _bounded_query_evidence_text
from pipeline.generation.synthetic_query_generator import _compact_ko_evidence_summary
from pipeline.generation.synthetic_query_generator import _primary_chunk_text
from pipeline.generation.synthetic_query_generator import _query_response_schema_for_strategy
from pipeline.generation.synthetic_query_generator import _requires_en_summary_asset
from pipeline.generation.synthetic_query_generator import _summary_source_text_candidates
from pipeline.generation.synthetic_query_generator import _summary_max_tokens_for_strategy


class SyntheticQueryGeneratorSchemaTests(unittest.TestCase):
    def test_strategy_required_fields(self) -> None:
        expected_required = {
            "A": ("query_en", "query_ko"),
            "B": ("query_ko", "query_type", "answerability_type"),
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

    def test_b_schema_requires_query_only_contract_fields(self) -> None:
        schema = _query_response_schema_for_strategy("B")
        valid_payload = {
            "query_ko": "Spring Boot configuration binding failure reason?",
            "query_type": "reason",
            "answerability_type": "single",
        }
        self.assertEqual(_validate_json_schema(valid_payload, schema, path="$"), [])
        errors = _validate_json_schema({"query_ko": valid_payload["query_ko"]}, schema, path="$")
        self.assertIn("$.query_type: required field missing", errors)
        self.assertIn("$.answerability_type: required field missing", errors)

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

    def test_summary_max_tokens_boosted_for_f_and_g(self) -> None:
        boosted_f = _summary_max_tokens_for_strategy(generation_strategy="F", base_max_tokens=384)
        boosted_g = _summary_max_tokens_for_strategy(generation_strategy="G", base_max_tokens=512)
        self.assertGreaterEqual(boosted_f, 2048)
        self.assertGreaterEqual(boosted_g, 2048)

    def test_summary_max_tokens_unchanged_for_non_fg(self) -> None:
        unchanged = _summary_max_tokens_for_strategy(generation_strategy="C", base_max_tokens=384)
        self.assertEqual(unchanged, 384)

    def test_summary_source_candidates_shrink_only_for_f_and_g(self) -> None:
        long_text = "x" * 5000
        fg_candidates = _summary_source_text_candidates(generation_strategy="F", source_text_ko=long_text)
        self.assertEqual([len(value) for value in fg_candidates], [5000, 3200, 2200, 1400])

        c_candidates = _summary_source_text_candidates(generation_strategy="C", source_text_ko=long_text)
        self.assertEqual([len(value) for value in c_candidates], [5000])

    def test_b_path_does_not_require_en_summary_asset(self) -> None:
        expected = {
            "A": True,
            "B": False,
            "C": True,
            "D": True,
            "E": True,
            "F": False,
            "G": False,
        }
        for strategy, requires_summary in expected.items():
            self.assertEqual(_requires_en_summary_asset(strategy), requires_summary)

    def test_b_query_payload_uses_ko_inputs_without_en_summary(self) -> None:
        chunk = ChunkRow(
            chunk_id="chunk-b-1",
            document_id="doc-b-1",
            chunk_text="English source chunk about configuration binding.",
            title="Configuration Binding",
            product_name="Spring Boot",
            version_label="3.x",
            content_checksum="content-checksum",
            cleaned_checksum="cleaned-checksum",
        )
        payload = _build_query_payload(
            chunk=chunk,
            generation_strategy="B",
            original_chunk_ko=chunk.chunk_text,
            related_chunks_ko=[],
            extractive_summary_en="",
            translated_chunk_ko="Korean translated chunk with @ConfigurationProperties.",
            extractive_summary_ko="Korean extractive summary with @ConfigurationProperties.",
            glossary_terms_keep_english=["@ConfigurationProperties"],
            query_type="procedure",
            answerability_type="single",
            target_chunk_ids=[chunk.chunk_id],
        )
        self.assertEqual(payload["original_chunk_en"], chunk.chunk_text)
        self.assertEqual(payload["original_chunk_ko"], "")
        self.assertEqual(payload["extractive_summary_en"], "")
        self.assertEqual(payload["translated_chunk_ko"], "Korean translated chunk with @ConfigurationProperties.")
        self.assertEqual(payload["extractive_summary_ko"], "Korean extractive summary with @ConfigurationProperties.")
        self.assertEqual(payload["glossary_terms_keep_english"], ["@ConfigurationProperties"])

    def test_b_summary_max_chars_default_and_bounds(self) -> None:
        self.assertEqual(_b_summary_max_chars({}), 900)
        self.assertEqual(_b_summary_max_chars({"b_summary_max_chars": 100}), 300)
        self.assertEqual(_b_summary_max_chars({"b_summary_max_chars": 5000}), 1600)

    def test_b_query_payload_limits_default_and_bounds(self) -> None:
        defaults = _b_query_payload_limits({})
        self.assertEqual(defaults.original_chunk_en_max_chars, 1800)
        self.assertEqual(defaults.translated_chunk_ko_max_chars, 1200)
        self.assertEqual(defaults.extractive_summary_ko_max_chars, 900)

        clamped = _b_query_payload_limits(
            {
                "b_query_original_chunk_max_chars": 100,
                "b_query_translated_chunk_max_chars": 5000,
                "b_query_summary_max_chars": 100,
            }
        )
        self.assertEqual(clamped.original_chunk_en_max_chars, 600)
        self.assertEqual(clamped.translated_chunk_ko_max_chars, 2400)
        self.assertEqual(clamped.extractive_summary_ko_max_chars, 300)

    def test_b_query_payload_bounds_long_evidence(self) -> None:
        chunk = ChunkRow(
            chunk_id="chunk-b-long",
            document_id="doc-b-long",
            chunk_text=("English configuration binding paragraph. " * 80)
            + "\n\n"
            + ("Another English paragraph about nested properties. " * 80),
            title="Long Configuration Binding",
            product_name="Spring Boot",
            version_label="3.x",
            content_checksum="content-checksum",
            cleaned_checksum="cleaned-checksum",
        )
        limits = _b_query_payload_limits(
            {
                "b_query_original_chunk_max_chars": 700,
                "b_query_translated_chunk_max_chars": 360,
                "b_query_summary_max_chars": 320,
            }
        )
        payload = _build_query_payload(
            chunk=chunk,
            generation_strategy="B",
            original_chunk_ko=chunk.chunk_text,
            related_chunks_ko=[],
            extractive_summary_en="",
            translated_chunk_ko=("Korean translated configuration paragraph. " * 80),
            extractive_summary_ko=("Korean extractive summary about binding. " * 40),
            glossary_terms_keep_english=["@ConfigurationProperties"],
            query_type="procedure",
            answerability_type="single",
            target_chunk_ids=[chunk.chunk_id],
            b_payload_limits=limits,
        )
        self.assertEqual(payload["original_chunk_ko"], "")
        self.assertEqual(payload["extractive_summary_en"], "")
        self.assertLessEqual(len(payload["original_chunk_en"]), 700)
        self.assertLessEqual(len(payload["translated_chunk_ko"]), 360)
        self.assertLessEqual(len(payload["extractive_summary_ko"]), 320)

    def test_bounded_query_evidence_text_prefers_paragraph_boundary(self) -> None:
        source = "first paragraph stays intact\n\nsecond paragraph should be omitted"
        bounded = _bounded_query_evidence_text(source, max_chars=40)
        self.assertEqual(bounded, "first paragraph stays intact")

    def test_deterministic_summary_cache_version_includes_max_chars(self) -> None:
        version_900 = _deterministic_summary_template_version(
            prompt_version="v2",
            prompt_version_suffix="B",
            max_chars=900,
        )
        version_1600 = _deterministic_summary_template_version(
            prompt_version="v2",
            prompt_version_suffix="B",
            max_chars=1600,
        )
        self.assertEqual(version_900, "v2:B:extractive:max900")
        self.assertNotEqual(version_900, version_1600)

    def test_code_mixed_routing_preserves_b_and_native_e_f_g_strategies(self) -> None:
        self.assertEqual(_generation_strategy_for_query_type("A", "code_mixed", True), "D")
        self.assertEqual(_generation_strategy_for_query_type("B", "code_mixed", True), "B")
        self.assertEqual(_generation_strategy_for_query_type("C", "code_mixed", True), "D")
        self.assertEqual(_generation_strategy_for_query_type("D", "code_mixed", True), "D")
        self.assertEqual(_generation_strategy_for_query_type("E", "code_mixed", True), "E")
        self.assertEqual(_generation_strategy_for_query_type("F", "code_mixed", True), "F")
        self.assertEqual(_generation_strategy_for_query_type("G", "code_mixed", True), "G")

    def test_primary_chunk_text_strips_overlap_context(self) -> None:
        source = (
            "Overlap context from previous chunk:\n"
            "previous section details\n\n"
            "Section Path: library/functions\n\n"
            "Use isinstance() to check object types."
        )
        primary = _primary_chunk_text(source)
        self.assertNotIn("previous section details", primary)
        self.assertTrue(primary.startswith("Section Path: library/functions"))

    def test_compact_ko_evidence_summary_strips_overlap_and_caps_length(self) -> None:
        source = (
            "Overlap context from previous chunk:\n"
            "old context\n\n"
            "Section Path: reference/import\n\n"
            "import statements load modules and bind names.\n\n"
            + ("Additional Python module details. " * 80)
        )
        summary = _compact_ko_evidence_summary(source, max_chars=220)
        self.assertLessEqual(len(summary), 220)
        self.assertNotIn("old context", summary)
        self.assertIn("Section Path: reference/import", summary)

    def test_is_max_tokens_truncation_error_detects_details_category(self) -> None:
        class _Details:
            category = "max_tokens_truncated"

        class _Cause(RuntimeError):
            def __init__(self) -> None:
                super().__init__("inner")
                self.details = _Details()

        outer = RuntimeError("outer")
        outer.__cause__ = _Cause()
        self.assertTrue(_is_max_tokens_truncation_error(outer))

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
