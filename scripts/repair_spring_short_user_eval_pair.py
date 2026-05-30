from __future__ import annotations

import argparse
import json
import re
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import psycopg
from psycopg.types.json import Jsonb


REPO_ROOT = Path(__file__).resolve().parents[1]
KR_DATASET_ID = "b2d47254-8655-4c9c-81ac-7615677ec5bd"
EN_DATASET_ID = "8f0d6e0f-6f9e-4d64-9b07-f4e8ce5ebec0"
KR_SOURCE = REPO_ROOT / "data" / "eval" / "human_eval_short_user_test_80.jsonl"
EN_SOURCE = REPO_ROOT / "data" / "eval" / "human_eval_short_user_test_80_en.jsonl"
REPORT_DEFAULT = REPO_ROOT / "data" / "reports" / "spring_short_user_eval_pair_repair_2026-05-30.json"
VERSION_LABEL = "v6-2026-05-30"

SPACE_RE = re.compile(r"\s+")
SECTION_PREFIX_RE = re.compile(r"^Section Path:\s*[^|.]+[|.]?\s*", re.IGNORECASE)
OVERLAP_PREFIX_RE = re.compile(r"^Overlap context from previous chunk:\s*", re.IGNORECASE)
LATEST_STABLE_RE = re.compile(
    r"(This version is still in development and is not considered stable yet\.\s*)?"
    r"For the latest stable version, please use Spring Data Commons [0-9.]+ !\s*",
    re.IGNORECASE,
)

KR_QUERY_OVERRIDES = {
    "test-short-user-010": "인터페이스 기반 projection?",
    "test-short-user-014": "Spring Data Commons Kotlin 지원?",
    "test-short-user-026": "MockMvc HtmlUnit 통합이 왜 필요함?",
}

EN_QUERY_OVERRIDES = {
    "test-short-user-en-010": "interface-based projection?",
    "test-short-user-en-014": "Spring Data Commons Kotlin support?",
    "test-short-user-en-026": "why integrate MockMvc with HtmlUnit?",
}

CHUNK_OVERRIDES = {
    "test-short-user-010": ["chk_28e501e5cca81f86", "chk_564bc0ad7db79636"],
    "test-short-user-014": ["chk_bdbaa7ee0e1c9b70"],
    "test-short-user-026": ["chk_a12a2c7e6a0f891f", "chk_5e1aedda768e3f0b"],
}

PAIR_SAMPLE_RE = re.compile(r"^test-short-user-(\d{3})$")


def _load_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def _write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.write_text(
        "\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n",
        encoding="utf-8",
    )


def _normalize(text: str) -> str:
    return SPACE_RE.sub(" ", str(text or "").replace("\r", " ").replace("\n", " ")).strip()


def _clean_chunk_text(text: str) -> str:
    lines: list[str] = []
    for raw_line in str(text or "").replace("\r\n", "\n").replace("\r", "\n").split("\n"):
        line = raw_line.strip()
        if not line:
            continue
        line = OVERLAP_PREFIX_RE.sub("", line).strip()
        line = SECTION_PREFIX_RE.sub("", line).strip()
        line = LATEST_STABLE_RE.sub("", line).strip()
        line = line.strip(" |")
        if not line or line in {"```", "```java", "```kotlin", "```xml", "- Java", "- Kotlin"}:
            continue
        lines.append(line)
    cleaned = _normalize(" ".join(lines))
    cleaned = LATEST_STABLE_RE.sub("", cleaned)
    cleaned = cleaned.strip(" |")
    return _normalize(cleaned)


def _snippet(text: str, *, max_chars: int = 620) -> str:
    text = _normalize(text)
    if len(text) <= max_chars:
        return text
    return text[: max_chars - 4].rstrip() + " ..."


