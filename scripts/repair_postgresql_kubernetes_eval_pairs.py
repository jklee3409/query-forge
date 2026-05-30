from __future__ import annotations

import argparse
import json
import re
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import psycopg
from psycopg.types.json import Jsonb


REPO_ROOT = Path(__file__).resolve().parents[1]
REPORT_DEFAULT = REPO_ROOT / "data" / "reports" / "postgresql_kubernetes_eval_pair_repair_2026-05-30.json"

SPACE_RE = re.compile(r"\s+")
OVERLAP_PREFIX_RE = re.compile(r"^Overlap context from previous chunk:\s*", re.IGNORECASE)
SECTION_PREFIX_RE = re.compile(r"^Section Path:\s*[^.]+[.]?\s*", re.IGNORECASE)
CHAPTER_PREFIX_RE = re.compile(r"^(Chapter|Appendix)\s+[A-Z0-9.]+\.\s*", re.IGNORECASE)
HANGUL_RE = re.compile(r"[가-힣]")


@dataclass(frozen=True, slots=True)
class DomainPairConfig:
    name: str
    kr_dataset_id: str
    en_dataset_id: str
    kr_dataset_key: str
    en_dataset_key: str
    kr_dataset_name: str
    en_dataset_name: str
    kr_source: Path
    en_source: Path
    version: str
    kr_repair_mode: str
    en_repair_mode: str
    query_repair: str
    source_product: str


CONFIGS: tuple[DomainPairConfig, ...] = (
    DomainPairConfig(
        name="postgresql",
        kr_dataset_id="862642e6-10bd-538d-9ba8-5de7f1f26d3c",
        en_dataset_id="020a93c4-0465-5655-b681-a5799a98fd15",
        kr_dataset_key="postgresql_kr_short_user_80",
        en_dataset_key="postgresql_en_short_user_80",
        kr_dataset_name="PostgreSQL KR Short User Eval 80 (KR, grounded v2)",
        en_dataset_name="PostgreSQL EN Short User Eval 80 (EN, paired grounded v2)",
        kr_source=REPO_ROOT / "data" / "eval" / "postgresql_kr_short_user_test_80.jsonl",
        en_source=REPO_ROOT / "data" / "eval" / "postgresql_en_short_user_test_80.jsonl",
        version="v2-2026-05-30",
        kr_repair_mode="postgresql_domain_grounding_repair_v1",
        en_repair_mode="paired_to_kr_postgresql_domain_grounding_repair_v1",
        query_repair="prefix_section_title",
        source_product="postgresql",
    ),
    DomainPairConfig(
        name="kubernetes",
        kr_dataset_id="87f74f10-1e61-5c56-84f9-f70a87fba424",
        en_dataset_id="e0445e9e-7ed3-58aa-8ce1-a32d06d44a11",
        kr_dataset_key="kubernetes_kr_short_user_80",
        en_dataset_key="kubernetes_en_short_user_80",
        kr_dataset_name="Kubernetes KR Short User Eval 80 (KR, grounded v3)",
        en_dataset_name="Kubernetes EN Short User Eval 80 (EN, paired grounded v3)",
        kr_source=REPO_ROOT / "data" / "eval" / "kubernetes_kr_short_user_test_80.jsonl",
        en_source=REPO_ROOT / "data" / "eval" / "kubernetes_en_short_user_test_80.jsonl",
        version="v3-2026-05-30",
        kr_repair_mode="kubernetes_domain_grounding_repair_v1",
        en_repair_mode="paired_to_kr_kubernetes_domain_grounding_repair_v1",
        query_repair="preserve",
        source_product="kubernetes",
    ),
)


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
    cleaned_lines: list[str] = []
    in_fence = False
    for raw_line in str(text or "").replace("\r\n", "\n").replace("\r", "\n").split("\n"):
        line = raw_line.strip()
        if not line:
            continue
        if line.startswith("```"):
            in_fence = not in_fence
            continue
        line = OVERLAP_PREFIX_RE.sub("", line).strip()
        line = SECTION_PREFIX_RE.sub("", line).strip()
        if not line:
            continue
        if line in {"- Java", "- Kotlin"}:
            continue
        cleaned_lines.append(line)
    cleaned = _normalize(" ".join(cleaned_lines))
    marker = " - Kubernetes Documentation - "
    if marker in cleaned:
        cleaned = cleaned.split(marker, 1)[1].strip()
    return _normalize(cleaned)


