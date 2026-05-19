from __future__ import annotations

import unittest

from pipeline.common.anchor_normalization import (
    DEFAULT_NORMALIZATION_VERSION,
    normalize_anchor_alias,
    resolve_canonical_anchors,
)


class AnchorNormalizationTests(unittest.TestCase):
    def test_anchor_normalize_v1_fixtures(self) -> None:
        cases = [
            ("@Transactional", "en", "annotation", "@transactional"),
            ("Transactional", "en", "annotation", "@transactional"),
            ("transaction readonly", "en", "concept", "transaction readonly"),
            ("transaction-readonly", "en", "concept", "transaction readonly"),
            ("transaction_readonly", "en", "concept", "transaction readonly"),
            ("transactional read only", "en", "concept", "transactional read only"),
            ("transactional-read-only", "en", "concept", "transactional read only"),
            ("트랜잭션 읽기 전용", "ko", "concept", "트랜잭션읽기전용"),
            ("읽기 전용 트랜잭션", "ko", "concept", "읽기전용트랜잭션"),
            ("spring.main.web-application-type", "en", "config_key", "spring.main.web-application-type"),
            ("spring-boot-starter-web", "en", "artifact", "spring-boot-starter-web"),
            ("HttpMessageConverter", "en", "class", "httpmessageconverter"),
        ]
        for alias_text, alias_language, term_type, expected in cases:
            with self.subTest(alias_text=alias_text, alias_language=alias_language, term_type=term_type):
                result = normalize_anchor_alias(alias_text, alias_language, term_type)
                self.assertEqual(result.normalized_alias, expected)
                self.assertEqual(result.normalization_version, DEFAULT_NORMALIZATION_VERSION)

    def test_display_alias_is_not_overwritten_by_normalized_alias(self) -> None:
        result = normalize_anchor_alias("Transactional", "en", "annotation")

        self.assertEqual(result.display_alias, "Transactional")
        self.assertEqual(result.normalized_alias, "@transactional")

    def test_alias_language_is_required_and_not_inferred(self) -> None:
        with self.assertRaises(ValueError):
            normalize_anchor_alias("트랜잭션 읽기 전용", "", "concept")

    def test_resolver_returns_metadata_only_payload_for_self_fallback(self) -> None:
        payload = resolve_canonical_anchors(
            [
                {
                    "alias_text": "@Transactional",
                    "alias_language": "en",
                    "term_type": "annotation",
                    "source_field": "query_text",
                }
            ],
            source_context={
                "kind": "eval_query",
                "source_id": "sample-1",
                "source_field": "query_text",
            },
            fallback_term_candidates=[
                {
                    "term_id": "term-transactional",
                    "canonical_form": "@Transactional",
                    "normalized_form": "@transactional",
                    "term_type": "annotation",
                    "is_active": True,
                }
            ],
        )

        self.assertEqual(payload["canonical_terms"], ["@Transactional"])
        self.assertEqual(payload["canonical_term_ids"], ["term-transactional"])
        self.assertEqual(payload["unresolved_aliases"], [])
        anchor = payload["anchors"][0]
        self.assertEqual(anchor["input_alias"], "@Transactional")
        self.assertEqual(anchor["resolution_status"], "self_fallback")
        self.assertTrue(anchor["used_for_scoring"])


if __name__ == "__main__":
    unittest.main()
