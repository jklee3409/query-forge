from __future__ import annotations

import json
import logging
import random
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

import psycopg
from psycopg.types.json import Jsonb

try:
    from common.experiment_config import load_experiment_config
    from common.experiment_run import ExperimentRunRecorder
    from common.text_utils import extract_extractive_summary
    from loaders.common import connect, default_database_args
except ModuleNotFoundError:  # pragma: no cover - direct module execution fallback
    from pipeline.common.experiment_config import load_experiment_config
    from pipeline.common.experiment_run import ExperimentRunRecorder
    from pipeline.common.text_utils import extract_extractive_summary
    from pipeline.loaders.common import connect, default_database_args


LOGGER = logging.getLogger(__name__)

SPLIT_TARGETS = {
    "dev": {
        "general_ko": 25,
        "troubleshooting": 15,
        "short_user": 10,
        "code_mixed": 10,
        "follow_up": 10,
    },
    "test": {
        "general_ko": 25,
        "troubleshooting": 15,
        "short_user": 10,
        "code_mixed": 10,
        "follow_up": 10,
    },
}


@dataclass(slots=True)
class CandidateSample:
    query_text: str
    query_type: str
    target_doc_id: str
    target_chunk_ids: list[str]
    answerability_type: str
    product: str | None
    version_label: str | None
    canonical_url: str | None
    key_points: list[str]

    @property
    def category(self) -> str:
        if self.query_type == "short_user":
            return "short_user"
        if self.query_type == "code_mixed":
            return "code_mixed"
        if self.query_type == "follow_up":
            return "follow_up"
        lowered = self.query_text.lower()
        if any(token in lowered for token in ("오류", "에러", "exception", "failed", "fail")):
            return "troubleshooting"
        return "general_ko"

    @property
    def family(self) -> str:
        if not self.canonical_url:
            return self.target_doc_id.split("::")[0]
        parsed = urlparse(self.canonical_url)
        parts = [part for part in parsed.path.split("/") if part]
        if not parts:
            return parsed.netloc or self.target_doc_id.split("::")[0]
        return f"{parsed.netloc}/{parts[0]}"


