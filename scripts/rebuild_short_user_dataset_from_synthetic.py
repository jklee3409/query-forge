from __future__ import annotations

import argparse
import json
import random
import re
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import psycopg
from psycopg.types.json import Jsonb


SPACE_RE = re.compile(r"\s+")
SENTENCE_SPLIT_RE = re.compile(r"(?<=[.!?])\s+")
TERM_RE = re.compile(r"@[A-Za-z][A-Za-z0-9_]+|[A-Za-z][A-Za-z0-9_.:/-]{1,}|[가-힣]{2,}")

KOREAN_STOPWORDS = {
    "무엇",
    "뭔가",
    "무슨",
    "방법",
    "설정",
    "어떻게",
    "왜",
    "언제",
    "가능",
    "가이드",
    "설명",
    "예시",
    "적용",
    "차이",
    "비교",
    "사용",
    "요청",
    "질문",
    "기본",
    "동작",
    "원리",
    "주의",
    "포인트",
    "체크",
    "에서",
    "으로",
    "로",
    "와",
    "과",
    "및",
    "또는",
    "관련",
    "대한",
    "대해",
    "하는",
    "되는",
    "있나요",
    "인가요",
    "어떤",
    "경우",
    "사용할",
    "하려면",
    "할때",
    "할",
    "때",
    "이",
    "그",
    "저",
    "수",
    "좀",
    "요약",
}

EN_STOPWORDS = {
    "what",
    "how",
    "when",
    "why",
    "which",
    "difference",
    "compare",
    "comparison",
    "example",
    "examples",
    "guide",
    "usage",
    "use",
    "basic",
    "default",
    "setting",
    "settings",
    "configure",
    "configuration",
    "spring",
    "framework",
    "boot",
    "data",
    "from",
    "with",
    "for",
    "into",
    "about",
    "method",
    "methods",
    "service",
    "services",
    "using",
    "based",
    "define",
    "defined",
    "support",
    "supported",
    "provide",
    "provided",
}

HOWTO_TYPES = {"procedure", "configuration", "troubleshooting", "reason", "action", "setup"}
COMPARE_TYPES = {"comparison"}
DEFINITION_TYPES = {"definition", "fact", "overview"}


@dataclass(frozen=True)
class SyntheticCandidate:
    synthetic_query_id: str
    query_text: str
    query_type: str
    answerability_type: str
    generation_strategy: str
    target_doc_id: str | None
    target_chunk_ids: list[str]


@dataclass(frozen=True)
class EvalItem:
    row: dict[str, Any]
    metadata: dict[str, Any]


def _normalize_spaces(text: str) -> str:
    return SPACE_RE.sub(" ", (text or "").strip())


def _normalize_key(text: str) -> str:
    return _normalize_spaces(text).lower()


def _trim_term(term: str) -> str:
    cleaned = term.strip().strip("`\"'()[]{}<>.,;:")
    if not cleaned:
        return ""
    if cleaned.isdigit():
        return ""
    if len(cleaned) <= 1:
        return ""
    lowered = cleaned.lower()
    if cleaned.startswith("@"):
        return cleaned
    if all("\uac00" <= ch <= "\ud7a3" for ch in cleaned):
        if lowered in KOREAN_STOPWORDS:
            return ""
        return cleaned
    if lowered in EN_STOPWORDS:
        return ""
    return cleaned


def _extract_terms(query_text: str, *, max_terms: int = 4) -> list[str]:
    seen: set[str] = set()
    picked: list[str] = []
    for raw in TERM_RE.findall(query_text or ""):
        term = _trim_term(raw)
        if not term:
            continue
        key = term.lower()
        if key in seen:
            continue
        seen.add(key)
        picked.append(term)
        if len(picked) >= max_terms:
            break
    return picked


def _finalize_query(text: str) -> str:
    normalized = _normalize_spaces(text)
    normalized = normalized.rstrip(".!?,")
    if not normalized.endswith("?"):
        normalized += "?"
    return normalized


