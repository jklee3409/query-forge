from __future__ import annotations

import unittest

from pipeline.gating.quality_gating import _resolve_generation_run_ids


class QualityGatingConfigTests(unittest.TestCase):
    def test_requires_explicit_generation_run_id(self) -> None:
        with self.assertRaisesRegex(ValueError, "source_generation_run_id"):
            _resolve_generation_run_ids({})

    def test_accepts_source_generation_run_ids_list(self) -> None:
        resolved = _resolve_generation_run_ids(
            {
                "source_generation_run_ids": [
                    "run-a",
                    " run-b ",
                    "",
                    "run-a",
                ]
            }
        )
        self.assertEqual(resolved, ["run-a", "run-b"])

    def test_falls_back_to_single_source_generation_run_id(self) -> None:
        resolved = _resolve_generation_run_ids({"source_generation_run_id": " run-single "})
        self.assertEqual(resolved, ["run-single"])

    def test_list_has_priority_over_single_value(self) -> None:
        resolved = _resolve_generation_run_ids(
            {
                "source_generation_run_ids": ["run-list"],
                "source_generation_run_id": "run-single",
            }
        )
        self.assertEqual(resolved, ["run-list"])


if __name__ == "__main__":
    unittest.main()
