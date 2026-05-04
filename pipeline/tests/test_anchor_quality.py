from __future__ import annotations

import unittest

from pipeline.common.anchor_quality import extract_technical_tokens, is_valid_anchor_phrase
from pipeline.preprocess.chunk_docs import normalize_anchor_phrase


class AnchorQualityTests(unittest.TestCase):
    def test_korean_functional_phrases_are_rejected(self) -> None:
        self.assertFalse(is_valid_anchor_phrase("수 있습니다", language_hint="ko"))
        self.assertFalse(is_valid_anchor_phrase("지원합니다", language_hint="ko"))
        self.assertEqual(normalize_anchor_phrase("부탁드립니다", language_hint="ko"), "")

    def test_technical_terms_are_preserved(self) -> None:
        self.assertTrue(is_valid_anchor_phrase("DigestAuthenticationFilter", language_hint="ko"))
        self.assertTrue(is_valid_anchor_phrase("spring.security.filter.order", language_hint="ko"))
        self.assertEqual(
            normalize_anchor_phrase("DigestAuthenticationFilter", language_hint="ko"),
            "DigestAuthenticationFilter",
        )

    def test_technical_token_extraction_filters_functional_terms(self) -> None:
        tokens = extract_technical_tokens(
            "지원합니다 DigestAuthenticationFilter 수 있습니다 spring.security.filter.order",
            language_hint="ko",
            max_items=8,
        )
        self.assertIn("DigestAuthenticationFilter", tokens)
        self.assertIn("spring.security.filter.order", tokens)
        self.assertNotIn("지원합니다", tokens)
        self.assertNotIn("수 있습니다", tokens)


if __name__ == "__main__":
    unittest.main()
