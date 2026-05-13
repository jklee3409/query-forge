from __future__ import annotations

import argparse
import json
import re
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import psycopg


REPO_ROOT = Path(__file__).resolve().parents[1]
DATASET_ID_DEFAULT = "b2d47254-8655-4c9c-81ac-7615677ec5bd"
OUTPUT_DEFAULT = REPO_ROOT / "data" / "reports" / "short_user_dataset_80_audit_2026-05-13.json"

IDENTIFIER_RE = re.compile(
    r"@[A-Za-z][A-Za-z0-9_]+|"
    r"[A-Za-z][A-Za-z0-9_.:/-]{2,}|"
    r"[가-힣]{2,}"
)
GENERIC_QUERY_MARKERS = ("보통", "뭐임", "요약", "포인트", "어케", "언제", "어디", "정리", "가이드")
COMPARE_MARKERS = (" vs ", "차이", "뭐가 맞", "비교")
GENERIC_TECH_TERMS = {
    "spring",
    "security",
    "framework",
    "boot",
    "data",
    "jpa",
    "http",
    "https",
    "web",
    "mvc",
    "webflux",
    "aop",
    "oauth2",
    "repository",
    "environment",
    "kotlin",
    "java",
}
KOREAN_STOPWORDS = {
    "보통",
    "뭐임",
    "요약",
    "포인트",
    "어케",
    "언제",
    "어디",
    "정리",
    "가이드",
    "사용",
    "설정",
    "순서",
    "차이",
    "비교",
    "방법",
    "설명",
    "기본",
    "동작",
    "예시",
    "같이",
    "쓸때",
}


@dataclass(frozen=True)
class SampleRow:
    sample_id: str
    user_query_ko: str
    expected_doc_ids: list[str]
    expected_chunk_ids: list[str]
    expected_answer_key_points: list[str]
    source_product: str | None
    source_version_if_available: str | None
    query_category: str
    difficulty: str
    single_or_multi_chunk: str
    metadata: dict[str, Any]
    source_synthetic: dict[str, Any] | None
    chunks: list[dict[str, Any]]


def _normalize(text: str) -> str:
    return " ".join((text or "").strip().split())


def _extract_terms(text: str) -> list[str]:
    seen: set[str] = set()
    terms: list[str] = []
    for token in IDENTIFIER_RE.findall(text or ""):
        cleaned = token.strip("`\"'()[]{}<>.,;:")
        if len(cleaned) < 2:
            continue
        lowered = cleaned.lower()
        if lowered in KOREAN_STOPWORDS:
            continue
        if lowered in {"section", "path", "overlap", "context", "previous", "chunk"}:
            continue
        if lowered in seen:
            continue
        seen.add(lowered)
        terms.append(cleaned)
    return terms


def _specific_terms(terms: list[str]) -> list[str]:
    picked: list[str] = []
    for term in terms:
        lowered = term.lower()
        if lowered in GENERIC_TECH_TERMS:
            continue
        picked.append(term)
    return picked


def _contains_any(text: str, markers: tuple[str, ...]) -> bool:
    normalized = _normalize(text).lower()
    return any(marker.lower() in normalized for marker in markers)


def _term_overlap(left: list[str], right_text: str) -> float:
    left_norm = {item.lower() for item in left if item}
    if not left_norm:
        return 0.0
    right_norm = _normalize(right_text).lower()
    matched = sum(1 for item in left_norm if item in right_norm)
    return matched / len(left_norm)