def _load_candidates(connection: psycopg.Connection[Any], limit: int = 800) -> list[CandidateSample]:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT m.query_text,
                   m.query_type,
                   m.target_doc_id,
                   m.target_chunk_ids,
                   r.answerability_type,
                   m.product,
                   c.version_label,
                   d.canonical_url,
                   ARRAY(
                       SELECT cc.chunk_text
                       FROM corpus_chunks cc
                       WHERE cc.chunk_id = ANY(
                           ARRAY(
                               SELECT jsonb_array_elements_text(m.target_chunk_ids)
                           )
                       )
                       ORDER BY cc.chunk_index_in_document
                       LIMIT 2
                   ) AS key_point_texts
            FROM memory_entries m
            JOIN synthetic_queries_gated g ON g.gated_query_id = m.source_gated_query_id
            JOIN synthetic_queries_raw_all r ON r.synthetic_query_id = g.synthetic_query_id
            LEFT JOIN corpus_documents d ON d.document_id = m.target_doc_id
            LEFT JOIN corpus_chunks c ON c.chunk_id = m.chunk_id_source
            ORDER BY m.created_at DESC
            LIMIT %s
            """,
            (limit,),
        )
        rows = cursor.fetchall()

    candidates: list[CandidateSample] = []
    for row in rows:
        key_points = [
            extract_extractive_summary(text, max_sentences=1)
            for text in (row["key_point_texts"] or [])
            if text
        ]
        candidates.append(
            CandidateSample(
                query_text=str(row["query_text"]),
                query_type=str(row["query_type"]),
                target_doc_id=str(row["target_doc_id"]),
                target_chunk_ids=list(row["target_chunk_ids"] or []),
                answerability_type=str(row["answerability_type"]),
                product=row["product"],
                version_label=row["version_label"],
                canonical_url=row["canonical_url"],
                key_points=key_points,
            )
        )
    return candidates


def _split_families(candidates: list[CandidateSample], rng: random.Random) -> tuple[set[str], set[str]]:
    families = sorted({candidate.family for candidate in candidates})
    rng.shuffle(families)
    midpoint = max(1, len(families) // 2)
    dev_families = set(families[:midpoint])
    test_families = set(families[midpoint:])
    if not test_families:
        test_families = set(dev_families)
    return dev_families, test_families


def _allocate_samples(
    candidates: list[CandidateSample],
    *,
    split: str,
    families: set[str],
    targets: dict[str, int],
    rng: random.Random,
) -> list[CandidateSample]:
    in_split = [candidate for candidate in candidates if candidate.family in families]
    by_category: dict[str, list[CandidateSample]] = {}
    for category in targets:
        rows = [candidate for candidate in in_split if candidate.category == category]
        rng.shuffle(rows)
        by_category[category] = rows

    selected: list[CandidateSample] = []
    for category, target_count in targets.items():
        rows = by_category.get(category, [])
        if len(rows) >= target_count:
            selected.extend(rows[:target_count])
            continue
        selected.extend(rows)
        shortage = target_count - len(rows)
        source_pool = rows or in_split or candidates
        for index in range(shortage):
            base = source_pool[index % len(source_pool)]
            selected.append(_synthesize_candidate(base, category, index))

    total_target = sum(targets.values())
    if len(selected) < total_target:
        if not in_split:
            in_split = list(candidates)
        suffixes = [
            " (실무 기준)",
            " (빠른 해결 중심)",
            " 예시도 포함해 주세요.",
            " 핵심만 짧게 답해주세요.",
            " 설정 순서 위주로 알려주세요.",
        ]
        index = 0
        while len(selected) < total_target and in_split:
            base = in_split[index % len(in_split)]
            suffix = suffixes[index % len(suffixes)]
            selected.append(
                CandidateSample(
                    query_text=(base.query_text + suffix).strip(),
                    query_type=base.query_type,
                    target_doc_id=base.target_doc_id,
                    target_chunk_ids=base.target_chunk_ids,
                    answerability_type=base.answerability_type,
                    product=base.product,
                    version_label=base.version_label,
                    canonical_url=base.canonical_url,
                    key_points=base.key_points,
                )
            )
            index += 1
    return selected[:total_target]


def _synthesize_candidate(base: CandidateSample, category: str, seed: int) -> CandidateSample:
    if category == "troubleshooting":
        query_text = f"{base.query_text} 실행 시 오류가 나는 경우 점검 포인트를 알려주세요."
        query_type = "reason"
    elif category == "short_user":
        head = base.query_text.split("?")[0].strip()
        query_text = f"{head} 빠르게?"
        query_type = "short_user"
    elif category == "code_mixed":
        query_text = f"{base.query_text} 설정할 때 default property가 뭐야?"
        query_type = "code_mixed"
    elif category == "follow_up":
        query_text = f"{base.query_text} 그다음에는 뭘 하면 돼?"
        query_type = "follow_up"
    else:
        query_text = f"{base.query_text} 실무 예시까지 포함해서 설명해 주세요."
        query_type = "procedure"
    return CandidateSample(
        query_text=query_text,
        query_type=query_type,
        target_doc_id=base.target_doc_id,
        target_chunk_ids=base.target_chunk_ids,
        answerability_type=base.answerability_type,
        product=base.product,
        version_label=base.version_label,
        canonical_url=base.canonical_url,
        key_points=base.key_points,
    )


def _to_eval_row(sample: CandidateSample, *, split: str, index: int) -> dict[str, Any]:
    sample_id = f"{split}-human-{index:03d}"
    dialog_context = {}
    if sample.category == "follow_up":
        dialog_context = {
            "previous_user_question": "앞 단계 설정은 했는데 다음 단계가 헷갈립니다.",
            "previous_assistant_summary": "기본 빈 등록은 완료된 상태입니다.",
        }
    return {
        "sample_id": sample_id,
        "split": split,
        "user_query_ko": sample.query_text,
        "dialog_context": dialog_context,
        "expected_doc_ids": [sample.target_doc_id],
        "expected_chunk_ids": sample.target_chunk_ids,
        "expected_answer_key_points": sample.key_points,
        "query_category": sample.category,
        "difficulty": "hard" if len(sample.target_chunk_ids) > 1 else "medium",
        "single_or_multi_chunk": "multi" if len(sample.target_chunk_ids) > 1 else "single",
        "source_product": sample.product,
        "source_version_if_available": sample.version_label,
    }


def _persist_eval_samples(
    connection: psycopg.Connection[Any],
    rows: list[dict[str, Any]],
) -> None:
    with connection.cursor() as cursor:
        cursor.execute("DELETE FROM eval_samples WHERE split IN ('dev', 'test')")
        for row in rows:
            cursor.execute(
                """
                INSERT INTO eval_samples (
                    sample_id,
                    split,
                    user_query_ko,
                    dialog_context,
                    expected_doc_ids,
                    expected_chunk_ids,
                    expected_answer_key_points,
                    query_category,
                    difficulty,
                    single_or_multi_chunk,
                    source_product,
                    source_version_if_available,
                    metadata
                ) VALUES (
                    %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s
                )
                """,
                (
                    row["sample_id"],
                    row["split"],
                    row["user_query_ko"],
                    Jsonb(row["dialog_context"]),
                    Jsonb(row["expected_doc_ids"]),
                    Jsonb(row["expected_chunk_ids"]),
                    Jsonb(row["expected_answer_key_points"]),
                    row["query_category"],
                    row["difficulty"],
                    row["single_or_multi_chunk"],
                    row["source_product"],
                    row["source_version_if_available"],
                    Jsonb({"builder": "build-eval-dataset"}),
                ),
            )


def _write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as target:
        for row in rows:
            target.write(json.dumps(row, ensure_ascii=False) + "\n")


def run_eval_dataset_builder(
    *,
    experiment: str,
    experiment_root: Path = Path("configs/experiments"),
    output_root: Path = Path("data/eval"),
    database_url: str | None = None,
    db_host: str = "localhost",
    db_port: int = 5432,
    db_name: str = "query_forge",
    db_user: str = "query_forge",
    db_password: str = "query_forge",
) -> dict[str, Any]:
    config = load_experiment_config(experiment, experiment_root=experiment_root)
    options = type(
        "DbOptions",
        (),
        {
            "database_url": database_url,
            "host": db_host,
            "port": db_port,
            "database": db_name,
            "user": db_user,
            "password": db_password,
        },
    )()
    connection = connect(options, autocommit=False)
    try:
        recorder = ExperimentRunRecorder(connection)
        run_context = recorder.start_run(
            experiment_key=config.experiment_key,
            category=config.category,
            description=config.description,
            config_path=str(config.config_path),
            config_hash=config.config_hash,
            parameters={"stage": "build-eval-dataset"},
            run_label="build-eval-dataset",
        )

        rng = random.Random(config.random_seed + 31)
        candidates = _load_candidates(connection)
        dev_families, test_families = _split_families(candidates, rng)
        dev_samples = _allocate_samples(
            candidates,
            split="dev",
            families=dev_families,
            targets=SPLIT_TARGETS["dev"],
            rng=rng,
        )
        test_samples = _allocate_samples(
            candidates,
            split="test",
            families=test_families,
            targets=SPLIT_TARGETS["test"],
            rng=rng,
        )
        dev_rows = [_to_eval_row(sample, split="dev", index=index + 1) for index, sample in enumerate(dev_samples)]
        test_rows = [_to_eval_row(sample, split="test", index=index + 1) for index, sample in enumerate(test_samples)]
        all_rows = dev_rows + test_rows
        _persist_eval_samples(connection, all_rows)

        dev_path = output_root / "human_eval_dev.jsonl"
        test_path = output_root / "human_eval_test.jsonl"
        _write_jsonl(dev_path, dev_rows)
        _write_jsonl(test_path, test_rows)

        summary = {
            "experiment_key": config.experiment_key,
            "experiment_run_id": run_context.experiment_run_id,
            "candidate_pool_size": len(candidates),
            "dev_count": len(dev_rows),
            "test_count": len(test_rows),
            "dev_file": str(dev_path),
            "test_file": str(test_path),
            "dev_category_distribution": {
                category: sum(1 for row in dev_rows if row["query_category"] == category)
                for category in SPLIT_TARGETS["dev"]
            },
            "test_category_distribution": {
                category: sum(1 for row in test_rows if row["query_category"] == category)
                for category in SPLIT_TARGETS["test"]
            },
            "dev_family_count": len(dev_families),
            "test_family_count": len(test_families),
        }
        recorder.finish_run(run_context, status="completed", metrics=summary)
        connection.commit()
        return summary
    except Exception as exception:  # noqa: BLE001
        connection.rollback()
        LOGGER.exception("Eval dataset build failed.")
        raise exception
    finally:
        connection.close()


def run_eval_dataset_builder_from_env(experiment: str) -> dict[str, Any]:
    defaults = default_database_args()
    return run_eval_dataset_builder(
        experiment=experiment,
        database_url=defaults["database_url"],
        db_host=defaults["db_host"],
        db_port=defaults["db_port"],
        db_name=defaults["db_name"],
        db_user=defaults["db_user"],
        db_password=defaults["db_password"],
    )