def _make_query_candidates(candidate: SyntheticCandidate, terms: list[str]) -> list[str]:
    t1 = terms[0] if terms else "이거"
    t2 = terms[1] if len(terms) > 1 else ""
    query_type = (candidate.query_type or "").lower()

    rows: list[str] = []
    if query_type in COMPARE_TYPES and t2:
        rows.extend(
            [
                f"{t1} {t2} 차이 뭐임",
                f"{t1} vs {t2} 뭐가 맞음",
                f"{t1} {t2} 헷갈리는데 한줄 정리",
            ]
        )
    elif query_type in HOWTO_TYPES:
        rows.extend(
            [
                f"{t1} 설정 어케함",
                f"{t1} 적용 순서 뭐임",
                f"{t1} 보통 어케씀",
                f"{t1} 실무에서 보통 어케씀",
            ]
        )
    elif query_type in DEFINITION_TYPES:
        rows.extend(
            [
                f"{t1} 뭐임",
                f"{t1} 언제씀",
                f"{t1} 핵심만",
            ]
        )
    else:
        rows.extend(
            [
                f"{t1} 뭐임",
                f"{t1} 어케씀",
                f"{t1} 핵심만",
            ]
        )

    if t2:
        rows.extend(
            [
                f"{t1} {t2} 같이 쓸때 포인트",
                f"{t1} {t2} 같이 쓰는 예시",
            ]
        )
    if candidate.answerability_type == "multi" and t2:
        rows.append(f"{t1} {t2} 순서 어케됨")
    rows.append(f"{t1} 빠르게 요약")
    return [_finalize_query(row) for row in rows]


def _compress_query(
    candidate: SyntheticCandidate,
    *,
    rng: random.Random,
    used_query_keys: set[str],
) -> str:
    terms = _extract_terms(candidate.query_text)
    candidates = _make_query_candidates(candidate, terms)
    if len(candidates) > 1:
        pivot = rng.randrange(0, len(candidates))
        candidates = candidates[pivot:] + candidates[:pivot]

    for query in candidates:
        if len(query) < 4 or len(query) > 64:
            continue
        key = _normalize_key(query)
        if key in used_query_keys:
            continue
        used_query_keys.add(key)
        return query

    fallback_base = terms[0] if terms else "이거"
    for suffix in ("뭐임?", "어케함?", "핵심만?"):
        query = _finalize_query(f"{fallback_base} {suffix}")
        key = _normalize_key(query)
        if key in used_query_keys:
            continue
        used_query_keys.add(key)
        return query
    return ""


def _first_sentence(text: str, *, max_len: int = 260) -> str:
    normalized = _normalize_spaces(text)
    if not normalized:
        return ""
    first = SENTENCE_SPLIT_RE.split(normalized)[0].strip()
    if len(first) > max_len:
        first = first[:max_len].rstrip() + "..."
    return first


def _connect(db_host: str, db_port: int, db_name: str, db_user: str, db_password: str) -> psycopg.Connection[Any]:
    return psycopg.connect(
        host=db_host,
        port=db_port,
        dbname=db_name,
        user=db_user,
        password=db_password,
    )


def _fetch_dataset_meta(connection: psycopg.Connection[Any], dataset_id: str) -> tuple[str, str]:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT dataset_key, dataset_name
            FROM eval_dataset
            WHERE dataset_id = %s
            """,
            (dataset_id,),
        )
        row = cursor.fetchone()
    if not row:
        raise RuntimeError(f"dataset not found: {dataset_id}")
    return str(row[0]), str(row[1])


def _fetch_synthetic_candidates(connection: psycopg.Connection[Any]) -> list[SyntheticCandidate]:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT synthetic_query_id::text,
                   query_text,
                   COALESCE(query_type, ''),
                   COALESCE(answerability_type, ''),
                   COALESCE(generation_strategy, ''),
                   target_doc_id::text,
                   target_chunk_ids
            FROM synthetic_queries_raw_all
            WHERE query_text IS NOT NULL
              AND jsonb_typeof(target_chunk_ids) = 'array'
              AND jsonb_array_length(target_chunk_ids) > 0
            """
        )
        rows = cursor.fetchall()
    return [
        SyntheticCandidate(
            synthetic_query_id=str(row[0]),
            query_text=str(row[1]),
            query_type=str(row[2] or ""),
            answerability_type=str(row[3] or ""),
            generation_strategy=str(row[4] or ""),
            target_doc_id=str(row[5]) if row[5] else None,
            target_chunk_ids=[str(chunk_id) for chunk_id in (row[6] or []) if chunk_id],
        )
        for row in rows
        if row[6]
    ]