def _fetch_dataset_rows(connection: psycopg.Connection[Any], dataset_id: str) -> tuple[dict[str, Any], list[SampleRow]]:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT dataset_id::text,
                   dataset_key,
                   dataset_name,
                   version,
                   total_items,
                   COALESCE(metadata, '{}'::jsonb)
            FROM eval_dataset
            WHERE dataset_id = %s
            """,
            (dataset_id,),
        )
        dataset_meta = cursor.fetchone()
        if not dataset_meta:
            raise RuntimeError(f"dataset not found: {dataset_id}")

        cursor.execute(
            """
            SELECT s.sample_id,
                   s.user_query_ko,
                   s.expected_doc_ids,
                   s.expected_chunk_ids,
                   s.expected_answer_key_points,
                   s.source_product,
                   s.source_version_if_available,
                   s.query_category,
                   s.difficulty,
                   s.single_or_multi_chunk,
                   COALESCE(s.metadata, '{}'::jsonb)
            FROM eval_dataset_item i
            JOIN eval_samples s ON s.sample_id = i.sample_id
            WHERE i.dataset_id = %s
              AND i.active = TRUE
            ORDER BY s.sample_id
            """,
            (dataset_id,),
        )
        sample_rows = cursor.fetchall()

        source_ids = [
            row[10].get("source_synthetic_query_id")
            for row in sample_rows
            if isinstance(row[10], dict) and row[10].get("source_synthetic_query_id")
        ]
        synthetic_map: dict[str, dict[str, Any]] = {}
        if source_ids:
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
                WHERE synthetic_query_id = ANY(%s)
                """,
                (source_ids,),
            )
            for row in cursor.fetchall():
                synthetic_map[str(row[0])] = {
                    "synthetic_query_id": str(row[0]),
                    "query_text": str(row[1] or ""),
                    "query_type": str(row[2] or ""),
                    "answerability_type": str(row[3] or ""),
                    "generation_strategy": str(row[4] or ""),
                    "target_doc_id": str(row[5]) if row[5] else None,
                    "target_chunk_ids": [str(item) for item in (row[6] or []) if str(item).strip()],
                }

        chunk_ids = sorted(
            {
                str(chunk_id)
                for row in sample_rows
                for chunk_id in (row[3] or [])
                if str(chunk_id).strip()
            }
        )
        chunk_map: dict[str, dict[str, Any]] = {}
        if chunk_ids:
            cursor.execute(
                """
                SELECT c.chunk_id,
                       c.document_id,
                       c.chunk_index_in_document,
                       COALESCE(c.section_path_text, ''),
                       COALESCE(c.chunk_text, ''),
                       COALESCE(d.title, ''),
                       COALESCE(d.source_id, ''),
                       COALESCE(d.version_label, '')
                FROM corpus_chunks c
                JOIN corpus_documents d ON d.document_id = c.document_id
                WHERE c.chunk_id = ANY(%s)
                """,
                (chunk_ids,),
            )
            for row in cursor.fetchall():
                chunk_map[str(row[0])] = {
                    "chunk_id": str(row[0]),
                    "document_id": str(row[1]),
                    "chunk_index_in_document": int(row[2] or 0),
                    "section_path_text": str(row[3] or ""),
                    "chunk_text": str(row[4] or ""),
                    "title": str(row[5] or ""),
                    "source_id": str(row[6] or ""),
                    "version_label": str(row[7] or ""),
                }

    dataset = {
        "dataset_id": str(dataset_meta[0]),
        "dataset_key": str(dataset_meta[1] or ""),
        "dataset_name": str(dataset_meta[2] or ""),
        "version": str(dataset_meta[3] or ""),
        "total_items": int(dataset_meta[4] or 0),
        "metadata": dataset_meta[5] or {},
    }
    samples: list[SampleRow] = []
    for row in sample_rows:
        metadata = row[10] or {}
        source_synthetic_id = metadata.get("source_synthetic_query_id") if isinstance(metadata, dict) else None
        chunks = [
            chunk_map[str(chunk_id)]
            for chunk_id in (row[3] or [])
            if str(chunk_id) in chunk_map
        ]
        chunks.sort(key=lambda item: (item["document_id"], item["chunk_index_in_document"]))
        samples.append(
            SampleRow(
                sample_id=str(row[0]),
                user_query_ko=str(row[1] or ""),
                expected_doc_ids=[str(item) for item in (row[2] or []) if str(item).strip()],
                expected_chunk_ids=[str(item) for item in (row[3] or []) if str(item).strip()],
                expected_answer_key_points=[str(item) for item in (row[4] or []) if str(item).strip()],
                source_product=str(row[5]).strip() if row[5] else None,
                source_version_if_available=str(row[6]).strip() if row[6] else None,
                query_category=str(row[7] or ""),
                difficulty=str(row[8] or ""),
                single_or_multi_chunk=str(row[9] or ""),
                metadata=metadata,
                source_synthetic=synthetic_map.get(str(source_synthetic_id)) if source_synthetic_id else None,
                chunks=chunks,
            )
        )
    return dataset, samples


