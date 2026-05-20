from __future__ import annotations

import argparse
import json
import re
import uuid
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb


REPO_ROOT = Path(__file__).resolve().parents[1]
VERSION_LABEL = "v1-2026-05-20"
DATASET_FAMILY = "spring_method_compressed_eval_80"
TARGET_TOTAL = 80

METHOD_BATCHES = {
    "A": "b45a1b9e-c135-4252-9aa2-ecb130c496cd",
    "B": "ca4ee519-3a9b-4803-a217-06b58ef097de",
    "C": "73b5bfc1-73b5-4cfe-ab64-daf94729578b",
    "D": "d9fd3ae5-4e16-4746-a0b8-4740678747ed",
    "E": "5fef4f64-47a1-4340-9a2f-7825a3c2b854",
}

METHOD_LANGUAGES = {
    "A": "ko",
    "B": "ko",
    "C": "ko",
    "D": "ko",
    "E": "en",
}

TECH_TOKEN_RE = re.compile(r"@[A-Za-z][A-Za-z0-9_]+|[A-Za-z][A-Za-z0-9_.:/+-]{2,}")
HANGUL_TOKEN_RE = re.compile(r"[\uac00-\ud7a3]{2,}")
SPACE_RE = re.compile(r"\s+")
SENTENCE_SPLIT_RE = re.compile(r"(?<=[.!?])\s+|\n+")

GENERIC_EN = {
    "spring",
    "security",
    "framework",
    "data",
    "boot",
    "class",
    "method",
    "object",
    "value",
    "values",
    "type",
    "types",
    "query",
    "queries",
    "request",
    "response",
    "application",
    "configuration",
    "config",
    "using",
    "usage",
    "example",
    "examples",
    "custom",
    "customize",
    "default",
}

GENERIC_KO = {
    "방법",
    "설정",
    "사용",
    "차이",
    "이유",
    "정의",
    "개념",
    "예시",
    "클래스",
    "메서드",
    "어노테이션",
    "애플리케이션",
    "스프링",
    "어떻게",
    "무엇",
    "왜",
}

ANSWERABILITY_PRIORITY = {
    "far": 0,
    "near": 1,
    "multi": 1,
    "single": 2,
}

QUERY_TYPE_PRIORITY = {
    "follow_up": 0,
    "comparison": 1,
    "procedure": 2,
    "reason": 3,
    "code_mixed": 4,
    "short_user": 5,
    "definition": 6,
}


@dataclass(frozen=True)
class Candidate:
    synthetic_query_id: str
    query_text: str
    query_language: str
    query_type: str
    generation_strategy: str
    target_doc_id: str
    target_chunk_ids: list[str]
    answerability_type: str
    glossary_terms: list[str]
    metadata: dict[str, Any]
    final_score: float


@dataclass(frozen=True)
class ChunkInfo:
    chunk_id: str
    document_id: str
    chunk_text: str
    section_path_text: str
    source_id: str
    version_label: str | None


def _normalize_spaces(text: str) -> str:
    return SPACE_RE.sub(" ", str(text or "").strip())


def _clean_token(token: str) -> str:
    return str(token or "").strip().strip(".,;:!?()[]{}<>\"'`")


def _is_technical_token(token: str) -> bool:
    value = _clean_token(token)
    if len(value) < 3:
        return False
    if value.startswith("@"):
        return True
    if any(separator in value for separator in (".", "_", "-", "/", ":")):
        return True
    if any(char.isdigit() for char in value):
        return True
    if value.isupper() and any(char.isalpha() for char in value) and len(value) >= 4:
        return True
    return any(char.isupper() for char in value[1:]) and any(char.islower() for char in value)