def _fetch_chunk_map(
    connection: psycopg.Connection[Any],
    *,
    chunk_ids: list[str],
) -> dict[str, tuple[str, str, str | None, str | None]]:
    if not chunk_ids:
        return {}
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT c.chunk_id,
                   c.document_id,
                   COALESCE(c.chunk_text, ''),
                   d.source_id,
                   d.version_label
            FROM corpus_chunks c
            JOIN corpus_documents d ON d.document_id = c.document_id
            WHERE c.chunk_id = ANY(%s)
              AND d.is_active = TRUE
            """,
            (chunk_ids,),
        )
        rows = cursor.fetchall()
    return {
        str(row[0]): (str(row[1]), str(row[2] or ""), row[3], row[4])
        for row in rows
    }


def _build_eval_items(
    *,
    candidates: list[SyntheticCandidate],
    chunk_map: dict[str, tuple[str, str, str | None, str | None]],
    target_total: int,
    seed: int,
    dataset_key: str,
) -> tuple[list[EvalItem], dict[str, Any]]:
    if target_total <= 0:
        raise ValueError("target_total must be positive")

    rng = random.Random(seed)
    shuffled = list(candidates)
    rng.shuffle(shuffled)
    used_queries: set[str] = set()
    items: list[EvalItem] = []
    strategy_counts: dict[str, int] = {}

    for candidate in shuffled:
        if len(items) >= target_total:
            break

        chunk_ids = [chunk_id for chunk_id in candidate.target_chunk_ids if chunk_id in chunk_map]
        if not chunk_ids:
            continue
        if len(chunk_ids) != len(candidate.target_chunk_ids):
            continue

        compressed_query = _compress_query(candidate, rng=rng, used_query_keys=used_queries)
        if not compressed_query:
            continue

        expected_doc_ids: list[str] = []
        if candidate.target_doc_id:
            expected_doc_ids.append(candidate.target_doc_id)

        expected_key_points: list[str] = []
        source_product: str | None = None
        source_version: str | None = None
        for chunk_id in chunk_ids:
            doc_id, chunk_text, product, version = chunk_map[chunk_id]
            if doc_id not in expected_doc_ids:
                expected_doc_ids.append(doc_id)
            if source_product is None and product:
                source_product = str(product)
            if source_version is None and version:
                source_version = str(version)
            key_point = _first_sentence(chunk_text)
            if key_point:
                expected_key_points.append(key_point)

        if not expected_key_points:
            continue

        single_or_multi = "multi" if len(chunk_ids) > 1 else "single"
        difficulty = "hard" if single_or_multi == "multi" else "medium"
        row = {
            "sample_id": "",
            "split": "test",
            "user_query_ko": compressed_query,
            "dialog_context": {},
            "expected_doc_ids": expected_doc_ids,
            "expected_chunk_ids": chunk_ids,
            "expected_answer_key_points": expected_key_points,
            "query_category": "short_user",
            "difficulty": difficulty,
            "single_or_multi_chunk": single_or_multi,
            "source_product": source_product,
            "source_version_if_available": source_version,
        }
        metadata = {
            "dataset_key": dataset_key,
            "query_style": "short_user",
            "generation_mode": "synthetic_random_compressed_query",
            "source_synthetic_query_id": candidate.synthetic_query_id,
            "source_query_type": candidate.query_type,
            "source_generation_strategy": candidate.generation_strategy,
            "target_method": candidate.generation_strategy,
            "updated_at": datetime.now(timezone.utc).isoformat(),
        }
        items.append(EvalItem(row=row, metadata=metadata))
        strategy = candidate.generation_strategy or "unknown"
        strategy_counts[strategy] = strategy_counts.get(strategy, 0) + 1

    if len(items) < target_total:
        raise RuntimeError(
            f"failed to sample enough rows from synthetic candidates: requested={target_total}, built={len(items)}"
        )

    for index, item in enumerate(items[:target_total], start=1):
        item.row["sample_id"] = f"test-short-user-{index:03d}"

    single_count = sum(1 for item in items[:target_total] if item.row["single_or_multi_chunk"] == "single")
    multi_count = target_total - single_count
    summary = {
        "selected_count": target_total,
        "single_count": single_count,
        "multi_count": multi_count,
        "strategy_distribution": dict(sorted(strategy_counts.items())),
        "preview": [
            {
                "sample_id": item.row["sample_id"],
                "user_query_ko": item.row["user_query_ko"],
                "source_synthetic_query_id": item.metadata["source_synthetic_query_id"],
                "source_generation_strategy": item.metadata["source_generation_strategy"],
                "expected_chunk_ids": item.row["expected_chunk_ids"],
            }
            for item in items[:12]
        ],
    }
    return items[:target_total], summary


def _write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as target:
        for row in rows:
            target.write(json.dumps(row, ensure_ascii=False) + "\n")


def _upsert_eval_samples(connection: psycopg.Connection[Any], items: list[EvalItem]) -> None:
    with connection.cursor() as cursor:
        for item in items:
            row = item.row
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
                ON CONFLICT (sample_id) DO UPDATE
                SET split = EXCLUDED.split,
                    user_query_ko = EXCLUDED.user_query_ko,
                    dialog_context = EXCLUDED.dialog_context,
                    expected_doc_ids = EXCLUDED.expected_doc_ids,
                    expected_chunk_ids = EXCLUDED.expected_chunk_ids,
                    expected_answer_key_points = EXCLUDED.expected_answer_key_points,
                    query_category = EXCLUDED.query_category,
                    difficulty = EXCLUDED.difficulty,
                    single_or_multi_chunk = EXCLUDED.single_or_multi_chunk,
                    source_product = EXCLUDED.source_product,
                    source_version_if_available = EXCLUDED.source_version_if_available,
                    metadata = EXCLUDED.metadata
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
                    Jsonb(item.metadata),
                ),
            )


def _refresh_dataset_items(connection: psycopg.Connection[Any], dataset_id: str, items: list[EvalItem]) -> None:
    with connection.cursor() as cursor:
        cursor.execute("DELETE FROM eval_dataset_item WHERE dataset_id = %s", (dataset_id,))
        for item in items:
            row = item.row
            cursor.execute(
                """
                INSERT INTO eval_dataset_item (
                    dataset_id,
                    sample_id,
                    query_category,
                    single_or_multi_chunk,
                    active
                ) VALUES (%s, %s, %s, %s, TRUE)
                """,
                (
                    dataset_id,
                    row["sample_id"],
                    row["query_category"],
                    row["single_or_multi_chunk"],
                ),
            )


def _update_dataset_meta(
    connection: psycopg.Connection[Any],
    *,
    dataset_id: str,
    total_items: int,
    single_count: int,
    multi_count: int,
    source_file: str,
    seed: int,
) -> None:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            UPDATE eval_dataset
            SET dataset_name = %s,
                description = %s,
                version = %s,
                total_items = %s,
                category_distribution = %s,
                single_multi_distribution = %s,
                metadata = %s,
                updated_at = NOW()
            WHERE dataset_id = %s
            """,
            (
                "짧은 사용자 질의 평가 데이터셋 (80문항)",
                "현재 synthetic_queries_raw_all 후보를 랜덤 샘플링해 한국어 짧은/압축 질의 스타일로 재구성한 retrieval-aware 평가셋 (80문항)",
                "v4-2026-04-19",
                total_items,
                Jsonb({"short_user": total_items}),
                Jsonb({"single": single_count, "multi": multi_count}),
                Jsonb(
                    {
                        "regenerated_at": datetime.now(timezone.utc).isoformat(),
                        "generation_mode": "synthetic_random_compressed_query",
                        "source_file": source_file,
                        "seed": seed,
                    }
                ),
                dataset_id,
            ),
        )