def _fetch_chunks(connection: psycopg.Connection[Any], chunk_ids: list[str]) -> dict[str, dict[str, Any]]:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT c.chunk_id,
                   c.document_id,
                   COALESCE(c.section_path_text, ''),
                   COALESCE(c.chunk_text, ''),
                   COALESCE(d.source_id, ''),
                   COALESCE(d.version_label, '')
            FROM corpus_chunks c
            JOIN corpus_documents d
              ON d.document_id = c.document_id
            WHERE c.chunk_id = ANY(%s)
            """,
            (list(dict.fromkeys(chunk_ids)),),
        )
        rows = cursor.fetchall()
    return {
        str(row[0]): {
            "chunk_id": str(row[0]),
            "document_id": str(row[1]),
            "section_path_text": str(row[2] or ""),
            "chunk_text": str(row[3] or ""),
            "source_id": str(row[4] or ""),
            "version_label": str(row[5] or ""),
        }
        for row in rows
    }


def _build_points(chunk_ids: list[str], chunks: dict[str, dict[str, Any]]) -> list[str]:
    points: list[str] = []
    for chunk_id in chunk_ids:
        chunk = chunks[chunk_id]
        cleaned = _clean_chunk_text(chunk["chunk_text"])
        section = chunk["section_path_text"]
        if cleaned:
            points.append(f"Section Path: {section}. {_snippet(cleaned)}")
        else:
            points.append(f"Section Path: {section}.")
    return points


def _paired_en_id(kr_sample_id: str) -> str:
    match = PAIR_SAMPLE_RE.match(kr_sample_id)
    if not match:
        raise RuntimeError(f"unexpected KR sample id: {kr_sample_id}")
    return f"test-short-user-en-{match.group(1)}"


def _repair_kr_rows(
    rows: list[dict[str, Any]],
    chunks: dict[str, dict[str, Any]],
    repaired_at: str,
) -> list[dict[str, Any]]:
    repaired: list[dict[str, Any]] = []
    for row in rows:
        item = dict(row)
        sample_id = str(item["sample_id"])
        chunk_ids = CHUNK_OVERRIDES.get(sample_id, [str(chunk_id) for chunk_id in item["expected_chunk_ids"]])
        if sample_id in KR_QUERY_OVERRIDES:
            item["user_query_ko"] = KR_QUERY_OVERRIDES[sample_id]
        item["expected_chunk_ids"] = chunk_ids
        item["expected_doc_ids"] = list(dict.fromkeys(chunks[chunk_id]["document_id"] for chunk_id in chunk_ids))
        item["expected_answer_key_points"] = _build_points(chunk_ids, chunks)
        item["single_or_multi_chunk"] = "multi" if len(chunk_ids) > 1 else "single"
        item["source_product"] = chunks[chunk_ids[0]]["source_id"]
        item["source_version_if_available"] = chunks[chunk_ids[0]]["version_label"]
        metadata = dict(item.get("metadata") or {})
        metadata.update(
            {
                "dataset_key": "human_eval_short_user_40",
                "query_language": "ko",
                "target_method": "A",
                "repair_mode": "spring_domain_grounding_repair_v1",
                "repaired_at": repaired_at,
                "paired_dataset_id": EN_DATASET_ID,
            }
        )
        if sample_id in CHUNK_OVERRIDES:
            metadata["grounding_repair_reason"] = {
                "test-short-user-010": "interface-based projection needs the interface projection continuation chunk in addition to the projection introduction",
                "test-short-user-014": "version-boilerplate target was replaced with the Spring Data Commons Kotlin Support chunk",
                "test-short-user-026": "HtmlUnit integration why-example spans the section introduction and the continuation chunk",
            }[sample_id]
        item["metadata"] = metadata
        repaired.append(item)
    return repaired


def _repair_en_rows(
    en_rows: list[dict[str, Any]],
    kr_rows: list[dict[str, Any]],
    repaired_at: str,
) -> list[dict[str, Any]]:
    en_by_id = {str(row["sample_id"]): row for row in en_rows}
    repaired: list[dict[str, Any]] = []
    for kr in kr_rows:
        en_id = _paired_en_id(str(kr["sample_id"]))
        if en_id not in en_by_id:
            raise RuntimeError(f"missing EN pair for {kr['sample_id']}: {en_id}")
        source_en = dict(en_by_id[en_id])
        source_en["user_query_en"] = EN_QUERY_OVERRIDES.get(en_id, source_en["user_query_en"])
        source_en["user_query_ko"] = ""
        source_en["query_language"] = "en"
        for field in (
            "expected_doc_ids",
            "expected_chunk_ids",
            "expected_answer_key_points",
            "query_category",
            "difficulty",
            "single_or_multi_chunk",
            "source_product",
            "source_version_if_available",
        ):
            source_en[field] = kr[field]
        metadata = dict(source_en.get("metadata") or {})
        metadata.update(
            {
                "builder": "short-user-en-v3",
                "dataset_key": "human_eval_short_user_80_en",
                "query_language": "en",
                "target_method": "E",
                "paired_sample_id": kr["sample_id"],
                "paired_user_query_ko": kr["user_query_ko"],
                "repair_mode": "paired_to_kr_spring_domain_grounding_repair_v1",
                "repaired_at": repaired_at,
                "paired_dataset_id": KR_DATASET_ID,
            }
        )
        source_en["metadata"] = metadata
        repaired.append(source_en)
    return repaired


def _fetch_dataset_before(connection: psycopg.Connection[Any], dataset_id: str) -> dict[str, Any]:
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
        row = cursor.fetchone()
    if not row:
        raise RuntimeError(f"dataset not found: {dataset_id}")
    return {
        "dataset_id": str(row[0]),
        "dataset_key": str(row[1] or ""),
        "dataset_name": str(row[2] or ""),
        "version": str(row[3] or ""),
        "total_items": int(row[4] or 0),
        "metadata": row[5] or {},
    }


def _fetch_active_samples_before(connection: psycopg.Connection[Any], dataset_id: str) -> list[dict[str, Any]]:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT s.sample_id,
                   s.user_query_ko,
                   s.user_query_en,
                   s.query_language,
                   s.expected_doc_ids,
                   s.expected_chunk_ids,
                   s.expected_answer_key_points
            FROM eval_dataset_item i
            JOIN eval_samples s ON s.sample_id = i.sample_id
            WHERE i.dataset_id = %s
              AND i.active = TRUE
            ORDER BY s.sample_id
            """,
            (dataset_id,),
        )
        rows = cursor.fetchall()
    return [
        {
            "sample_id": str(row[0]),
            "user_query_ko": str(row[1] or ""),
            "user_query_en": str(row[2] or ""),
            "query_language": str(row[3] or ""),
            "expected_doc_ids": row[4] or [],
            "expected_chunk_ids": row[5] or [],
            "expected_answer_key_points": row[6] or [],
        }
        for row in rows
    ]