def _extract_terms(query_text: str, glossary_terms: list[str], *, language: str) -> list[str]:
    terms: list[str] = []
    seen: set[str] = set()

    def add(term: str, *, allow_plain: bool = False) -> None:
        cleaned = _clean_token(term)
        if not cleaned:
            return
        folded = cleaned.casefold()
        if folded in seen:
            return
        if folded in GENERIC_EN or cleaned in GENERIC_KO:
            return
        if not allow_plain and not _is_technical_token(cleaned):
            return
        seen.add(folded)
        terms.append(cleaned)

    for token in TECH_TOKEN_RE.findall(query_text):
        add(token)

    for term in glossary_terms:
        add(term)

    if language == "ko":
        for token in HANGUL_TOKEN_RE.findall(query_text):
            add(token, allow_plain=True)
            if len(terms) >= 8:
                break
    else:
        for token in TECH_TOKEN_RE.findall(query_text):
            add(token, allow_plain=_is_technical_token(token))
            if len(terms) >= 8:
                break

    return terms[:8]


def _suffix(query_type: str, *, language: str) -> str:
    normalized = str(query_type or "").strip().lower()
    if language == "en":
        return {
            "definition": "what?",
            "procedure": "how?",
            "reason": "why?",
            "comparison": "difference?",
            "follow_up": "this?",
            "code_mixed": "usage?",
            "short_user": "?",
        }.get(normalized, "?")
    return {
        "definition": "뭐임?",
        "procedure": "방법?",
        "reason": "왜?",
        "comparison": "차이?",
        "follow_up": "이거?",
        "code_mixed": "사용?",
        "short_user": "?",
    }.get(normalized, "?")


def _compress_query(candidate: Candidate, *, language: str, used_queries: set[str]) -> str:
    terms = _extract_terms(candidate.query_text, candidate.glossary_terms, language=language)
    suffix = _suffix(candidate.query_type, language=language)
    variants: list[str] = []

    if terms:
        variants.append(f"{terms[0]} {suffix}".replace(" ?", "?"))
    if len(terms) >= 2:
        variants.append(f"{terms[0]} {terms[1]} {suffix}".replace(" ?", "?"))
    if len(terms) >= 3:
        variants.append(f"{terms[0]} {terms[1]} {terms[2]}?".replace(" ?", "?"))

    source_words = _normalize_spaces(candidate.query_text).split()
    if not variants and source_words:
        variants.append(" ".join(source_words[:3]).rstrip("?.!") + "?")

    for variant in variants:
        query = _normalize_spaces(variant)
        if not query.endswith("?"):
            query += "?"
        query = query.replace("??", "?")
        if language == "ko" and len(query) > 64:
            continue
        if language == "en" and len(query) > 72:
            continue
        if len(query) < 3:
            continue
        key = query.casefold()
        if key in used_queries:
            continue
        used_queries.add(key)
        return query

    fallback = _normalize_spaces(candidate.query_text)
    if len(fallback) > 64:
        fallback = fallback[:64].rstrip()
    if not fallback.endswith("?"):
        fallback += "?"
    key = fallback.casefold()
    if key in used_queries:
        fallback = f"{fallback.rstrip('?')} {candidate.synthetic_query_id[:4]}?"
    used_queries.add(fallback.casefold())
    return fallback


def _first_key_point(chunk: ChunkInfo, *, max_len: int = 360) -> str:
    text = _normalize_spaces(chunk.chunk_text)
    if not text:
        return ""
    sentence = SENTENCE_SPLIT_RE.split(text)[0].strip()
    if len(sentence) > max_len:
        sentence = sentence[:max_len].rstrip() + "..."
    section = _normalize_spaces(chunk.section_path_text)
    if section:
        return f"Section Path: {section}. {sentence}"
    return sentence


def _dataset_key(method: str, language: str) -> str:
    return f"spring_method_{method.lower()}_compressed_eval_80_{language}"


def _dataset_id(dataset_key: str) -> str:
    return str(uuid.uuid5(uuid.NAMESPACE_URL, f"query-forge:{dataset_key}:{VERSION_LABEL}"))


def _output_file(method: str, language: str) -> Path:
    return REPO_ROOT / "data" / "eval" / f"spring_method_{method.lower()}_compressed_eval_80_{language}.jsonl"