def _classify_sample(sample: SampleRow) -> dict[str, Any]:
    flags: list[dict[str, str]] = []
    query_terms = _extract_terms(sample.user_query_ko)
    query_specific_terms = _specific_terms(query_terms)
    chunk_sections = " | ".join(chunk["section_path_text"] for chunk in sample.chunks)
    chunk_text_blob = "\n".join(chunk["chunk_text"] for chunk in sample.chunks)
    source_query = (sample.source_synthetic or {}).get("query_text", "")
    source_terms = _extract_terms(source_query)
    source_specific_terms = _specific_terms(source_terms)
    source_query_type = str((sample.source_synthetic or {}).get("query_type", "")).lower()
    query_overlap_with_chunk = _term_overlap(query_specific_terms or query_terms, chunk_text_blob)
    source_overlap_with_query = _term_overlap(source_specific_terms, sample.user_query_ko)

    if _contains_any(sample.user_query_ko, GENERIC_QUERY_MARKERS) and len(query_specific_terms) <= 1:
        flags.append(
            {
                "type": "A",
                "reason": "generic short phrase remains while specific anchor count is 1 or less",
            }
        )
    elif len(source_specific_terms) >= 2 and source_overlap_with_query < 0.5:
        flags.append(
            {
                "type": "A",
                "reason": "compressed query dropped multiple specific anchors present in source synthetic query",
            }
        )

    if any(point.startswith("Overlap context from previous chunk") for point in sample.expected_answer_key_points):
        flags.append(
            {
                "type": "D",
                "reason": "expected_answer_key_points depend on overlap-context text instead of direct section grounding",
            }
        )
    if "Section Summary" in chunk_sections or query_overlap_with_chunk < 0.34:
        flags.append(
            {
                "type": "B",
                "reason": "expected target section is summary-like or query anchor overlap with expected chunk text is weak",
            }
        )

    if _contains_any(sample.user_query_ko, COMPARE_MARKERS):
        if source_query_type != "comparison" and "difference" not in chunk_text_blob.lower() and "versus" not in chunk_text_blob.lower():
            flags.append(
                {
                    "type": "C",
                    "reason": "query asks for comparison/choice but source chunk does not expose a comparison-focused grounding",
                }
            )

    if len(query_specific_terms) == 0:
        flags.append(
            {
                "type": "F",
                "reason": "query is dominated by product-level or generic terms without differentiating anchor",
            }
        )

    if len(query_specific_terms) <= 1 and len(source_specific_terms) >= 1:
        flags.append(
            {
                "type": "E",
                "reason": "query is too generic to isolate rewrite benefit from plain retrieval miss",
            }
        )

    unique_flag_types = []
    seen_types: set[str] = set()
    for flag in flags:
        if flag["type"] in seen_types:
            continue
        seen_types.add(flag["type"])
        unique_flag_types.append(flag)

    return {
        "sample_id": sample.sample_id,
        "user_query_ko": sample.user_query_ko,
        "source_query_type": source_query_type,
        "source_generation_strategy": (sample.source_synthetic or {}).get("generation_strategy"),
        "source_synthetic_query_id": sample.metadata.get("source_synthetic_query_id"),
        "source_synthetic_query_text": source_query,
        "expected_chunk_ids": sample.expected_chunk_ids,
        "expected_doc_ids": sample.expected_doc_ids,
        "section_paths": [chunk["section_path_text"] for chunk in sample.chunks],
        "query_terms": query_terms,
        "query_specific_terms": query_specific_terms,
        "source_specific_terms": source_specific_terms,
        "query_overlap_with_chunk": round(query_overlap_with_chunk, 4),
        "source_overlap_with_query": round(source_overlap_with_query, 4),
        "flags": unique_flag_types,
        "needs_review": bool(unique_flag_types),
    }


def run(
    *,
    dataset_id: str,
    output_file: Path,
    db_host: str,
    db_port: int,
    db_name: str,
    db_user: str,
    db_password: str,
) -> dict[str, Any]:
    with psycopg.connect(
        host=db_host,
        port=db_port,
        dbname=db_name,
        user=db_user,
        password=db_password,
    ) as connection:
        dataset, samples = _fetch_dataset_rows(connection, dataset_id)

    classified = [_classify_sample(sample) for sample in samples]
    flag_counts: Counter[str] = Counter()
    flagged_samples: list[dict[str, Any]] = []
    for row in classified:
        for flag in row["flags"]:
            flag_counts[flag["type"]] += 1
        if row["needs_review"]:
            flagged_samples.append(row)

    summary = {
        "dataset": dataset,
        "sample_count": len(samples),
        "flag_counts": dict(sorted(flag_counts.items())),
        "needs_review_count": len(flagged_samples),
        "flagged_samples": flagged_samples,
    }
    output_file.parent.mkdir(parents=True, exist_ok=True)
    output_file.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    return summary


def main() -> int:
    parser = argparse.ArgumentParser(description="Audit the current short-user dataset with provenance-aware heuristics.")
    parser.add_argument("--dataset-id", default=DATASET_ID_DEFAULT)
    parser.add_argument("--output-file", default=str(OUTPUT_DEFAULT))
    parser.add_argument("--db-host", default="localhost")
    parser.add_argument("--db-port", type=int, default=5432)
    parser.add_argument("--db-name", default="query_forge")
    parser.add_argument("--db-user", default="query_forge")
    parser.add_argument("--db-password", default="query_forge")
    args = parser.parse_args()

    summary = run(
        dataset_id=args.dataset_id,
        output_file=Path(args.output_file),
        db_host=args.db_host,
        db_port=args.db_port,
        db_name=args.db_name,
        db_user=args.db_user,
        db_password=args.db_password,
    )
    print(json.dumps(
        {
            "dataset_id": summary["dataset"]["dataset_id"],
            "sample_count": summary["sample_count"],
            "flag_counts": summary["flag_counts"],
            "needs_review_count": summary["needs_review_count"],
            "output_file": str(Path(args.output_file)),
        },
        ensure_ascii=False,
        indent=2,
    ))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