def _upsert_sample(connection: psycopg.Connection[Any], row: dict[str, Any]) -> None:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            INSERT INTO eval_samples (
                sample_id,
                split,
                user_query_ko,
                user_query_en,
                query_language,
                dialog_context,
                expected_doc_ids,
                expected_chunk_ids,
                expected_answer_key_points,
                query_category,
                difficulty,
                single_or_multi_chunk,
                source_product,
                source_version_if_available,
                metadata,
                domain_id
            ) VALUES (
                %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s,
                (SELECT domain_id FROM eval_dataset WHERE dataset_id = %s)
            )
            ON CONFLICT (sample_id) DO UPDATE
            SET split = EXCLUDED.split,
                user_query_ko = EXCLUDED.user_query_ko,
                user_query_en = EXCLUDED.user_query_en,
                query_language = EXCLUDED.query_language,
                dialog_context = EXCLUDED.dialog_context,
                expected_doc_ids = EXCLUDED.expected_doc_ids,
                expected_chunk_ids = EXCLUDED.expected_chunk_ids,
                expected_answer_key_points = EXCLUDED.expected_answer_key_points,
                query_category = EXCLUDED.query_category,
                difficulty = EXCLUDED.difficulty,
                single_or_multi_chunk = EXCLUDED.single_or_multi_chunk,
                source_product = EXCLUDED.source_product,
                source_version_if_available = EXCLUDED.source_version_if_available,
                metadata = EXCLUDED.metadata,
                domain_id = EXCLUDED.domain_id
            """,
            (
                row["sample_id"],
                row.get("split") or "test",
                row.get("user_query_ko") or "",
                row.get("user_query_en") or None,
                row.get("query_language") or ("en" if row.get("user_query_en") else "ko"),
                Jsonb(row.get("dialog_context") or {}),
                Jsonb(row["expected_doc_ids"]),
                Jsonb(row["expected_chunk_ids"]),
                Jsonb(row["expected_answer_key_points"]),
                row.get("query_category") or "short_user",
                row.get("difficulty") or "medium",
                row.get("single_or_multi_chunk") or ("multi" if len(row["expected_chunk_ids"]) > 1 else "single"),
                row.get("source_product"),
                row.get("source_version_if_available"),
                Jsonb(row.get("metadata") or {}),
                row.get("_dataset_id"),
            ),
        )


def _replace_dataset_items(connection: psycopg.Connection[Any], dataset_id: str, rows: list[dict[str, Any]]) -> None:
    with connection.cursor() as cursor:
        cursor.execute("DELETE FROM eval_dataset_item WHERE dataset_id = %s", (dataset_id,))
        for row in rows:
            cursor.execute(
                """
                INSERT INTO eval_dataset_item (
                    dataset_id,
                    sample_id,
                    query_category,
                    single_or_multi_chunk,
                    active,
                    domain_id
                ) VALUES (%s, %s, %s, %s, TRUE, (SELECT domain_id FROM eval_dataset WHERE dataset_id = %s))
                """,
                (
                    dataset_id,
                    row["sample_id"],
                    row.get("query_category") or "short_user",
                    row.get("single_or_multi_chunk") or ("multi" if len(row["expected_chunk_ids"]) > 1 else "single"),
                    dataset_id,
                ),
            )


def _update_dataset(
    connection: psycopg.Connection[Any],
    *,
    dataset_id: str,
    dataset_name: str,
    version: str,
    query_language: str,
    rows: list[dict[str, Any]],
    metadata_extra: dict[str, Any],
) -> None:
    total = len(rows)
    single = sum(1 for row in rows if row.get("single_or_multi_chunk") == "single")
    multi = sum(1 for row in rows if row.get("single_or_multi_chunk") == "multi")
    with connection.cursor() as cursor:
        cursor.execute(
            "SELECT COALESCE(metadata, '{}'::jsonb) FROM eval_dataset WHERE dataset_id = %s",
            (dataset_id,),
        )
        metadata = dict((cursor.fetchone() or [{}])[0] or {})
        metadata.update(metadata_extra)
        metadata.update(
            {
                "query_language": query_language,
                "version": version,
                "repaired_at": metadata_extra["repaired_at"],
            }
        )
        cursor.execute(
            """
            UPDATE eval_dataset
            SET dataset_name = %s,
                version = %s,
                total_items = %s,
                category_distribution = %s,
                single_multi_distribution = %s,
                metadata = %s,
                updated_at = NOW()
            WHERE dataset_id = %s
            """,
            (
                dataset_name,
                version,
                total,
                Jsonb({"short_user": total}),
                Jsonb({"single": single, "multi": multi}),
                Jsonb(metadata),
                dataset_id,
            ),
        )


def _validate(rows: list[dict[str, Any]], *, language: str) -> dict[str, Any]:
    chunk_refs = sum(len(row["expected_chunk_ids"]) for row in rows)
    boilerplate = 0
    overlap = 0
    empty_query = 0
    for row in rows:
        text = json.dumps(row["expected_answer_key_points"], ensure_ascii=False)
        if "latest stable version" in text or "still in development" in text:
            boilerplate += 1
        if "Overlap context from previous chunk" in text:
            overlap += 1
        if language == "ko" and not str(row.get("user_query_ko") or "").strip():
            empty_query += 1
        if language == "en" and not str(row.get("user_query_en") or "").strip():
            empty_query += 1
    return {
        "sample_count": len(rows),
        "chunk_refs": chunk_refs,
        "single_count": sum(1 for row in rows if row["single_or_multi_chunk"] == "single"),
        "multi_count": sum(1 for row in rows if row["single_or_multi_chunk"] == "multi"),
        "boilerplate_keypoint_count": boilerplate,
        "overlap_keypoint_count": overlap,
        "empty_query_count": empty_query,
        "source_distribution": dict(Counter(str(row.get("source_product") or "") for row in rows)),
    }


def run(args: argparse.Namespace) -> dict[str, Any]:
    repaired_at = datetime.now(timezone.utc).isoformat()
    kr_source_rows = _load_jsonl(Path(args.kr_source))
    en_source_rows = _load_jsonl(Path(args.en_source))
    if len(kr_source_rows) != 80 or len(en_source_rows) != 80:
        raise RuntimeError("expected exactly 80 KR rows and 80 EN rows")

    override_chunk_ids = sorted(
        {
            str(chunk_id)
            for row in kr_source_rows
            for chunk_id in CHUNK_OVERRIDES.get(str(row["sample_id"]), row["expected_chunk_ids"])
        }
    )

    with psycopg.connect(
        host=args.db_host,
        port=args.db_port,
        dbname=args.db_name,
        user=args.db_user,
        password=args.db_password,
        autocommit=False,
    ) as connection:
        kr_before = _fetch_dataset_before(connection, args.kr_dataset_id)
        en_before = _fetch_dataset_before(connection, args.en_dataset_id)
        kr_samples_before = _fetch_active_samples_before(connection, args.kr_dataset_id)
        en_samples_before = _fetch_active_samples_before(connection, args.en_dataset_id)
        chunks = _fetch_chunks(connection, override_chunk_ids)
        missing_chunks = sorted(set(override_chunk_ids) - set(chunks))
        if missing_chunks:
            raise RuntimeError(f"missing chunk ids: {missing_chunks}")

        kr_rows = _repair_kr_rows(kr_source_rows, chunks, repaired_at)
        en_rows = _repair_en_rows(en_source_rows, kr_rows, repaired_at)
        for row in kr_rows:
            row["_dataset_id"] = args.kr_dataset_id
        for row in en_rows:
            row["_dataset_id"] = args.en_dataset_id

        kr_validation = _validate(kr_rows, language="ko")
        en_validation = _validate(en_rows, language="en")
        if kr_validation["boilerplate_keypoint_count"] or kr_validation["overlap_keypoint_count"]:
            raise RuntimeError(f"KR validation failed: {kr_validation}")
        if en_validation["boilerplate_keypoint_count"] or en_validation["overlap_keypoint_count"]:
            raise RuntimeError(f"EN validation failed: {en_validation}")

        if not args.dry_run:
            for row in kr_rows:
                _upsert_sample(connection, row)
            for row in en_rows:
                _upsert_sample(connection, row)
            _replace_dataset_items(connection, args.kr_dataset_id, kr_rows)
            _replace_dataset_items(connection, args.en_dataset_id, en_rows)
            _update_dataset(
                connection,
                dataset_id=args.kr_dataset_id,
                dataset_name="Spring KR Short User Eval 80 (KR, grounded v6)",
                version=VERSION_LABEL,
                query_language="ko",
                rows=kr_rows,
                metadata_extra={
                    "repair_mode": "spring_domain_grounding_repair_v1",
                    "repaired_at": repaired_at,
                    "paired_dataset_id": args.en_dataset_id,
                    "source_file": "data/eval/human_eval_short_user_test_80.jsonl",
                    "repair_report": str(Path(args.report_file).relative_to(REPO_ROOT)).replace("\\", "/"),
                },
            )
            _update_dataset(
                connection,
                dataset_id=args.en_dataset_id,
                dataset_name="Spring Short User Eval 80 (EN, paired grounded v6)",
                version=VERSION_LABEL,
                query_language="en",
                rows=en_rows,
                metadata_extra={
                    "repair_mode": "paired_to_kr_spring_domain_grounding_repair_v1",
                    "repaired_at": repaired_at,
                    "paired_dataset_id": args.kr_dataset_id,
                    "source_file": "data/eval/human_eval_short_user_test_80_en.jsonl",
                    "repair_report": str(Path(args.report_file).relative_to(REPO_ROOT)).replace("\\", "/"),
                },
            )
            connection.commit()
            _write_jsonl(Path(args.kr_source), [{k: v for k, v in row.items() if k != "_dataset_id"} for row in kr_rows])
            _write_jsonl(Path(args.en_source), [{k: v for k, v in row.items() if k != "_dataset_id"} for row in en_rows])
        else:
            connection.rollback()

    report = {
        "repaired_at": repaired_at,
        "dry_run": bool(args.dry_run),
        "version": VERSION_LABEL,
        "datasets_before": {
            "kr": kr_before,
            "en": en_before,
        },
        "active_samples_before_preview": {
            "kr_first": kr_samples_before[:3],
            "en_first": en_samples_before[:3],
            "kr_count": len(kr_samples_before),
            "en_count": len(en_samples_before),
        },
        "active_samples_before": {
            "kr": kr_samples_before,
            "en": en_samples_before,
        },
        "changes": {
            "active_kr_sample_ids_replaced_from": [row["sample_id"] for row in kr_samples_before[:5]],
            "active_kr_sample_ids_replaced_to": [row["sample_id"] for row in kr_rows[:5]],
            "query_overrides": {
                "kr": KR_QUERY_OVERRIDES,
                "en": EN_QUERY_OVERRIDES,
            },
            "chunk_overrides": CHUNK_OVERRIDES,
        },
        "active_samples_after": {
            "kr": [
                {
                    "sample_id": row["sample_id"],
                    "user_query_ko": row["user_query_ko"],
                    "expected_doc_ids": row["expected_doc_ids"],
                    "expected_chunk_ids": row["expected_chunk_ids"],
                    "source_product": row["source_product"],
                }
                for row in kr_rows
            ],
            "en": [
                {
                    "sample_id": row["sample_id"],
                    "paired_sample_id": row["metadata"].get("paired_sample_id"),
                    "user_query_en": row["user_query_en"],
                    "expected_doc_ids": row["expected_doc_ids"],
                    "expected_chunk_ids": row["expected_chunk_ids"],
                    "source_product": row["source_product"],
                }
                for row in en_rows
            ],
        },
        "validation": {
            "kr": kr_validation,
            "en": en_validation,
            "paired_grounding_mismatch_count": sum(
                1
                for kr, en in zip(kr_rows, en_rows, strict=True)
                if kr["expected_doc_ids"] != en["expected_doc_ids"]
                or kr["expected_chunk_ids"] != en["expected_chunk_ids"]
                or kr["expected_answer_key_points"] != en["expected_answer_key_points"]
            ),
        },
        "spring_domain_scope": {
            "allowed_sources": [
                "spring-boot-reference",
                "spring-data-commons-reference",
                "spring-data-jpa-reference",
                "spring-framework-reference",
                "spring-security-reference",
            ],
            "design_notes": [
                "The repaired KR dataset uses anchor-bearing short queries rather than generic prompts.",
                "Expected key points are regenerated from current chunk text after removing overlap and version boilerplate.",
                "EN rows share KR grounding exactly and differ only in English query surface and language metadata.",
            ],
        },
    }
    Path(args.report_file).parent.mkdir(parents=True, exist_ok=True)
    Path(args.report_file).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description="Repair the paired Spring short-user KR/EN eval datasets in place.")
    parser.add_argument("--kr-dataset-id", default=KR_DATASET_ID)
    parser.add_argument("--en-dataset-id", default=EN_DATASET_ID)
    parser.add_argument("--kr-source", default=str(KR_SOURCE))
    parser.add_argument("--en-source", default=str(EN_SOURCE))
    parser.add_argument("--report-file", default=str(REPORT_DEFAULT))
    parser.add_argument("--db-host", default="localhost")
    parser.add_argument("--db-port", type=int, default=5432)
    parser.add_argument("--db-name", default="query_forge")
    parser.add_argument("--db-user", default="query_forge")
    parser.add_argument("--db-password", default="query_forge")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    report = run(args)
    print(
        json.dumps(
            {
                "dry_run": report["dry_run"],
                "version": report["version"],
                "kr_validation": report["validation"]["kr"],
                "en_validation": report["validation"]["en"],
                "paired_grounding_mismatch_count": report["validation"]["paired_grounding_mismatch_count"],
                "report_file": str(args.report_file),
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
