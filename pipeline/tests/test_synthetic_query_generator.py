from __future__ import annotations

import unittest
from pathlib import Path
from unittest.mock import patch

from pipeline.common.experiment_config import ExperimentConfig
from pipeline.common.gemini_batch import GeminiBatchExecutionError
from pipeline.common.gemini_batch import GeminiBatchJob
from pipeline.common.gemini_batch import GeminiBatchRequestItem
from pipeline.common.gemini_batch import GeminiBatchResult
from pipeline.common.llm_client import LlmStageConfig
from pipeline.common.local_retriever import RetrieverConfig
from pipeline.common.prompt_assets import PromptAsset
from pipeline.common.llm_client import _validate_json_schema
from pipeline.generation.synthetic_query_generator import BatchJsonExecution
from pipeline.generation.synthetic_query_generator import ChunkRow
from pipeline.generation.synthetic_query_generator import PromptBundle
from pipeline.generation.synthetic_query_generator import TRANSLATION_SEGMENTATION_VERSION
from pipeline.generation.synthetic_query_generator import _b_summary_max_chars
from pipeline.generation.synthetic_query_generator import _b_query_payload_limits
from pipeline.generation.synthetic_query_generator import _build_translation_segments
from pipeline.generation.synthetic_query_generator import _build_query_payload
from pipeline.generation.synthetic_query_generator import _build_query_row_payload
from pipeline.generation.synthetic_query_generator import _extract_query_text
from pipeline.generation.synthetic_query_generator import _gemini_batch_input_mode
from pipeline.generation.synthetic_query_generator import _generation_strategy_for_query_type
from pipeline.generation.synthetic_query_generator import _is_max_tokens_truncation_error
from pipeline.generation.synthetic_query_generator import _deterministic_summary_template_version
from pipeline.generation.synthetic_query_generator import _bounded_query_evidence_text
from pipeline.generation.synthetic_query_generator import _compact_ko_evidence_summary
from pipeline.generation.synthetic_query_generator import _execute_gemini_batch_json_requests
from pipeline.generation.synthetic_query_generator import _llm_execution_mode
from pipeline.generation.synthetic_query_generator import _primary_chunk_text
from pipeline.generation.synthetic_query_generator import _query_response_schema_for_strategy
from pipeline.generation.synthetic_query_generator import _requires_en_summary_asset
from pipeline.generation.synthetic_query_generator import _resolve_or_create_translated_chunk
from pipeline.generation.synthetic_query_generator import _run_strategy_b_gemini_batch
from pipeline.generation.synthetic_query_generator import _summary_source_text_candidates
from pipeline.generation.synthetic_query_generator import _summary_max_tokens_for_strategy


def _prompt_asset(name: str, *, version: str = "v1") -> PromptAsset:
    return PromptAsset(
        prompt_family="test",
        prompt_name=name,
        version=version,
        content_path=f"{name}.md",
        content_hash=f"hash-{name}",
        metadata={},
        prompt_asset_id=f"asset-{name}",
    )


def _prompt_bundle() -> PromptBundle:
    query_assets = {strategy: _prompt_asset(f"gen_{strategy.lower()}") for strategy in ("A", "B", "C", "D", "E", "F", "G")}
    return PromptBundle(
        summary_en_asset=_prompt_asset("summary_en"),
        summary_en_text="summary en prompt",
        summary_ko_asset=_prompt_asset("summary_ko", version="v2"),
        summary_ko_text="summary ko prompt",
        translate_asset=_prompt_asset("translate", version="v3"),
        translate_text="translate prompt",
        query_assets=query_assets,
        query_texts={strategy: f"query prompt {strategy}" for strategy in query_assets},
    )


def _experiment_config_for_b(*, query_type: str = "procedure", enable_code_mixed: bool = False) -> ExperimentConfig:
    return ExperimentConfig(
        experiment_key="test_b_batch",
        category="test",
        description="test",
        generation_strategy="B",
        enable_code_mixed=enable_code_mixed,
        enable_rule_filter=True,
        enable_llm_self_eval=True,
        enable_retrieval_utility=True,
        enable_diversity=True,
        enable_anti_copy=True,
        memory_top_n=5,
        rewrite_candidate_count=3,
        rewrite_threshold=0.1,
        retrieval_top_k=10,
        rerank_top_n=5,
        use_session_context=False,
        avg_queries_per_chunk=1.0,
        query_type_distribution={query_type: 1.0},
        answerability_distribution={"single": 1.0},
        gating_preset="full_gating",
        retrieval_utility_weights={},
        gating_weights={},
        final_score_threshold=0.75,
        utility_threshold=0.7,
        random_seed=31,
        diversity_threshold_same_chunk=0.93,
        diversity_threshold_same_doc=0.96,
        limit_chunks=None,
        retriever_config=RetrieverConfig(dense_embedding_required=False),
        rewrite_adoption_policy={},
        config_path=Path("test.yaml"),
        raw={},
    )


