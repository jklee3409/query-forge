from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

from pipeline.eval import retrieval_eval
from pipeline.eval.java_retrieval_client import (
    JavaRetrievalEvalClient,
    java_retrieval_settings_from_config,
)
from pipeline.eval.official_retrieval_eval import GATING_COMPARISON_MODES

from .test_java_retrieval_client import _FakeResponse, _evaluate_java_row, _sample


DOMAIN_ID = "11111111-1111-1111-1111-111111111111"
BATCH_ID = "22222222-2222-2222-2222-222222222222"
RUN_ID = "33333333-3333-3333-3333-333333333333"


class _OfficialSession:
    def __init__(self, *, invalid_snapshot: bool = False, omit_trace: bool = False):
        self.calls = []
        self.invalid_snapshot = invalid_snapshot
        self.omit_trace = omit_trace

    def post(self, url: str, *, json: dict, timeout: float) -> _FakeResponse:
        self.calls.append(json)
        mode = json["forcedMode"]
        raw = mode == "raw_only"
        runtime = json["runtimeConfig"]
        retrieved_ids = list(_sample().expected_chunk_ids) if raw else ["other-chunk"]
        snapshot = {
            "gatingPreset": runtime.get("gatingPreset"),
            "sourceGatingBatchIds": runtime.get("sourceGatingBatchIds", []),
            "sourceGatingRunIds": runtime.get("sourceGatingRunIds", []),
        }
        if self.invalid_snapshot:
            snapshot["sourceGatingBatchIds"] = []
        body = {
            "forcedMode": mode,
            "selectedMode": mode,
            "finalQuery": json["query"] if raw else "rewritten",
            "retrievedChunkIds": retrieved_ids,
            "retrievedDocs": [{"chunkId": item, "score": 0.5} for item in retrieved_ids],
            "latencyMs": 15,
        }
        if not self.omit_trace:
            body["trace"] = {"retrievalTrace": {
                "evalPolicyVersion": 2,
                "rewriteApplied": not raw,
                "decisionReason": "raw_only" if raw else "confidence_gain",
                "rawConfidence": 0.3,
                "bestCandidateConfidence": None if raw else 0.5,
                "generation": None if raw else {
                    "attempted": True,
                    "succeeded": True,
                    "heuristicFallback": False,
                    "promptAssetId": "fixture-prompt",
                    "latencyMs": 4,
                },
                "anchorCandidates": [] if raw else [{"anchor": "FilterChainProxy"}],
                "anchorInjectionEnabled": not raw,
                "rawLatencyMs": 3,
                "rewriteLatencyMs": None if raw else 8,
                "snapshot": snapshot,
            }}
        return _FakeResponse(status_code=200, body=body)


def _config(modes: list[str]):
    raw = {
        "retrieval_eval_backend": "java",
        "java_backend_domain_id": DOMAIN_ID,
        "run_discipline": "official",
        "official_comparison_type": "gating_effect",
        "forced_retrieval_mode": "raw_only",
        "retrieval_modes": modes,
        "answer_eval_mode": modes[-1],
        "eval_policy_version": 2,
        "dataset_id": DOMAIN_ID,
        "comparison_snapshots": {
            preset: {
                "gating_batch_id": f"{i}{BATCH_ID[1:]}",
                "source_gating_run_id": f"{i}{RUN_ID[1:]}",
            }
            for i, preset in enumerate(GATING_COMPARISON_MODES.values(), start=1)
        },
        "gatingPreset": "existing",
        "sourceGatingBatchIds": [DOMAIN_ID],
        "sourceGatingRunIds": [DOMAIN_ID],
    }
    return SimpleNamespace(
        raw=raw, retrieval_top_k=10, experiment_key="official", category="test",
        description="", config_path=Path("official.yaml"), config_hash="fixture",
        retriever_config=SimpleNamespace(to_metadata=dict),
    )


