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


SOURCE_ID = "docs-python-org-ko-3-14"
SOURCE_PRODUCT = "python"
SOURCE_VERSION = "3.14.5"
SOURCE_BASE_URL = "https://docs.python.org/3/"

KO_DATASET_ID = "dfbadf26-0ab6-4b95-890e-5196dddc62cc"
KO_DATASET_KEY = "python_kr_short_user_80_ko"
EN_DATASET_ID = "0d29df79-3920-40b2-b7ff-897eac5544fa"
EN_DATASET_KEY = "python_kr_short_user_80_en"

DEFAULT_KO_OUTPUT = Path("data/eval/python_kr_short_user_test_80_ko.jsonl")
DEFAULT_EN_OUTPUT = Path("data/eval/python_kr_short_user_test_80_en.jsonl")
DEFAULT_AUDIT_OUTPUT = Path("data/reports/python_kr_eval_dataset_80_audit_2026-05-12.json")

EVALUATION_FOCUS = ["retrieval", "rewrite", "language_comparison", "python_kr_source"]


@dataclass(frozen=True, slots=True)
class QuerySpec:
    path: str
    chunk_indices: tuple[int, ...]
    ko: str
    en: str


@dataclass(frozen=True, slots=True)
class ChunkRecord:
    path: str
    chunk_index: int
    document_id: str
    chunk_id: str
    title: str
    canonical_url: str
    section_path: str
    chunk_text: str