def _snippet(text: str, *, max_chars: int = 620) -> str:
    text = _normalize(text)
    if len(text) <= max_chars:
        return text
    return text[: max_chars - 4].rstrip() + " ..."


def _section_title(section_path: str) -> str:
    section = _normalize(section_path).replace("\xa0", " ")
    if not section:
        return ""
    title = section.split(": ")[-1].strip()
    title = CHAPTER_PREFIX_RE.sub("", title).strip()
    return title


def _repair_postgresql_query(query: str, section_path: str) -> str:
    title = _section_title(section_path)
    query = _normalize(query)
    if not title or query.lower().startswith(title.lower()):
        return query
    return _normalize(f"{title} {query}")


def _fetch_chunks(connection: psycopg.Connection[Any], chunk_ids: list[str]) -> dict[str, dict[str, Any]]:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT c.chunk_id,
                   c.document_id,
                   c.chunk_index_in_document,
                   COALESCE(c.section_path_text, ''),
                   COALESCE(c.chunk_text, ''),
                   COALESCE(d.source_id, ''),
                   COALESCE(d.version_label, ''),
                   d.domain_id::text
            FROM corpus_chunks c
            JOIN corpus_documents d ON d.document_id = c.document_id
            WHERE c.chunk_id = ANY(%s)
            """,
            (list(dict.fromkeys(chunk_ids)),),
        )
        rows = cursor.fetchall()
    return {
        str(row[0]): {
            "chunk_id": str(row[0]),
            "document_id": str(row[1]),
            "chunk_index": int(row[2] or 0),
            "section_path_text": str(row[3] or ""),
            "chunk_text": str(row[4] or ""),
            "source_id": str(row[5] or ""),
            "version_label": str(row[6] or ""),
            "domain_id": str(row[7] or ""),
        }
        for row in rows
    }


def _fetch_dataset_before(connection: psycopg.Connection[Any], dataset_id: str) -> dict[str, Any]:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT dataset_id::text,
                   dataset_key,
                   dataset_name,
                   version,
                   total_items,
                   domain_id::text,
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
        "domain_id": str(row[5] or ""),
        "metadata": row[6] or {},
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


def _build_points(chunk_ids: list[str], chunks: dict[str, dict[str, Any]]) -> list[str]:
    points: list[str] = []
    for chunk_id in chunk_ids:
        chunk = chunks[chunk_id]
        section = chunk["section_path_text"]
        cleaned = _clean_chunk_text(chunk["chunk_text"])
        points.append(f"Section Path: {section}. {_snippet(cleaned)}" if cleaned else f"Section Path: {section}.")
    return points


def _paired_en_id(config: DomainPairConfig, kr_sample_id: str) -> str:
    if config.name == "postgresql":
        return kr_sample_id.replace("postgresql-kr-", "postgresql-en-", 1)
    if config.name == "kubernetes":
        return kr_sample_id.replace("kubernetes-kr-", "kubernetes-en-", 1)
    raise RuntimeError(f"unsupported config: {config.name}")


def _repair_kr_rows(
    config: DomainPairConfig,
    rows: list[dict[str, Any]],
    chunks: dict[str, dict[str, Any]],
    repaired_at: str,
) -> list[dict[str, Any]]:
    repaired: list[dict[str, Any]] = []
    for source in rows:
        row = dict(source)
        chunk_ids = [str(chunk_id) for chunk_id in row["expected_chunk_ids"]]
        for chunk_id in chunk_ids:
            if chunk_id not in chunks:
                raise RuntimeError(f"{config.name}: missing chunk {chunk_id}")

        if config.query_repair == "prefix_section_title":
            row["user_query_ko"] = _repair_postgresql_query(
                str(row.get("user_query_ko") or ""),
                chunks[chunk_ids[0]]["section_path_text"],
            )
        row["user_query_en"] = None
        row["query_language"] = "ko"
        row["expected_chunk_ids"] = chunk_ids
        row["expected_doc_ids"] = list(dict.fromkeys(chunks[chunk_id]["document_id"] for chunk_id in chunk_ids))
        row["expected_answer_key_points"] = _build_points(chunk_ids, chunks)
        row["single_or_multi_chunk"] = "multi" if len(chunk_ids) > 1 else "single"
        row["source_product"] = config.source_product
        row["source_version_if_available"] = row.get("source_version_if_available") or None
        metadata = dict(row.get("metadata") or {})
        metadata.update(
            {
                "dataset_key": config.kr_dataset_key,
                "query_language": "ko",
                "target_method": metadata.get("target_method") or row.get("target_method") or "A",
                "repair_mode": config.kr_repair_mode,
                "repaired_at": repaired_at,
                "paired_dataset_id": config.en_dataset_id,
                "grounding_policy": "expected_doc_ids and expected_answer_key_points are rebuilt from the current corpus chunk rows",
            }
        )
        if config.query_repair == "prefix_section_title":
            metadata["query_repair_policy"] = "prefix expected chunk section title to low-signal PostgreSQL short-user fragment"
        row["metadata"] = metadata
        repaired.append(row)
    return repaired


def _repair_en_rows(
    config: DomainPairConfig,
    en_rows: list[dict[str, Any]],
    kr_rows: list[dict[str, Any]],
    chunks: dict[str, dict[str, Any]],
    repaired_at: str,
) -> list[dict[str, Any]]:
    en_by_id = {str(row["sample_id"]): row for row in en_rows}
    repaired: list[dict[str, Any]] = []
    for kr in kr_rows:
        en_id = _paired_en_id(config, str(kr["sample_id"]))
        if en_id not in en_by_id:
            raise RuntimeError(f"{config.name}: missing EN pair for {kr['sample_id']}: {en_id}")
        row = dict(en_by_id[en_id])
        chunk_ids = [str(chunk_id) for chunk_id in kr["expected_chunk_ids"]]
        if config.query_repair == "prefix_section_title":
            row["user_query_en"] = _repair_postgresql_query(
                str(row.get("user_query_en") or ""),
                chunks[chunk_ids[0]]["section_path_text"],
            )
        row["user_query_ko"] = ""
        row["query_language"] = "en"
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
            row[field] = kr[field]
        metadata = dict(row.get("metadata") or {})
        metadata.update(
            {
                "dataset_key": config.en_dataset_key,
                "query_language": "en",
                "target_method": "E",
                "paired_sample_id": kr["sample_id"],
                "paired_user_query_ko": kr["user_query_ko"],
                "repair_mode": config.en_repair_mode,
                "repaired_at": repaired_at,
                "paired_dataset_id": config.kr_dataset_id,
                "grounding_policy": "paired to KR with identical expected doc ids, chunk ids, and answer key points",
            }
        )
        row["metadata"] = metadata
        repaired.append(row)
    return repaired


def _validate(rows: list[dict[str, Any]], *, language: str) -> dict[str, Any]:
    chunk_refs = sum(len(row["expected_chunk_ids"]) for row in rows)
    overlap = 0
    boilerplate = 0
    empty_query = 0
    hangul_en_query = 0
    en_ko_field_nonempty = 0
    duplicate_queries = 0
    seen_queries: set[str] = set()
    for row in rows:
        keypoints_text = json.dumps(row.get("expected_answer_key_points") or [], ensure_ascii=False)
        if "Overlap context from previous chunk" in keypoints_text:
            overlap += 1
        if "latest stable version" in keypoints_text.lower() or "still in development" in keypoints_text.lower():
            boilerplate += 1
        query = str(row.get("user_query_en") if language == "en" else row.get("user_query_ko") or "").strip()
        if not query:
            empty_query += 1
        if query in seen_queries:
            duplicate_queries += 1
        seen_queries.add(query)
        if language == "en":
            if HANGUL_RE.search(query):
                hangul_en_query += 1
            if str(row.get("user_query_ko") or "").strip():
                en_ko_field_nonempty += 1
    return {
        "sample_count": len(rows),
        "chunk_refs": chunk_refs,
        "single_count": sum(1 for row in rows if row["single_or_multi_chunk"] == "single"),
        "multi_count": sum(1 for row in rows if row["single_or_multi_chunk"] == "multi"),
        "overlap_keypoint_count": overlap,
        "boilerplate_keypoint_count": boilerplate,
        "empty_query_count": empty_query,
        "duplicate_query_count": duplicate_queries,
        "hangul_en_query_count": hangul_en_query,
        "en_user_query_ko_nonempty_count": en_ko_field_nonempty,
        "source_distribution": dict(Counter(str(row.get("source_product") or "") for row in rows)),
    }


def _pair_validation(kr_rows: list[dict[str, Any]], en_rows: list[dict[str, Any]]) -> dict[str, Any]:
    mismatch_count = 0
    for kr, en in zip(kr_rows, en_rows, strict=True):
        if kr["expected_doc_ids"] != en["expected_doc_ids"]:
            mismatch_count += 1
        if kr["expected_chunk_ids"] != en["expected_chunk_ids"]:
            mismatch_count += 1
        if kr["expected_answer_key_points"] != en["expected_answer_key_points"]:
            mismatch_count += 1
    return {"pair_count": len(kr_rows), "grounding_mismatch_count": mismatch_count}


def _strict_reference_validation(
    rows: list[dict[str, Any]],
    chunks: dict[str, dict[str, Any]],
) -> dict[str, Any]:
    missing = 0
    doc_mismatch = 0
    continuation_refs = 0
    chunk_refs = 0
    for row in rows:
        expected_docs = set(str(doc_id) for doc_id in row["expected_doc_ids"])
        for chunk_id in row["expected_chunk_ids"]:
            chunk_refs += 1
            chunk = chunks.get(str(chunk_id))
            if not chunk:
                missing += 1
                continue
            if chunk["document_id"] not in expected_docs:
                doc_mismatch += 1
            if str(chunk["chunk_text"]).lstrip().startswith("Overlap context from previous chunk:"):
                continuation_refs += 1
    return {
        "chunk_refs": chunk_refs,
        "missing_chunk_count": missing,
        "chunk_doc_mismatch_count": doc_mismatch,
        "continuation_chunk_ref_count": continuation_refs,
    }


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
                row.get("query_language"),
                Jsonb(row.get("dialog_context") or {}),
                Jsonb(row["expected_doc_ids"]),
                Jsonb(row["expected_chunk_ids"]),
                Jsonb(row["expected_answer_key_points"]),
                row.get("query_category") or "short_user",
                row.get("difficulty") or "medium",
                row.get("single_or_multi_chunk"),
                row.get("source_product"),
                row.get("source_version_if_available"),
                Jsonb(row.get("metadata") or {}),
                row["_dataset_id"],
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
                    row.get("single_or_multi_chunk"),
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
    single = sum(1 for row in rows if row["single_or_multi_chunk"] == "single")
    multi = sum(1 for row in rows if row["single_or_multi_chunk"] == "multi")
    with connection.cursor() as cursor:
        cursor.execute("SELECT COALESCE(metadata, '{}'::jsonb) FROM eval_dataset WHERE dataset_id = %s", (dataset_id,))
        metadata = dict((cursor.fetchone() or [{}])[0] or {})
        metadata.update(metadata_extra)
        metadata.update({"query_language": query_language, "version": version, "repaired_at": metadata_extra["repaired_at"]})
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


def _repair_config(
    connection: psycopg.Connection[Any],
    config: DomainPairConfig,
    repaired_at: str,
    *,
    write_changes: bool,
) -> dict[str, Any]:
    kr_source_rows = _load_jsonl(config.kr_source)
    en_source_rows = _load_jsonl(config.en_source)
    if len(kr_source_rows) != 80 or len(en_source_rows) != 80:
        raise RuntimeError(f"{config.name}: expected exactly 80 KR and 80 EN rows")

    chunk_ids = sorted({str(chunk_id) for row in kr_source_rows for chunk_id in row["expected_chunk_ids"]})
    chunks = _fetch_chunks(connection, chunk_ids)
    missing_chunks = sorted(set(chunk_ids) - set(chunks))
    if missing_chunks:
        raise RuntimeError(f"{config.name}: missing chunk ids: {missing_chunks}")

    kr_before = _fetch_dataset_before(connection, config.kr_dataset_id)
    en_before = _fetch_dataset_before(connection, config.en_dataset_id)
    kr_samples_before = _fetch_active_samples_before(connection, config.kr_dataset_id)
    en_samples_before = _fetch_active_samples_before(connection, config.en_dataset_id)

    kr_rows = _repair_kr_rows(config, kr_source_rows, chunks, repaired_at)
    en_rows = _repair_en_rows(config, en_source_rows, kr_rows, chunks, repaired_at)
    for row in kr_rows:
        row["_dataset_id"] = config.kr_dataset_id
    for row in en_rows:
        row["_dataset_id"] = config.en_dataset_id

    kr_validation = _validate(kr_rows, language="ko")
    en_validation = _validate(en_rows, language="en")
    pair_validation = _pair_validation(kr_rows, en_rows)
    reference_validation = _strict_reference_validation(kr_rows, chunks)
    hard_failures = [
        kr_validation["overlap_keypoint_count"],
        kr_validation["boilerplate_keypoint_count"],
        kr_validation["empty_query_count"],
        kr_validation["duplicate_query_count"],
        en_validation["overlap_keypoint_count"],
        en_validation["boilerplate_keypoint_count"],
        en_validation["empty_query_count"],
        en_validation["duplicate_query_count"],
        en_validation["hangul_en_query_count"],
        en_validation["en_user_query_ko_nonempty_count"],
        pair_validation["grounding_mismatch_count"],
        reference_validation["missing_chunk_count"],
        reference_validation["chunk_doc_mismatch_count"],
    ]
    if any(hard_failures):
        raise RuntimeError(
            json.dumps(
                {
                    "domain": config.name,
                    "kr_validation": kr_validation,
                    "en_validation": en_validation,
                    "pair_validation": pair_validation,
                    "reference_validation": reference_validation,
                },
                ensure_ascii=False,
                indent=2,
            )
        )

    if write_changes:
        _write_jsonl(config.kr_source, kr_rows)
        _write_jsonl(config.en_source, en_rows)

        for row in kr_rows:
            _upsert_sample(connection, row)
        for row in en_rows:
            _upsert_sample(connection, row)
        _replace_dataset_items(connection, config.kr_dataset_id, kr_rows)
        _replace_dataset_items(connection, config.en_dataset_id, en_rows)
        _update_dataset(
            connection,
            dataset_id=config.kr_dataset_id,
            dataset_name=config.kr_dataset_name,
            version=config.version,
            query_language="ko",
            rows=kr_rows,
            metadata_extra={
                "repaired_at": repaired_at,
                "repair_mode": config.kr_repair_mode,
                "paired_dataset_id": config.en_dataset_id,
                "paired_dataset_key": config.en_dataset_key,
                "source_file": str(config.kr_source.relative_to(REPO_ROOT)),
            },
        )
        _update_dataset(
            connection,
            dataset_id=config.en_dataset_id,
            dataset_name=config.en_dataset_name,
            version=config.version,
            query_language="en",
            rows=en_rows,
            metadata_extra={
                "repaired_at": repaired_at,
                "repair_mode": config.en_repair_mode,
                "paired_dataset_id": config.kr_dataset_id,
                "paired_dataset_key": config.kr_dataset_key,
                "source_file": str(config.en_source.relative_to(REPO_ROOT)),
            },
        )

    return {
        "domain": config.name,
        "version": config.version,
        "kr_dataset_before": kr_before,
        "en_dataset_before": en_before,
        "kr_active_sample_count_before": len(kr_samples_before),
        "en_active_sample_count_before": len(en_samples_before),
        "kr_validation": kr_validation,
        "en_validation": en_validation,
        "pair_validation": pair_validation,
        "reference_validation": reference_validation,
        "kr_output": str(config.kr_source),
        "en_output": str(config.en_source),
    }


def run(args: argparse.Namespace) -> dict[str, Any]:
    repaired_at = datetime.now(timezone.utc).isoformat()
    selected = [config for config in CONFIGS if args.domain in {"all", config.name}]
    if not selected:
        raise RuntimeError(f"unknown domain: {args.domain}")

    with psycopg.connect(
        host=args.db_host,
        port=args.db_port,
        dbname=args.db_name,
        user=args.db_user,
        password=args.db_password,
        autocommit=False,
    ) as connection:
        domain_reports = [
            _repair_config(connection, config, repaired_at, write_changes=not args.dry_run)
            for config in selected
        ]
        report = {
            "dry_run": bool(args.dry_run),
            "repaired_at": repaired_at,
            "domains": domain_reports,
        }
        if args.dry_run:
            connection.rollback()
        else:
            connection.commit()

    report_path = Path(args.report)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    report["report_path"] = str(report_path)
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description="Repair PostgreSQL/Kubernetes paired KR/EN short-user eval datasets.")
    parser.add_argument("--domain", choices=["all", "postgresql", "kubernetes"], default="all")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--report", type=Path, default=REPORT_DEFAULT)
    parser.add_argument("--db-host", default="localhost")
    parser.add_argument("--db-port", type=int, default=5432)
    parser.add_argument("--db-name", default="query_forge")
    parser.add_argument("--db-user", default="query_forge")
    parser.add_argument("--db-password", default="query_forge")
    args = parser.parse_args()
    print(json.dumps(run(args), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