def _fetch_candidates(connection: psycopg.Connection[Any], *, method: str, gating_batch_id: str) -> list[Candidate]:
    with connection.cursor(row_factory=dict_row) as cursor:
        cursor.execute(
            """
            SELECT r.synthetic_query_id,
                   r.query_text,
                   r.query_language,
                   r.query_type,
                   r.generation_strategy,
                   r.target_doc_id,
                   r.target_chunk_ids,
                   r.answerability_type,
                   r.glossary_terms,
                   r.metadata,
                   g.final_score
            FROM synthetic_query_gating_result g
            JOIN synthetic_queries_raw_all r
              ON r.synthetic_query_id = g.synthetic_query_id
            WHERE g.gating_batch_id = %s
              AND g.accepted = TRUE
              AND r.generation_strategy = %s
              AND r.query_text IS NOT NULL
              AND btrim(r.query_text) <> ''
              AND jsonb_typeof(r.target_chunk_ids) = 'array'
              AND jsonb_array_length(r.target_chunk_ids) > 0
            """,
            (gating_batch_id, method),
        )
        rows = cursor.fetchall()

    candidates: list[Candidate] = []
    for row in rows:
        candidates.append(
            Candidate(
                synthetic_query_id=str(row["synthetic_query_id"]),
                query_text=str(row["query_text"]),
                query_language=str(row["query_language"] or ""),
                query_type=str(row["query_type"] or ""),
                generation_strategy=str(row["generation_strategy"] or ""),
                target_doc_id=str(row["target_doc_id"] or ""),
                target_chunk_ids=[str(item) for item in (row["target_chunk_ids"] or []) if str(item).strip()],
                answerability_type=str(row["answerability_type"] or ""),
                glossary_terms=[str(item) for item in (row["glossary_terms"] or []) if str(item).strip()],
                metadata=dict(row["metadata"] or {}),
                final_score=float(row["final_score"] or 0.0),
            )
        )
    return candidates


def _fetch_chunk_map(connection: psycopg.Connection[Any], chunk_ids: set[str]) -> dict[str, ChunkInfo]:
    if not chunk_ids:
        return {}
    with connection.cursor(row_factory=dict_row) as cursor:
        cursor.execute(
            """
            SELECT c.chunk_id,
                   c.document_id,
                   COALESCE(c.chunk_text, '') AS chunk_text,
                   COALESCE(c.section_path_text, '') AS section_path_text,
                   d.source_id,
                   d.version_label
            FROM corpus_chunks c
            JOIN corpus_documents d
              ON d.document_id = c.document_id
             AND d.is_active = TRUE
            WHERE c.chunk_id = ANY(%s)
            """,
            (sorted(chunk_ids),),
        )
        rows = cursor.fetchall()
    return {
        str(row["chunk_id"]): ChunkInfo(
            chunk_id=str(row["chunk_id"]),
            document_id=str(row["document_id"]),
            chunk_text=str(row["chunk_text"] or ""),
            section_path_text=str(row["section_path_text"] or ""),
            source_id=str(row["source_id"] or ""),
            version_label=str(row["version_label"]) if row["version_label"] else None,
        )
        for row in rows
    }


def _candidate_sort_key(candidate: Candidate) -> tuple[int, int, float, str]:
    answerability_rank = ANSWERABILITY_PRIORITY.get(candidate.answerability_type.lower(), 9)
    query_type_rank = QUERY_TYPE_PRIORITY.get(candidate.query_type.lower(), 9)
    return (answerability_rank, query_type_rank, -candidate.final_score, candidate.synthetic_query_id)