QUERY_SPECS: tuple[QuerySpec, ...] = (
    QuerySpec("howto/argparse.html", (0,), "argparse 빠른 시작?", "argparse quick start?"),
    QuerySpec("howto/argparse.html", (1,), "argparse 위치 인자?", "argparse positional arguments?"),
    QuerySpec("howto/argparse.html", (2, 3), "argparse 위치 옵션 같이?", "argparse positional and optional arguments?"),
    QuerySpec("howto/argparse.html", (4,), "argparse 애매한 옵션?", "argparse ambiguous options?"),
    QuerySpec("howto/argparse.html", (5,), "argparse 옵션 충돌?", "argparse conflicting options?"),
    QuerySpec("howto/argparse.html", (6,), "argparse custom type?", "argparse custom type converters?"),
    QuerySpec("howto/logging.html", (0,), "logging basicConfig?", "logging basicConfig setup?"),
    QuerySpec("howto/logging.html", (1,), "logging 변수 데이터?", "logging variable data?"),
    QuerySpec("howto/logging.html", (2, 3), "logging handler 설정?", "logging handler configuration?"),
    QuerySpec("howto/logging.html", (4,), "logging handler 종류?", "useful logging handlers?"),
    QuerySpec("howto/regex.html", (0,), "regex 기본 메타문자?", "regex metacharacters?"),
    QuerySpec("howto/regex.html", (1,), "re.compile 언제?", "when use re.compile?"),
    QuerySpec("howto/regex.html", (2,), "regex match search?", "regex match search?"),
    QuerySpec("howto/regex.html", (3,), "re 플래그 옵션?", "re compilation flags?"),
    QuerySpec("howto/regex.html", (4, 5), "regex named group?", "regex named groups?"),
    QuerySpec("howto/regex.html", (6,), "re.split 문자열 나누기?", "re.split strings?"),
    QuerySpec("howto/regex.html", (7,), "re.sub 치환?", "re.sub replacement?"),
    QuerySpec("howto/regex.html", (8,), "greedy non-greedy?", "greedy vs non-greedy regex?"),
    QuerySpec("howto/sockets.html", (0,), "socket send recv 흐름?", "socket send recv flow?"),
    QuerySpec("howto/sockets.html", (1,), "socket binary data?", "socket binary data?"),
    QuerySpec("library/argparse.html", (0,), "ArgumentParser 개요?", "ArgumentParser overview?"),
    QuerySpec("library/argparse.html", (7,), "argparse action 종류?", "argparse action types?"),
    QuerySpec("library/argparse.html", (9,), "argparse nargs?", "argparse nargs?"),
    QuerySpec("library/argparse.html", (10,), "argparse default 값?", "argparse default value?"),
    QuerySpec("library/argparse.html", (11,), "argparse type bool 주의?", "argparse type bool gotcha?"),
    QuerySpec("library/argparse.html", (17, 18), "argparse subcommands?", "argparse subcommands?"),
    QuerySpec("library/argparse.html", (20,), "argparse mutually exclusive?", "argparse mutually exclusive group?"),
    QuerySpec("library/argparse.html", (22,), "argparse custom action 등록?", "register argparse custom action?"),
    QuerySpec("library/datetime.html", (0,), "datetime 모듈 뭐?", "datetime module overview?"),
    QuerySpec("library/datetime.html", (1,), "timedelta 생성?", "timedelta construction?"),
    QuerySpec("library/datetime.html", (2,), "timedelta 연산?", "timedelta operations?"),
    QuerySpec("library/datetime.html", (3,), "date 객체 사용?", "date object usage?"),
    QuerySpec("library/datetime.html", (6,), "datetime 객체 만들기?", "datetime object construction?"),
    QuerySpec("library/datetime.html", (15, 16), "tzinfo DST 구현?", "tzinfo DST implementation?"),
    QuerySpec("library/datetime.html", (21,), "strftime 코드?", "strftime format codes?"),
    QuerySpec("library/datetime.html", (22, 23), "strptime 윤년 문제?", "strptime leap year issue?"),
    QuerySpec("library/exceptions.html", (0,), "내장 예외 뭐?", "built-in exceptions overview?"),
    QuerySpec("library/exceptions.html", (1,), "OSError 계열?", "OSError subclasses?"),
    QuerySpec("library/exceptions.html", (3,), "ExceptionGroup?", "ExceptionGroup?"),
    QuerySpec("library/exceptions.html", (4,), "예외 계층 구조?", "exception hierarchy?"),
    QuerySpec("library/functions.html", (0,), "내장 함수 목록?", "built-in functions list?"),
    QuerySpec("library/functions.html", (2,), "classmethod 언제?", "when use classmethod?"),
    QuerySpec("library/functions.html", (4,), "enumerate 사용법?", "enumerate usage?"),
    QuerySpec("library/functions.html", (7,), "hex 변환?", "hex conversion?"),
    QuerySpec("library/functions.html", (12,), "pow mod 인자?", "pow mod argument?"),
    QuerySpec("library/functions.html", (16,), "zip strict?", "zip strict option?"),
    QuerySpec("library/json.html", (0,), "json dumps loads?", "json dumps loads?"),
    QuerySpec("library/json.html", (2,), "json 기본 사용?", "json basic usage?"),
    QuerySpec("library/json.html", (3,), "JSONEncoder Decoder?", "JSONEncoder JSONDecoder?"),
    QuerySpec("library/json.html", (4,), "JSONDecodeError?", "JSONDecodeError?"),
    QuerySpec("library/logging.html", (0,), "logging 모듈 개요?", "logging module overview?"),
    QuerySpec("library/logging.html", (1,), "Logger 객체?", "Logger object?"),
    QuerySpec("library/logging.html", (2,), "logging level?", "logging levels?"),
    QuerySpec("library/logging.html", (3,), "Formatter 객체?", "Formatter object?"),
    QuerySpec("library/logging.html", (4,), "LogRecord 객체?", "LogRecord object?"),
    QuerySpec("library/logging.html", (5,), "LoggerAdapter?", "LoggerAdapter?"),
    QuerySpec("library/pathlib.html", (0,), "pathlib Path 언제?", "when use pathlib Path?"),
    QuerySpec("library/re.html", (0,), "re 모듈 뭐?", "re module overview?"),
    QuerySpec("library/re.html", (6,), "re flags?", "re flags?"),
    QuerySpec("library/re.html", (7,), "re 함수들?", "re functions?"),
    QuerySpec("library/re.html", (10,), "Pattern 객체?", "Pattern object?"),
    QuerySpec("library/re.html", (11,), "Match 객체?", "Match object?"),
    QuerySpec("library/re.html", (14,), "search match 차이?", "search vs match?"),
    QuerySpec("library/stdtypes.html", (6,), "sequence 공통 연산?", "common sequence operations?"),
    QuerySpec("library/stdtypes.html", (10,), "str 메서드 기본?", "str methods basics?"),
    QuerySpec("library/stdtypes.html", (16,), "str.split 동작?", "str.split behavior?"),
    QuerySpec("library/stdtypes.html", (18,), "f-string debug?", "f-string debug specifier?"),
    QuerySpec("library/stdtypes.html", (21,), "bytearray hex?", "bytearray hex?"),
    QuerySpec("library/stdtypes.html", (30,), "set frozenset 연산?", "set frozenset operations?"),
    QuerySpec("library/stdtypes.html", (31, 32), "dict 기본 연산?", "dict operations?"),
    QuerySpec("library/subprocess.html", (0,), "subprocess 언제?", "when use subprocess?"),
    QuerySpec("library/subprocess.html", (1,), "subprocess.run 사용?", "subprocess.run usage?"),
    QuerySpec("library/subprocess.html", (2,), "Popen 생성자?", "Popen constructor?"),
    QuerySpec("library/subprocess.html", (3,), "subprocess 예외?", "subprocess exceptions?"),
    QuerySpec("library/typing.html", (0,), "typing 런타임 검사?", "typing runtime enforcement?"),
    QuerySpec("library/typing.html", (1,), "NewType 왜?", "why use NewType?"),
    QuerySpec("library/typing.html", (2,), "Callable annotation?", "Callable annotation?"),
    QuerySpec("library/typing.html", (5,), "사용자 제네릭?", "user defined generics?"),
    QuerySpec("library/typing.html", (14, 15), "type alias generic?", "type alias generics?"),
    QuerySpec("library/typing.html", (24,), "reveal_type 어디?", "reveal_type usage?"),
)