def _chunk() -> ChunkRow:
    return ChunkRow(
        chunk_id="chunk-1",
        document_id="doc-1",
        chunk_text="English source chunk about Spring configuration.",
        title="Config",
        product_name="Spring Boot",
        version_label="3.x",
        content_checksum="content",
        cleaned_checksum="cleaned",
    )


def _stage_config() -> LlmStageConfig:
    return LlmStageConfig(
        provider="gemini-native",
        base_url="https://generativelanguage.googleapis.com/v1beta",
        api_key="test-key",
        model="gemini-2.5-flash-lite",
        temperature=0.2,
        max_tokens=128,
        timeout_seconds=5.0,
        min_interval_seconds=0.0,
        tokens_per_minute=0,
        requests_per_day=0,
        chars_per_token=2.2,
        max_retries=0,
        backoff_initial_seconds=0.1,
        backoff_max_seconds=0.2,
        backoff_multiplier=2.0,
        backoff_jitter_ratio=0.0,
        fallback_models=(),
        thinking_budget=0,
        concurrency_limit=1,
    )


class _FakeClient:
    def __init__(self) -> None:
        self.config = _stage_config()


class _FakeConnection:
    def __init__(self) -> None:
        self.commit_count = 0

    def commit(self) -> None:
        self.commit_count += 1


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

    def test_query_row_payload_adds_canonical_anchor_metadata_without_rewriting_fields(self) -> None:
        chunk = _chunk()
        query_text = "트랜잭션 읽기 전용 설정은 어떻게 하나요?"
        glossary_terms = ["@Transactional"]
        query_payload = _build_query_payload(
            chunk=chunk,
            generation_strategy="B",
            original_chunk_ko="",
            related_chunks_ko=[],
            extractive_summary_en="",
            translated_chunk_ko="translated chunk",
            extractive_summary_ko="summary",
            glossary_terms_keep_english=glossary_terms,
            query_type="procedure",
            answerability_type="single",
            target_chunk_ids=[chunk.chunk_id],
            b_payload_limits=None,
        )

        payload = _build_query_row_payload(
            synthetic_query_id="syn-1",
            run_context_id="run-1",
            generation_method_id="method-b",
            generation_batch_id="batch-1",
            chunk=chunk,
            generation_strategy="B",
            query_prompt_asset=_prompt_asset("gen_b"),
            source_fingerprint="source-fingerprint",
            target_chunk_ids=[chunk.chunk_id],
            answerability_type="single",
            query_text=query_text,
            query_type="procedure",
            generation_asset_ids=[],
            query_response={
                "query_ko": query_text,
                "query_type": "procedure",
                "answerability_type": "single",
            },
            extra_trace={},
            chunk_glossary_terms=glossary_terms,
            glossary_term_candidates=[
                {
                    "term_id": "term-transactional",
                    "canonical_form": "@Transactional",
                    "normalized_form": "@transactional",
                    "term_type": "annotation",
                    "is_active": True,
                }
            ],
            en_summary="",
            summary_ko="summary",
            translated_chunk_ko="translated chunk",
            query_payload=query_payload,
            b_payload_limits=None,
            fg_summary_mode="extractive",
            related_chunks_ko=[],
            llm_provider="gemini-native",
            llm_model="gemini-2.5-flash-lite",
            execution_mode="online",
        )

        self.assertEqual(payload["query_text"], query_text)
        self.assertEqual(payload["glossary_terms"].obj, glossary_terms)

        metadata = payload["metadata"].obj
        self.assertEqual(metadata["anchor_mapping_version"], "anchor-map-v1")
        self.assertEqual(metadata["anchor_normalization_version"], "anchor-normalize-v1")

        canonical = metadata["canonical_anchors"]
        self.assertEqual(canonical["schema_version"], "canonical-anchor-runtime-v1")
        self.assertEqual(canonical["mapping_version"], "anchor-map-v1")
        self.assertEqual(canonical["normalization_version"], "anchor-normalize-v1")
        self.assertEqual(canonical["source_context"]["kind"], "synthetic_query")
        self.assertEqual(canonical["source_context"]["source_id"], "syn-1")
        self.assertEqual(canonical["source_context"]["source_field"], "query_text")
        self.assertEqual(canonical["canonical_terms"], ["@Transactional"])
        self.assertEqual(canonical["canonical_term_ids"], ["term-transactional"])

        anchor = canonical["anchors"][0]
        self.assertEqual(anchor["input_alias"], "@Transactional")
        self.assertEqual(anchor["source_field"], "glossary_terms")
        self.assertEqual(anchor["resolution_status"], "self_fallback")
        self.assertTrue(anchor["used_for_scoring"])

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

    def test_batch_mode_config_is_explicit_opt_in(self) -> None:
        self.assertEqual(_llm_execution_mode({}), "online")
        self.assertEqual(_llm_execution_mode({"gemini_batch_enabled": True}), "gemini_batch")
        self.assertEqual(_llm_execution_mode({"llm_execution_mode": "gemini_batch"}), "gemini_batch")
        self.assertEqual(_gemini_batch_input_mode({}), "inline")
        self.assertEqual(_gemini_batch_input_mode({"gemini_batch_input_mode": "jsonl"}), "jsonl")

    def test_translation_segments_preserve_code_fence_and_source_order(self) -> None:
        source = (
            "# Password Storage\n\n"
            "Use @Transactional with Spring configuration. Keep method names intact.\n\n"
            "```java\n"
            "Pbkdf2PasswordEncoder encoder = Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();\n"
            "```\n\n"
            "- Configure spring.security.user.name.\n"
            "- Verify PasswordEncoder matches.\n"
        )

        segments = _build_translation_segments(source, max_chars=80)

        self.assertEqual("".join(segment.text for segment in segments), source)
        code_segments = [segment for segment in segments if segment.kind == "code"]
        self.assertEqual(len(code_segments), 1)
        self.assertIn("Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()", code_segments[0].text)
        self.assertEqual([segment.index for segment in segments], list(range(len(segments))))

    def test_segmented_translation_reuses_cached_segments_and_reconstructs_full_asset(self) -> None:
        source = (
            "First paragraph about @Transactional.\n\n"
            "```java\n"
            "class Demo {}\n"
            "```\n\n"
            "Second paragraph about spring.jpa.show-sql.\n"
        )
        chunk = ChunkRow(
            chunk_id="chunk-segmented",
            document_id="doc-1",
            chunk_text=source,
            title="Segmented",
            product_name="Spring",
            version_label="3.x",
            content_checksum="content",
            cleaned_checksum="cleaned",
        )
        connection = _FakeConnection()
        created_assets: list[dict[str, object]] = []
        segment_cache_used = {"value": False}

        def fake_find_existing_asset(_connection, **kwargs):
            if str(kwargs["prompt_template_version"]).endswith(":full"):
                return None
            if not segment_cache_used["value"]:
                segment_cache_used["value"] = True
                return ("segment-cached", "첫 문단 번역.\n")
            return None

        def fake_create_asset(_connection, **kwargs):
            created_assets.append(kwargs)
            return f"asset-{len(created_assets)}"

        with patch(
            "pipeline.generation.synthetic_query_generator._find_existing_asset",
            side_effect=fake_find_existing_asset,
        ), patch(
            "pipeline.generation.synthetic_query_generator._llm_json",
            return_value={"translated_chunk_ko": "둘째 문단 번역."},
        ) as llm_json, patch(
            "pipeline.generation.synthetic_query_generator._create_asset",
            side_effect=fake_create_asset,
        ):
            asset_id, translated, cached = _resolve_or_create_translated_chunk(
                connection,
                chunk=chunk,
                source_fingerprint="source-fingerprint",
                prompt_asset=_prompt_asset("translate", version="v3"),
                prompt_text="translate prompt",
                client=_FakeClient(),
            )

        self.assertFalse(cached)
        self.assertEqual(asset_id, "asset-2")
        self.assertEqual(llm_json.call_count, 1)
        self.assertGreaterEqual(connection.commit_count, 1)
        self.assertIn("```java\nclass Demo {}\n```", translated)
        final_asset = created_assets[-1]
        self.assertEqual(final_asset["asset_type"], "KO_TRANSLATED_CHUNK")
        self.assertEqual(final_asset["metadata"]["translation_mode"], "segmented_full")
        self.assertEqual(final_asset["metadata"]["segmentation_version"], TRANSLATION_SEGMENTATION_VERSION)
        self.assertEqual(final_asset["metadata"]["segment_count"], 5)
        self.assertTrue(final_asset["prompt_template_version"].endswith(":full"))

    def test_partial_batch_failure_is_observable_and_raises(self) -> None:
        class _Adapter:
            def submit_inline(self, **_kwargs):
                return GeminiBatchJob(name="batches/failed", state="BATCH_STATE_PENDING", raw={})

            def poll_job(self, **_kwargs):
                return GeminiBatchJob(name="batches/failed", state="BATCH_STATE_SUCCEEDED", raw={})

            def fetch_results(self, **_kwargs):
                return [
                    GeminiBatchResult(
                        key="query:1",
                        metadata={"key": "query:1", "query_id": "q1", "purpose": "generate_query"},
                        response=None,
                        error={"code": 13, "message": "internal"},
                        raw={"error": {"code": 13, "message": "internal"}},
                    )
                ]

        with self.assertRaises(GeminiBatchExecutionError) as raised:
            _execute_gemini_batch_json_requests(
                adapter=_Adapter(),
                stage_config=_stage_config(),
                items=[
                    GeminiBatchRequestItem(
                        key="query:1",
                        request={"contents": []},
                        metadata={"query_id": "q1", "purpose": "generate_query"},
                    )
                ],
                response_schema=_query_response_schema_for_strategy("B"),
                display_name="partial_failure",
                input_mode="inline",
                work_dir=Path("tmp"),
                poll_interval_seconds=1,
                timeout_seconds=60,
                request_purpose="generate_query",
            )

        self.assertEqual(raised.exception.failures[0]["category"], "batch_item_error")
        self.assertEqual(raised.exception.failures[0]["query_id"], "q1")

    def test_b_batch_translation_cache_hit_skips_translation_submission_and_preserves_query_lineage(self) -> None:
        chunk = _chunk()

        def fake_execute(**kwargs):
            self.assertEqual(kwargs["items"], [])
            return BatchJsonExecution(
                job_name=None,
                display_name=kwargs["display_name"],
                input_mode=kwargs["input_mode"],
                submitted_item_count=0,
                completed_item_count=0,
                failed_item_count=0,
                batch_stats={},
                item_mapping=[],
                failures=[],
                responses_by_key={},
            )

        with patch(
            "pipeline.generation.synthetic_query_generator._find_existing_asset",
            return_value=("translation-asset", "translated chunk"),
        ), patch(
            "pipeline.generation.synthetic_query_generator._resolve_or_create_extractive_summary_ko",
            return_value=("summary-asset", "summary", False),
        ), patch(
            "pipeline.generation.synthetic_query_generator._find_cached_query",
            return_value=True,
        ), patch(
            "pipeline.generation.synthetic_query_generator._count_queries_for_generation_batch",
            return_value=1,
        ), patch(
            "pipeline.generation.synthetic_query_generator._execute_gemini_batch_json_requests",
            side_effect=fake_execute,
        ), patch(
            "pipeline.generation.synthetic_query_generator._create_gemini_batch_adapter",
            return_value=object(),
        ), patch(
            "pipeline.generation.synthetic_query_generator._attach_cached_query"
        ) as attach_cached, patch(
            "pipeline.generation.synthetic_query_generator._insert_source_links_for_targets"
        ):
            result = _run_strategy_b_gemini_batch(
                connection=object(),
                config=_experiment_config_for_b(),
                run_context_id="run-1",
                prompts=_prompt_bundle(),
                chunks=[chunk],
                chunks_by_id={chunk.chunk_id: chunk},
                relations={},
                glossary_by_doc={},
                generation_batch_id="batch-1",
                method_id_cache={"B": "method-b"},
                max_total_queries=1,
                initial_generated_count=0,
                b_summary_max_chars=900,
                b_payload_limits=_b_query_payload_limits({}),
                query_client=_FakeClient(),
                translate_client=_FakeClient(),
                input_mode="inline",
                poll_interval_seconds=1,
                timeout_seconds=60,
                work_dir=Path("tmp"),
            )

        self.assertEqual(result["initial_generated_queries"], 0)
        self.assertEqual(result["new_generated_queries"], 0)
        self.assertEqual(result["generated_queries"], 1)
        self.assertEqual(result["reused_queries"], 1)
        self.assertEqual(result["asset_cache_hits"]["KO_TRANSLATED_CHUNK"], 1)
        attach_cached.assert_called_once()
        self.assertEqual(
            attach_cached.call_args.kwargs["generation_asset_ids"],
            ["translation-asset", "summary-asset"],
        )

    def test_b_batch_skips_when_batch_already_reached_target(self) -> None:
        chunk = _chunk()

        with patch(
            "pipeline.generation.synthetic_query_generator._execute_gemini_batch_json_requests"
        ) as execute_batch:
            result = _run_strategy_b_gemini_batch(
                connection=object(),
                config=_experiment_config_for_b(),
                run_context_id="run-1",
                prompts=_prompt_bundle(),
                chunks=[chunk],
                chunks_by_id={chunk.chunk_id: chunk},
                relations={},
                glossary_by_doc={},
                generation_batch_id="batch-1",
                method_id_cache={"B": "method-b"},
                max_total_queries=1,
                initial_generated_count=1,
                b_summary_max_chars=900,
                b_payload_limits=_b_query_payload_limits({}),
                query_client=_FakeClient(),
                translate_client=_FakeClient(),
                input_mode="inline",
                poll_interval_seconds=1,
                timeout_seconds=60,
                work_dir=Path("tmp"),
            )

        self.assertEqual(result["planned_queries"], 0)
        self.assertEqual(result["generated_queries"], 1)
        self.assertEqual(result["new_generated_queries"], 0)
        execute_batch.assert_not_called()

    def test_b_batch_code_mixed_query_still_inserts_into_raw_b(self) -> None:
        chunk = _chunk()

        def fake_execute(**kwargs):
            responses_by_key = {}
            for item in kwargs["items"]:
                if item.key.startswith("query:"):
                    responses_by_key[item.key] = {
                        "query_ko": "code mixed query",
                        "query_type": "code_mixed",
                        "answerability_type": "single",
                        "_llm_meta": {
                            "gemini_batch": {"job_name": "batches/query", "item_key": item.key},
                            "usage": {"total_tokens": 11},
                        },
                    }
            return BatchJsonExecution(
                job_name="batches/query" if responses_by_key else None,
                display_name=kwargs["display_name"],
                input_mode=kwargs["input_mode"],
                submitted_item_count=len(kwargs["items"]),
                completed_item_count=len(responses_by_key),
                failed_item_count=0,
                batch_stats={},
                item_mapping=[],
                failures=[],
                responses_by_key=responses_by_key,
            )

        inserted_tables: list[str] = []

        def fake_insert_query_row(_connection, *, table_name, payload):
            inserted_tables.append(table_name)
            response = payload["llm_output"].obj["response"]
            self.assertEqual(set(response.keys()), {"query_ko", "query_type", "answerability_type", "_llm_meta"})

        with patch(
            "pipeline.generation.synthetic_query_generator._find_existing_asset",
            return_value=("translation-asset", "translated chunk"),
        ), patch(
            "pipeline.generation.synthetic_query_generator._resolve_or_create_extractive_summary_ko",
            return_value=("summary-asset", "summary", False),
        ), patch(
            "pipeline.generation.synthetic_query_generator._find_cached_query",
            return_value=False,
        ), patch(
            "pipeline.generation.synthetic_query_generator._count_queries_for_generation_batch",
            return_value=1,
        ), patch(
            "pipeline.generation.synthetic_query_generator._execute_gemini_batch_json_requests",
            side_effect=fake_execute,
        ), patch(
            "pipeline.generation.synthetic_query_generator._create_gemini_batch_adapter",
            return_value=object(),
        ), patch(
            "pipeline.generation.synthetic_query_generator._insert_query_row",
            side_effect=fake_insert_query_row,
        ), patch(
            "pipeline.generation.synthetic_query_generator._insert_source_links_for_targets"
        ):
            result = _run_strategy_b_gemini_batch(
                connection=object(),
                config=_experiment_config_for_b(query_type="code_mixed", enable_code_mixed=True),
                run_context_id="run-1",
                prompts=_prompt_bundle(),
                chunks=[chunk],
                chunks_by_id={chunk.chunk_id: chunk},
                relations={},
                glossary_by_doc={},
                generation_batch_id="batch-1",
                method_id_cache={"B": "method-b", "D": "method-d"},
                max_total_queries=1,
                initial_generated_count=0,
                b_summary_max_chars=900,
                b_payload_limits=_b_query_payload_limits({}),
                query_client=_FakeClient(),
                translate_client=_FakeClient(),
                input_mode="inline",
                poll_interval_seconds=1,
                timeout_seconds=60,
                work_dir=Path("tmp"),
            )

        self.assertEqual(result["new_generated_queries"], 1)
        self.assertEqual(result["generated_queries"], 1)
        self.assertEqual(inserted_tables, ["synthetic_queries_raw_b"])


if __name__ == "__main__":
    unittest.main()