def run(
    *,
    dataset_id: str,
    output_file: Path,
    report_file: Path,
    target_total: int,
    seed: int,
    db_host: str,
    db_port: int,
    db_name: str,
    db_user: str,
    db_password: str,
) -> dict[str, Any]:
    connection = _connect(db_host, db_port, db_name, db_user, db_password)
    try:
        dataset_key, dataset_name = _fetch_dataset_meta(connection, dataset_id)
        synthetic_candidates = _fetch_synthetic_candidates(connection)
        all_chunk_ids = sorted(
            {
                chunk_id
                for candidate in synthetic_candidates
                for chunk_id in candidate.target_chunk_ids
            }
        )
        chunk_map = _fetch_chunk_map(connection, chunk_ids=all_chunk_ids)
        eval_items, selection_summary = _build_eval_items(
            candidates=synthetic_candidates,
            chunk_map=chunk_map,
            target_total=target_total,
            seed=seed,
            dataset_key=dataset_key,
        )

        eval_rows = [item.row for item in eval_items]
        _write_jsonl(output_file, eval_rows)
        _upsert_eval_samples(connection, eval_items)
        _refresh_dataset_items(connection, dataset_id=dataset_id, items=eval_items)

        single_count = sum(1 for row in eval_rows if row["single_or_multi_chunk"] == "single")
        multi_count = len(eval_rows) - single_count
        _update_dataset_meta(
            connection,
            dataset_id=dataset_id,
            total_items=len(eval_rows),
            single_count=single_count,
            multi_count=multi_count,
            source_file=str(output_file).replace("/", "\\"),
            seed=seed,
        )
        connection.commit()

        summary = {
            "dataset_id": dataset_id,
            "dataset_name_before": dataset_name,
            "generation_mode": "synthetic_random_compressed_query",
            "candidate_pool_size": len(synthetic_candidates),
            "chunk_map_size": len(chunk_map),
            "target_total": target_total,
            "selected_summary": selection_summary,
            "output_file": str(output_file),
            "report_file": str(report_file),
            "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        }
        report_file.parent.mkdir(parents=True, exist_ok=True)
        report_file.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
        return summary
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Rebuild short-user eval dataset from random synthetic query candidates."
    )
    parser.add_argument("--dataset-id", default="b2d47254-8655-4c9c-81ac-7615677ec5bd")
    parser.add_argument("--output-file", default="data/eval/human_eval_short_user_test_80.jsonl")
    parser.add_argument(
        "--report-file",
        default="data/reports/short_user_dataset_80_synthetic_compressed_2026-04-19.json",
    )
    parser.add_argument("--target-total", type=int, default=80)
    parser.add_argument("--seed", type=int, default=20260419)
    parser.add_argument("--db-host", default="localhost")
    parser.add_argument("--db-port", type=int, default=5432)
    parser.add_argument("--db-name", default="query_forge")
    parser.add_argument("--db-user", default="query_forge")
    parser.add_argument("--db-password", default="query_forge")
    args = parser.parse_args()

    summary = run(
        dataset_id=args.dataset_id,
        output_file=Path(args.output_file),
        report_file=Path(args.report_file),
        target_total=args.target_total,
        seed=args.seed,
        db_host=args.db_host,
        db_port=args.db_port,
        db_name=args.db_name,
        db_user=args.db_user,
        db_password=args.db_password,
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