def _normalize_spaces(value: str) -> str:
    return re.sub(r"\s+", " ", value or "").strip()


def _clean_chunk_text(value: str) -> str:
    text = value or ""
    section_position = text.find("Section Path:")
    if section_position > 0:
        text = text[section_position:]
    text = re.sub(r"^Overlap context from previous chunk:\s*", "", text)
    return _normalize_spaces(text)


def _summary_for_chunk(chunk: ChunkRecord) -> str:
    text = _clean_chunk_text(chunk.chunk_text)
    if not text:
        return f"Section Path: {chunk.section_path}"
    text = re.sub(r"```.*?```", " ", text, flags=re.DOTALL)
    text = _normalize_spaces(text)
    if len(text) > 360:
        cut = max(text.rfind(".", 0, 360), text.rfind("다.", 0, 360), text.rfind("요.", 0, 360))
        if cut >= 120:
            text = text[: cut + 1]
        else:
            text = text[:360].rstrip() + "..."
    return text


def _fetch_chunks(
    connection: psycopg.Connection[Any],
    specs: tuple[QuerySpec, ...],
) -> dict[tuple[str, int], ChunkRecord]:
    paths = sorted({spec.path for spec in specs})
    urls = [SOURCE_BASE_URL + path for path in paths]
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT replace(d.canonical_url, %s, '') AS path,
                   c.chunk_index_in_document,
                   d.document_id,
                   c.chunk_id,
                   d.title,
                   d.canonical_url,
                   c.section_path_text,
                   c.chunk_text
            FROM corpus_chunks c
            JOIN corpus_documents d
              ON d.document_id = c.document_id
            WHERE d.source_id = %s
              AND d.canonical_url = ANY(%s)
            ORDER BY d.canonical_url, c.chunk_index_in_document
            """,
            (SOURCE_BASE_URL, SOURCE_ID, urls),
        )
        rows = cursor.fetchall()

    chunks: dict[tuple[str, int], ChunkRecord] = {}
    for row in rows:
        (
            path,
            chunk_index,
            document_id,
            chunk_id,
            title,
            canonical_url,
            section_path,
            chunk_text,
        ) = row
        path = str(path)
        chunk_index = int(chunk_index)
        chunks[(path, chunk_index)] = ChunkRecord(
            path=path,
            chunk_index=chunk_index,
            document_id=str(document_id),
            chunk_id=str(chunk_id),
            title=str(title or ""),
            canonical_url=str(canonical_url),
            section_path=str(section_path or ""),
            chunk_text=str(chunk_text or ""),
        )
    return chunks


def _build_row(
    *,
    index: int,
    spec: QuerySpec,
    chunks: list[ChunkRecord],
    language: str,
) -> dict[str, Any]:
    is_en = language == "en"
    dataset_key = EN_DATASET_KEY if is_en else KO_DATASET_KEY
    dataset_id = EN_DATASET_ID if is_en else KO_DATASET_ID
    target_method = "F" if is_en else "G"
    sample_id = f"test-python-kr-{language}-{index:03d}"
    paired_sample_id = f"test-python-kr-{'ko' if is_en else 'en'}-{index:03d}"
    query = spec.en if is_en else spec.ko
    source_urls = list(dict.fromkeys(chunk.canonical_url for chunk in chunks))
    expected_doc_ids = list(dict.fromkeys(chunk.document_id for chunk in chunks))
    expected_chunk_ids = [chunk.chunk_id for chunk in chunks]
    key_points = [_summary_for_chunk(chunk) for chunk in chunks]
    single_or_multi = "multi" if len(chunks) > 1 else "single"

    return {
        "sample_id": sample_id,
        "paired_sample_id": paired_sample_id,
        "split": "test",
        "query_language": language,
        "user_query_ko": "" if is_en else query,
        "user_query_en": query if is_en else "",
        "dialog_context": {},
        "expected_doc_ids": expected_doc_ids,
        "expected_chunk_ids": expected_chunk_ids,
        "expected_answer_key_points": key_points,
        "query_category": "short_user",
        "difficulty": "hard" if single_or_multi == "multi" else "medium",
        "single_or_multi_chunk": single_or_multi,
        "source_product": SOURCE_PRODUCT,
        "source_version_if_available": SOURCE_VERSION,
        "target_method": target_method,
        "evaluation_focus": EVALUATION_FOCUS,
        "metadata": {
            "builder": "python-kr-short-user-v1",
            "dataset_id": dataset_id,
            "dataset_key": dataset_key,
            "query_language": language,
            "target_method": target_method,
            "paired_sample_id": paired_sample_id,
            "paired_dataset_key": KO_DATASET_KEY if is_en else EN_DATASET_KEY,
            "source_id": SOURCE_ID,
            "source_document_language": "ko",
            "source_urls": source_urls,
            "source_chunk_indices": [chunk.chunk_index for chunk in chunks],
            "evaluation_focus": EVALUATION_FOCUS,
        },
    }


def _build_rows(chunks_by_key: dict[tuple[str, int], ChunkRecord]) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    ko_rows: list[dict[str, Any]] = []
    en_rows: list[dict[str, Any]] = []
    for index, spec in enumerate(QUERY_SPECS, start=1):
        chunks: list[ChunkRecord] = []
        for chunk_index in spec.chunk_indices:
            key = (spec.path, chunk_index)
            if key not in chunks_by_key:
                raise KeyError(f"missing source chunk for {spec.path}#{chunk_index}")
            chunks.append(chunks_by_key[key])
        ko_rows.append(_build_row(index=index, spec=spec, chunks=chunks, language="ko"))
        en_rows.append(_build_row(index=index, spec=spec, chunks=chunks, language="en"))
    return ko_rows, en_rows


def _write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n",
        encoding="utf-8",
    )


def _validate_rows(ko_rows: list[dict[str, Any]], en_rows: list[dict[str, Any]]) -> dict[str, Any]:
    issues: list[str] = []
    warnings: list[str] = []
    all_rows = ko_rows + en_rows
    sample_ids = [row["sample_id"] for row in all_rows]
    if len(ko_rows) != 80:
        issues.append(f"ko row count is {len(ko_rows)}, expected 80")
    if len(en_rows) != 80:
        issues.append(f"en row count is {len(en_rows)}, expected 80")
    if len(sample_ids) != len(set(sample_ids)):
        issues.append("duplicate sample_id detected")

    for rows, language in ((ko_rows, "ko"), (en_rows, "en")):
        queries = [row["user_query_en"] if language == "en" else row["user_query_ko"] for row in rows]
        if len(queries) != len(set(queries)):
            issues.append(f"duplicate {language} query detected")
        for row, query in zip(rows, queries):
            if row["query_language"] != language:
                issues.append(f"{row['sample_id']} has wrong query_language")
            if not query.strip():
                issues.append(f"{row['sample_id']} query is blank")
            if len(query) > 80:
                warnings.append(f"{row['sample_id']} query is longer than 80 chars")
            if "�" in query or any(token in query for token in ("媛", "蹂", "鍮", "萸")):
                issues.append(f"{row['sample_id']} query contains mojibake-like text")
            if not row["expected_doc_ids"]:
                issues.append(f"{row['sample_id']} expected_doc_ids is empty")
            if not row["expected_chunk_ids"]:
                issues.append(f"{row['sample_id']} expected_chunk_ids is empty")
            if not row["expected_answer_key_points"]:
                issues.append(f"{row['sample_id']} expected_answer_key_points is empty")
            if row["metadata"]["target_method"] not in {"F", "G"}:
                issues.append(f"{row['sample_id']} has invalid target_method")

    paired_mismatches = []
    for ko_row, en_row in zip(ko_rows, en_rows):
        if ko_row["expected_chunk_ids"] != en_row["expected_chunk_ids"]:
            paired_mismatches.append((ko_row["sample_id"], en_row["sample_id"]))
    if paired_mismatches:
        issues.append(f"paired chunk mismatch count={len(paired_mismatches)}")

    return {
        "status": "pass" if not issues else "fail",
        "issues": issues,
        "warnings": warnings,
        "counts": {
            "ko": len(ko_rows),
            "en": len(en_rows),
            "total": len(all_rows),
            "unique_samples": len(set(sample_ids)),
        },
        "category_distribution": dict(Counter(row["query_category"] for row in all_rows)),
        "single_multi_distribution": dict(Counter(row["single_or_multi_chunk"] for row in all_rows)),
    }


def _dataset_distribution(rows: list[dict[str, Any]], field: str) -> dict[str, int]:
    return dict(Counter(str(row.get(field) or "") for row in rows))


def _upsert_dataset(
    connection: psycopg.Connection[Any],
    *,
    rows: list[dict[str, Any]],
    dataset_id: str,
    dataset_key: str,
    dataset_name: str,
    description: str,
    query_language: str,
    target_method: str,
    output_file: Path,
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
                "v1-2026-05-12",
                "test_only",
                len(rows),
                Jsonb(_dataset_distribution(rows, "query_category")),
                Jsonb(_dataset_distribution(rows, "single_or_multi_chunk")),
                Jsonb(
                    {
                        "query_language": query_language,
                        "strategy_profile": "python_kr",
                        "dataset_family": "python_kr_short_user_80",
                        "target_method": target_method,
                        "source_id": SOURCE_ID,
                        "source_product": SOURCE_PRODUCT,
                        "source_document_language": "ko",
                        "paired_dataset_key": KO_DATASET_KEY if query_language == "en" else EN_DATASET_KEY,
                        "source_file": str(output_file).replace("\\", "/"),
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
    ko_output: Path,
    en_output: Path,
    audit_output: Path,
    skip_db: bool,
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
        autocommit=False,
    ) as connection:
        chunks_by_key = _fetch_chunks(connection, QUERY_SPECS)
        ko_rows, en_rows = _build_rows(chunks_by_key)
        audit = _validate_rows(ko_rows, en_rows)
        if audit["status"] != "pass":
            raise RuntimeError(json.dumps(audit, ensure_ascii=False, indent=2))

        _write_jsonl(ko_output, ko_rows)
        _write_jsonl(en_output, en_rows)

        if not skip_db:
            _upsert_dataset(
                connection,
                rows=ko_rows,
                dataset_id=KO_DATASET_ID,
                dataset_key=KO_DATASET_KEY,
                dataset_name="Python KR Short User Eval 80 (KO)",
                description="Korean short-user evaluation dataset grounded in Korean Python 3.14 documentation.",
                query_language="ko",
                target_method="G",
                output_file=ko_output,
            )
            _upsert_dataset(
                connection,
                rows=en_rows,
                dataset_id=EN_DATASET_ID,
                dataset_key=EN_DATASET_KEY,
                dataset_name="Python KR Short User Eval 80 (EN)",
                description="English short-user evaluation dataset paired to Korean Python documentation chunks.",
                query_language="en",
                target_method="F",
                output_file=en_output,
            )
            connection.commit()
        else:
            connection.rollback()

    report = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "source_id": SOURCE_ID,
        "source_product": SOURCE_PRODUCT,
        "source_version": SOURCE_VERSION,
        "ko_dataset_id": KO_DATASET_ID,
        "ko_dataset_key": KO_DATASET_KEY,
        "en_dataset_id": EN_DATASET_ID,
        "en_dataset_key": EN_DATASET_KEY,
        "ko_output_file": str(ko_output).replace("\\", "/"),
        "en_output_file": str(en_output).replace("\\", "/"),
        "skip_db": skip_db,
        "audit": audit,
    }
    audit_output.parent.mkdir(parents=True, exist_ok=True)
    audit_output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description="Build paired KO/EN Python-KR short-user eval datasets.")
    parser.add_argument("--ko-output", default=str(DEFAULT_KO_OUTPUT))
    parser.add_argument("--en-output", default=str(DEFAULT_EN_OUTPUT))
    parser.add_argument("--audit-output", default=str(DEFAULT_AUDIT_OUTPUT))
    parser.add_argument("--skip-db", action="store_true")
    parser.add_argument("--db-host", default="localhost")
    parser.add_argument("--db-port", type=int, default=5432)
    parser.add_argument("--db-name", default="query_forge")
    parser.add_argument("--db-user", default="query_forge")
    parser.add_argument("--db-password", default="query_forge")
    args = parser.parse_args()

    summary = run(
        ko_output=Path(args.ko_output),
        en_output=Path(args.en_output),
        audit_output=Path(args.audit_output),
        skip_db=args.skip_db,
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