class OfficialRetrievalEvalTest(unittest.TestCase):
    def test_version_only_applies_to_official_java_runs_without_explicit_override(self):
        raw = _config(["raw_only"]).raw
        self.assertEqual(java_retrieval_settings_from_config(raw).eval_policy_version, 2)
        for override in (
            {"eval_policy_version": "2"}, {"run_discipline": "exploratory"},
            {"synthetic_free_baseline": True}, {"java_forced_mode": "strategy_router"},
        ):
            with self.subTest(override=override):
                settings = java_retrieval_settings_from_config({**raw, **override})
                self.assertIsNone(settings.eval_policy_version)
                self.assertNotIn("eval_policy_version", settings.runtime_config)

    def test_distinct_gating_rows_send_and_record_their_actual_snapshot(self):
        config = _config(["raw_only", *GATING_COMPARISON_MODES])
        settings = java_retrieval_settings_from_config(config.raw)
        session = _OfficialSession()
        for mode, preset in GATING_COMPARISON_MODES.items():
            _, info, _ = _evaluate_java_row(mode, config, settings, session)
            with self.subTest(mode=mode):
                request = session.calls[-1]
                self.assertEqual(request["forcedMode"], "selective_rewrite")
                self.assertTrue(request["includeTrace"])
                self.assertEqual(request["runtimeConfig"]["eval_policy_version"], 2)
                self.assertEqual(info["snapshot"]["gatingPreset"], preset)
                self.assertEqual(
                    info["snapshot"]["sourceGatingBatchIds"],
                    [config.raw["comparison_snapshots"][preset]["gating_batch_id"]],
                )
                self.assertEqual(info["rewrite_generation"]["promptAssetId"], "fixture-prompt")
                self.assertEqual(info["anchor_candidates"], [{"anchor": "FilterChainProxy"}])

    def test_missing_trace_or_wrong_snapshot_fails_before_scoring(self):
        mode = "selective_rewrite_full_gating"
        config = _config(["raw_only", mode])
        settings = java_retrieval_settings_from_config(config.raw)
        for session, error_code in (
            (_OfficialSession(omit_trace=True), "invalid_official_retrieval_trace"),
            (_OfficialSession(invalid_snapshot=True), "java_retrieval_snapshot_mismatch"),
        ):
            with self.subTest(error_code=error_code):
                with self.assertRaises(retrieval_eval.JavaRetrievalClientError) as rejected:
                    _evaluate_java_row(mode, config, settings, session)
                self.assertEqual(rejected.exception.code, error_code)

    def test_run_uses_java_raw_as_its_baseline_and_keeps_missing_baseline_null(self):
        comparison_mode = "selective_rewrite_full_gating"
        for modes in (["raw_only", comparison_mode], [comparison_mode]):
            with self.subTest(modes=modes), tempfile.TemporaryDirectory() as tmp:
                config = _config(modes)
                directory = Path(tmp)
                recorder = MagicMock()
                recorder.start_run.return_value = SimpleNamespace(experiment_run_id=RUN_ID)
                with (
                    patch.object(retrieval_eval, "load_experiment_config", return_value=config),
                    patch.object(retrieval_eval, "connect", return_value=MagicMock()),
                    patch.object(retrieval_eval, "ExperimentRunRecorder", return_value=recorder),
                    patch.object(retrieval_eval, "load_eval_samples", return_value=[_sample()]),
                    patch.object(retrieval_eval, "load_chunk_items", side_effect=AssertionError),
                    patch.object(retrieval_eval, "_precompute_raw_retrieval", side_effect=AssertionError),
                    patch.object(retrieval_eval, "_write_official_answer_context",
                                 return_value=(directory / "artifact.json", comparison_mode)) as writer,
                ):
                    summary = retrieval_eval.run_retrieval_eval(
                        experiment="official", output_root=directory, docs_root=directory,
                        java_client=JavaRetrievalEvalClient("http://java", session=_OfficialSession()),
                    )
                self.assertEqual(summary["eval_policy_version"], 2)
                self.assertEqual(summary["experiment_run_id"], RUN_ID)
                result = summary["summary"][-1]
                details = json.loads((directory / "rewrite_cases_official.json").read_text(encoding="utf-8"))[-1]
                self.assertEqual(result["p95_server_latency_ms"], 15)
                self.assertEqual(result["p95_rewrite_latency_ms"], 8)
                self.assertEqual(details["raw_rank"], 1 if "raw_only" in modes else None)
                self.assertEqual(details["raw_mrr"], 1.0 if "raw_only" in modes else None)
                self.assertEqual(result["rewrite_gain_mrr"], -1.0 if "raw_only" in modes else None)
                self.assertEqual(result["bad_rewrite_rate"], 1.0 if "raw_only" in modes else None)
                writer.assert_called_once()