def _build_rows(
    *,
    method: str,
    language: str,
    gating_batch_id: str,
    candidates: list[Candidate],
    chunk_map: dict[str, ChunkInfo],
    target_total: int,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    used_queries: set[str] = set()
    used_chunk_signatures: set[tuple[str, ...]] = set()
    rows: list[dict[str, Any]] = []
    skipped: list[dict[str, Any]] = []

    for candidate in sorted(candidates, key=_candidate_sort_key):
        if len(rows) >= target_total:
            break
        chunk_ids = [chunk_id for chunk_id in candidate.target_chunk_ids if chunk_id in chunk_map]
        if not chunk_ids or len(chunk_ids) != len(candidate.target_chunk_ids):
            skipped.append({"synthetic_query_id": candidate.synthetic_query_id, "reason": "missing_chunk"})
            continue
        chunk_signature = tuple(chunk_ids)
        if chunk_signature in used_chunk_signatures:
            skipped.append({"synthetic_query_id": candidate.synthetic_query_id, "reason": "duplicate_chunk_signature"})
            continue

        compressed_query = _compress_query(candidate, language=language, used_queries=used_queries)
        if not compressed_query:
            skipped.append({"synthetic_query_id": candidate.synthetic_query_id, "reason": "compression_failed"})
            continue

        expected_doc_ids: list[str] = []
        if candidate.target_doc_id:
            expected_doc_ids.append(candidate.target_doc_id)
        expected_key_points: list[str] = []
        source_product: str | None = None
        source_version: str | None = None
        for chunk_id in chunk_ids:
            chunk = chunk_map[chunk_id]
            if chunk.document_id not in expected_doc_ids:
                expected_doc_ids.append(chunk.document_id)
            if source_product is None and chunk.source_id:
                source_product = chunk.source_id
            if source_version is None and chunk.version_label:
                source_version = chunk.version_label
            key_point = _first_key_point(chunk)
            if key_point:
                expected_key_points.append(key_point)
        if not expected_key_points:
            skipped.append({"synthetic_query_id": candidate.synthetic_query_id, "reason": "missing_key_point"})
            continue

        index = len(rows) + 1
        sample_id = f"spring-method-{method.lower()}-compressed-{index:03d}"
        metadata = {
            "dataset_family": DATASET_FAMILY,
            "dataset_profile": "method_compressed_stress",
            "target_method": method,
            "query_language": language,
            "source_synthetic_query_id": candidate.synthetic_query_id,
            "source_gating_batch_id": gating_batch_id,
            "source_generation_batch_id": candidate.metadata.get("generation_batch_id"),
            "source_query_text": candidate.query_text,
            "source_query_type": candidate.query_type,
            "source_answerability_type": candidate.answerability_type,
            "source_final_score": candidate.final_score,
            "compression_policy": "minimal_anchor_short_user",
            "selection_policy": "accepted_full_gating_near_far_first_unique_chunk_signature",
            "evaluation_focus": ["retrieval_stress", "rewrite", "synthetic_memory_effect"],
        }
        row = {
            "sample_id": sample_id,
            "split": "test",
            "query_language": language,
            "user_query_ko": compressed_query if language == "ko" else "",
            "user_query_en": compressed_query if language == "en" else None,
            "dialog_context": {},
            "expected_doc_ids": expected_doc_ids,
            "expected_chunk_ids": chunk_ids,
            "expected_answer_key_points": expected_key_points,
            "query_category": "short_user",
            "difficulty": "hard" if len(chunk_ids) > 1 else "medium",
            "single_or_multi_chunk": "multi" if len(chunk_ids) > 1 else "single",
            "source_product": source_product,
            "source_version_if_available": source_version,
            "target_method": method,
            "metadata": metadata,
        }
        rows.append(row)
        used_chunk_signatures.add(chunk_signature)

    if len(rows) < target_total:
        raise RuntimeError(f"method {method}: only built {len(rows)} rows; need {target_total}")
    return rows, skipped


def _write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n", encoding="utf-8")


def _distribution(rows: list[dict[str, Any]], field: str) -> dict[str, int]:
    return dict(Counter(str(row.get(field) or "") for row in rows))


def _upsert_dataset(
    connection: psycopg.Connection[Any],
    *,
    rows: list[dict[str, Any]],
    dataset_id: str,
    dataset_key: str,
    dataset_name: str,
    description: str,
    language: str,
    method: str,
    output_file: Path,
    gating_batch_id: str,
) -> None:
    now = datetime.now(timezone.utc).isoformat()
    with connection.cursor() as cursor:
        cursor.execute(
            """
            INSERT INTO eval_dataset (
                dataset_id,
                dataset_key,
                dataset_name,
                description,
                version,
                split_strategy,
                total_items,
                category_distribution,
                single_multi_distribution,
                metadata
            ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
            ON CONFLICT (dataset_key) DO UPDATE
            SET dataset_name = EXCLUDED.dataset_name,
                description = EXCLUDED.description,
                version = EXCLUDED.version,
                split_strategy = EXCLUDED.split_strategy,
                total_items = EXCLUDED.total_items,
                category_distribution = EXCLUDED.category_distribution,
                single_multi_distribution = EXCLUDED.single_multi_distribution,
                metadata = EXCLUDED.metadata,
                updated_at = NOW()
            """,
            (
                dataset_id,
                dataset_key,
                dataset_name,
                description,
                VERSION_LABEL,
                "test_only",
                len(rows),
                Jsonb(_distribution(rows, "query_category")),
                Jsonb(_distribution(rows, "single_or_multi_chunk")),
                Jsonb(
                    {
                        "dataset_family": DATASET_FAMILY,
                        "dataset_profile": "method_compressed_stress",
                        "query_language": language,
                        "target_method": method,
                        "source_gating_batch_id": gating_batch_id,
                        "source_file": str(output_file.relative_to(REPO_ROOT)).replace("\\", "/"),
                        "selection_policy": "accepted full-gating synthetic rows, near/far first, unique chunk signatures",
                        "compression_policy": "minimal anchors from existing synthetic query text and glossary terms",
                        "performance_note": "Designed as a challenging stress dataset; metrics must be reported, not assumed.",
                        "updated_at": now,
                    }
                ),
            ),
        )

        for row in rows:
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
                    metadata
                ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
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
                    metadata = EXCLUDED.metadata
                """,
                (
                    row["sample_id"],
                    row["split"],
                    row["user_query_ko"],
                    row["user_query_en"],
                    row["query_language"],
                    Jsonb(row["dialog_context"]),
                    Jsonb(row["expected_doc_ids"]),
                    Jsonb(row["expected_chunk_ids"]),
                    Jsonb(row["expected_answer_key_points"]),
                    row["query_category"],
                    row["difficulty"],
                    row["single_or_multi_chunk"],
                    row["source_product"],
                    row["source_version_if_available"],
                    Jsonb(row["metadata"]),
                ),
            )

        cursor.execute("DELETE FROM eval_dataset_item WHERE dataset_id = %s", (dataset_id,))
        for row in rows:
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
                (dataset_id, row["sample_id"], row["query_category"], row["single_or_multi_chunk"]),
            )


def run(
    *,
    output_dir: Path,
    report_file: Path,
    skip_db: bool,
    db_host: str,
    db_port: int,
    db_name: str,
    db_user: str,
    db_password: str,
    target_total: int = TARGET_TOTAL,
) -> dict[str, Any]:
    summaries: list[dict[str, Any]] = []
    all_rows_by_method: dict[str, list[dict[str, Any]]] = {}
    with psycopg.connect(
        host=db_host,
        port=db_port,
        dbname=db_name,
        user=db_user,
        password=db_password,
        autocommit=False,
    ) as connection:
        for method, gating_batch_id in METHOD_BATCHES.items():
            language = METHOD_LANGUAGES[method]
            candidates = _fetch_candidates(connection, method=method, gating_batch_id=gating_batch_id)
            chunk_ids = {chunk_id for candidate in candidates for chunk_id in candidate.target_chunk_ids}
            chunk_map = _fetch_chunk_map(connection, chunk_ids)
            rows, skipped = _build_rows(
                method=method,
                language=language,
                gating_batch_id=gating_batch_id,
                candidates=candidates,
                chunk_map=chunk_map,
                target_total=target_total,
            )
            output_file = output_dir / _output_file(method, language).name
            _write_jsonl(output_file, rows)
            dataset_key = _dataset_key(method, language)
            dataset_id = _dataset_id(dataset_key)
            if not skip_db:
                _upsert_dataset(
                    connection,
                    rows=rows,
                    dataset_id=dataset_id,
                    dataset_key=dataset_key,
                    dataset_name=f"Spring Method {method} Compressed Eval 80 ({language.upper()})",
                    description=(
                        f"Method {method} compressed short-user stress dataset built from accepted synthetic queries "
                        f"in gating batch {gating_batch_id}."
                    ),
                    language=language,
                    method=method,
                    output_file=output_file,
                    gating_batch_id=gating_batch_id,
                )
            all_rows_by_method[method] = rows
            summaries.append(
                {
                    "method": method,
                    "dataset_id": dataset_id,
                    "dataset_key": dataset_key,
                    "language": language,
                    "source_gating_batch_id": gating_batch_id,
                    "candidate_count": len(candidates),
                    "selected_count": len(rows),
                    "skipped_count": len(skipped),
                    "answerability_distribution": _distribution(
                        [{"answerability": row["metadata"]["source_answerability_type"]} for row in rows],
                        "answerability",
                    ),
                    "single_multi_distribution": _distribution(rows, "single_or_multi_chunk"),
                    "output_file": str(output_file.relative_to(REPO_ROOT)).replace("\\", "/"),
                    "sample_preview": [
                        {
                            "sample_id": row["sample_id"],
                            "query": row["user_query_ko"] or row["user_query_en"],
                            "source_query_text": row["metadata"]["source_query_text"],
                            "expected_chunk_ids": row["expected_chunk_ids"],
                        }
                        for row in rows[:5]
                    ],
                }
            )

        if skip_db:
            connection.rollback()
        else:
            connection.commit()

    report = {
        "version": VERSION_LABEL,
        "dataset_family": DATASET_FAMILY,
        "target_total_per_method": target_total,
        "built_at": datetime.now(timezone.utc).isoformat(),
        "skip_db": skip_db,
        "summaries": summaries,
        "structural_validation": _validate_outputs(all_rows_by_method, target_total=target_total),
    }
    report_file.parent.mkdir(parents=True, exist_ok=True)
    report_file.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return report


def _validate_outputs(rows_by_method: dict[str, list[dict[str, Any]]], *, target_total: int) -> dict[str, Any]:
    issues: list[str] = []
    for method, rows in rows_by_method.items():
        if len(rows) != target_total:
            issues.append(f"{method}: expected {target_total}, got {len(rows)}")
        sample_ids = [row["sample_id"] for row in rows]
        if len(sample_ids) != len(set(sample_ids)):
            issues.append(f"{method}: duplicate sample_id")
        for row in rows:
            language = row["query_language"]
            if language == "ko" and not row["user_query_ko"]:
                issues.append(f"{row['sample_id']}: missing user_query_ko")
            if language == "en" and not row["user_query_en"]:
                issues.append(f"{row['sample_id']}: missing user_query_en")
            if not row["expected_doc_ids"] or not row["expected_chunk_ids"]:
                issues.append(f"{row['sample_id']}: missing expected grounding")
            if row["metadata"].get("target_method") != method:
                issues.append(f"{row['sample_id']}: target_method mismatch")
    return {"status": "pass" if not issues else "fail", "issues": issues}


def main() -> int:
    parser = argparse.ArgumentParser(description="Build method-compressed Spring eval datasets from existing synthetic queries.")
    parser.add_argument("--output-dir", default=str(REPO_ROOT / "data" / "eval"))
    parser.add_argument("--report-file", default=str(REPO_ROOT / "data" / "reports" / "spring_method_compressed_eval_80_audit_2026-05-20.json"))
    parser.add_argument("--skip-db", action="store_true")
    parser.add_argument("--db-host", default="localhost")
    parser.add_argument("--db-port", type=int, default=5432)
    parser.add_argument("--db-name", default="query_forge")
    parser.add_argument("--db-user", default="query_forge")
    parser.add_argument("--db-password", default="query_forge")
    parser.add_argument("--target-total", type=int, default=TARGET_TOTAL)
    args = parser.parse_args()

    summary = run(
        output_dir=Path(args.output_dir),
        report_file=Path(args.report_file),
        skip_db=args.skip_db,
        db_host=args.db_host,
        db_port=args.db_port,
        db_name=args.db_name,
        db_user=args.db_user,
        db_password=args.db_password,
        target_total=args.target_total,
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
