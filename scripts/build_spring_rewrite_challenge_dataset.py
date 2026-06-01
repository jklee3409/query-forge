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

SOURCE_DATASET_ID = "b2d47254-8655-4c9c-81ac-7615677ec5bd"
DATASET_KEY = "spring_kr_rewrite_challenge_30"
DATASET_ID = str(uuid.uuid5(uuid.NAMESPACE_URL, f"query-forge:{DATASET_KEY}:v1-2026-06-01"))
VERSION_LABEL = "v1-2026-06-01"
OUTPUT_FILE = REPO_ROOT / "data" / "eval" / "spring_kr_rewrite_challenge_30.jsonl"
REPORT_FILE = REPO_ROOT / "data" / "reports" / "spring_kr_rewrite_challenge_30_audit_2026-06-01.json"

ASCII_ANCHOR_RE = re.compile(r"[A-Za-z@._-]")


@dataclass(frozen=True)
class QuerySpec:
    source_sample_id: str
    query: str


QUERY_SPECS: tuple[QuerySpec, ...] = (
    QuerySpec("test-short-user-001", "다이제스트 인증 필터 설정 방법?"),
    QuerySpec("test-short-user-002", "인증서 로그인 후 로그아웃 세션 관리?"),
    QuerySpec("test-short-user-003", "프록시 전달 헤더 접두사와 보안 표시 역할?"),
    QuerySpec("test-short-user-004", "자바 영속성 속성 경로 밑줄 탐색 기준?"),
    QuerySpec("test-short-user-005", "관점 지향 스키마와 어노테이션 방식 차이?"),
    QuerySpec("test-short-user-006", "동기 요청 클라이언트를 새 요청 클라이언트로 옮기는 방법?"),
    QuerySpec("test-short-user-007", "외부 인증 로그인 페이지와 리디렉션 끝점 설정?"),
    QuerySpec("test-short-user-008", "접근 토큰 응답 변환기 커스터마이징 방법?"),
    QuerySpec("test-short-user-009", "긴 연결 스트리밍에는 어떤 웹 스택을 써야 함?"),
    QuerySpec("test-short-user-010", "인터페이스 기반 데이터 투영 동작 방식?"),
    QuerySpec("test-short-user-011", "표현식 언어 가변 인자 타입 변환?"),
    QuerySpec("test-short-user-012", "내장 디렉터리 서버 설정 방법?"),
    QuerySpec("test-short-user-013", "웹 테스트 클라이언트와 모의 웹 계층 단언 비교?"),
    QuerySpec("test-short-user-014", "데이터 공통 모듈 코틀린 지원 조건?"),
    QuerySpec("test-short-user-015", "웹 요청 인터페이스 클라이언트 정의 방법?"),
    QuerySpec("test-short-user-016", "속성 경로 팩토리 빈 값 참조 방법?"),
    QuerySpec("test-short-user-017", "외부 인증 클라이언트 네임스페이스 설정?"),
    QuerySpec("test-short-user-018", "엔티티 콜백 동기식과 반응형 차이?"),
    QuerySpec("test-short-user-021", "데이터 전달 객체 투영 생성자 지정?"),
    QuerySpec("test-short-user-023", "자바 메시징 템플릿 수신 메서드?"),
    QuerySpec("test-short-user-025", "질의 조건 어노테이션 웹 바인딩?"),
    QuerySpec("test-short-user-026", "모의 웹 테스트와 브라우저 테스트 통합 이유?"),
    QuerySpec("test-short-user-034", "실행 권한 관리자 구현체 역할?"),
    QuerySpec("test-short-user-035", "관리 속성 어노테이션 읽기 전용 설정?"),
    QuerySpec("test-short-user-040", "사전 컴파일 실행 힌트가 필요한 이유?"),
    QuerySpec("test-short-user-043", "게시 요청에서 위조 방어 오류가 나는 이유?"),
    QuerySpec("test-short-user-049", "메시지 수신 컨테이너와 분산 트랜잭션 관리자?"),
    QuerySpec("test-short-user-051", "접근 토큰 응답 클라이언트 빈 등록과 승인 관리자?"),
    QuerySpec("test-short-user-064", "여러 부분 요청 처리기와 양식 데이터 파라미터?"),
    QuerySpec("test-short-user-072", "전통 웹 모형과 반응형 웹 선택 기준?"),
)


def _json_list(value: Any) -> list[Any]:
    if isinstance(value, list):
        return value
    return []


def _source_rows(connection: psycopg.Connection[Any]) -> dict[str, dict[str, Any]]:
    with connection.cursor(row_factory=dict_row) as cursor:
        cursor.execute(
            """
            SELECT s.sample_id,
                   s.split,
                   s.user_query_ko,
                   s.user_query_en,
                   s.dialog_context,
                   s.expected_doc_ids,
                   s.expected_chunk_ids,
                   s.expected_answer_key_points,
                   s.query_category,
                   s.difficulty,
                   s.single_or_multi_chunk,
                   s.source_product,
                   s.source_version_if_available,
                   s.metadata,
                   COALESCE(s.domain_id, i.domain_id, d.domain_id) AS domain_id
            FROM eval_dataset_item i
            JOIN eval_dataset d
              ON d.dataset_id = i.dataset_id
            JOIN eval_samples s
              ON s.sample_id = i.sample_id
            WHERE i.dataset_id = %s
              AND i.active = TRUE
            """,
            (SOURCE_DATASET_ID,),
        )
        rows = cursor.fetchall()
    return {str(row["sample_id"]): dict(row) for row in rows}


def _fetch_chunk_docs(connection: psycopg.Connection[Any], chunk_ids: set[str]) -> dict[str, str]:
    if not chunk_ids:
        return {}
    with connection.cursor(row_factory=dict_row) as cursor:
        cursor.execute(
            """
            SELECT c.chunk_id,
                   c.document_id
            FROM corpus_chunks c
            JOIN corpus_documents d
              ON d.document_id = c.document_id
             AND d.is_active = TRUE
            WHERE c.chunk_id = ANY(%s)
            """,
            (sorted(chunk_ids),),
        )
        rows = cursor.fetchall()
    return {str(row["chunk_id"]): str(row["document_id"]) for row in rows}


def _build_rows(source_rows: dict[str, dict[str, Any]]) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for index, spec in enumerate(QUERY_SPECS, start=1):
        source = source_rows.get(spec.source_sample_id)
        if source is None:
            raise RuntimeError(f"source sample is not active in V6 dataset: {spec.source_sample_id}")
        metadata = dict(source.get("metadata") or {})
        metadata.update(
            {
                "dataset_key": DATASET_KEY,
                "dataset_profile": "rewrite_challenge_anchor_gap",
                "query_language": "ko",
                "target_method": "A/C",
                "source_dataset_id": SOURCE_DATASET_ID,
                "source_sample_id": spec.source_sample_id,
                "source_user_query_ko": source.get("user_query_ko"),
                "query_surface_policy": "Korean-only technical paraphrase with English/API anchors removed",
                "evaluation_focus": ["rewrite", "anchor_recovery", "retrieval_stress"],
            }
        )
        rows.append(
            {
                "sample_id": f"spring-rewrite-challenge-{index:03d}",
                "split": source.get("split") or "test",
                "query_language": "ko",
                "user_query_ko": spec.query,
                "user_query_en": None,
                "dialog_context": source.get("dialog_context") or {},
                "expected_doc_ids": _json_list(source.get("expected_doc_ids")),
                "expected_chunk_ids": _json_list(source.get("expected_chunk_ids")),
                "expected_answer_key_points": _json_list(source.get("expected_answer_key_points")),
                "query_category": "short_user",
                "difficulty": "hard",
                "single_or_multi_chunk": source.get("single_or_multi_chunk") or "single",
                "source_product": source.get("source_product"),
                "source_version_if_available": source.get("source_version_if_available"),
                "target_method": "A/C",
                "metadata": metadata,
                "_domain_id": source.get("domain_id"),
            }
        )
    return rows


def _validate_rows(connection: psycopg.Connection[Any], rows: list[dict[str, Any]]) -> dict[str, Any]:
    issues: list[str] = []
    if len(rows) != len(QUERY_SPECS):
        issues.append(f"row count mismatch: {len(rows)} != {len(QUERY_SPECS)}")

    queries = [str(row["user_query_ko"]) for row in rows]
    duplicates = [query for query, count in Counter(queries).items() if count > 1]
    if duplicates:
        issues.append(f"duplicate queries: {duplicates}")
    for row in rows:
        query = str(row["user_query_ko"])
        if not query.strip():
            issues.append(f"{row['sample_id']}: empty query")
        if ASCII_ANCHOR_RE.search(query):
            issues.append(f"{row['sample_id']}: query contains ASCII anchor surface")
        if not row["expected_doc_ids"] or not row["expected_chunk_ids"]:
            issues.append(f"{row['sample_id']}: missing grounding")

    chunk_ids = {str(chunk_id) for row in rows for chunk_id in row["expected_chunk_ids"]}
    chunk_docs = _fetch_chunk_docs(connection, chunk_ids)
    for row in rows:
        expected_docs = {str(doc_id) for doc_id in row["expected_doc_ids"]}
        for chunk_id in row["expected_chunk_ids"]:
            actual_doc = chunk_docs.get(str(chunk_id))
            if actual_doc is None:
                issues.append(f"{row['sample_id']}: missing active corpus chunk {chunk_id}")
            elif actual_doc not in expected_docs:
                issues.append(
                    f"{row['sample_id']}: chunk {chunk_id} belongs to {actual_doc}, not expected docs {sorted(expected_docs)}"
                )

    return {
        "status": "pass" if not issues else "fail",
        "issues": issues,
        "row_count": len(rows),
        "ascii_anchor_query_count": sum(1 for query in queries if ASCII_ANCHOR_RE.search(query)),
        "single_multi_distribution": dict(Counter(str(row["single_or_multi_chunk"]) for row in rows)),
    }


def _write_jsonl(rows: list[dict[str, Any]], output_file: Path) -> None:
    output_file.parent.mkdir(parents=True, exist_ok=True)
    payload_rows = []
    for row in rows:
        copy = dict(row)
        copy.pop("_domain_id", None)
        payload_rows.append(copy)
    output_file.write_text(
        "\n".join(json.dumps(row, ensure_ascii=False) for row in payload_rows) + "\n",
        encoding="utf-8",
    )


def _upsert_db(connection: psycopg.Connection[Any], rows: list[dict[str, Any]], output_file: Path) -> None:
    now = datetime.now(timezone.utc).isoformat()
    domain_ids = {str(row["_domain_id"]) for row in rows if row.get("_domain_id")}
    domain_id = sorted(domain_ids)[0] if len(domain_ids) == 1 else None
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
                metadata,
                domain_id
            ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
            ON CONFLICT (dataset_key) DO UPDATE
            SET dataset_name = EXCLUDED.dataset_name,
                description = EXCLUDED.description,
                version = EXCLUDED.version,
                split_strategy = EXCLUDED.split_strategy,
                total_items = EXCLUDED.total_items,
                category_distribution = EXCLUDED.category_distribution,
                single_multi_distribution = EXCLUDED.single_multi_distribution,
                metadata = EXCLUDED.metadata,
                domain_id = EXCLUDED.domain_id,
                updated_at = NOW()
            """,
            (
                DATASET_ID,
                DATASET_KEY,
                "Spring KR Rewrite Challenge 30",
                "Korean-only anchor-gap rewrite challenge copied from Spring KR V6 grounding.",
                VERSION_LABEL,
                "test_only",
                len(rows),
                Jsonb(dict(Counter(str(row["query_category"]) for row in rows))),
                Jsonb(dict(Counter(str(row["single_or_multi_chunk"]) for row in rows))),
                Jsonb(
                    {
                        "dataset_profile": "rewrite_challenge_anchor_gap",
                        "query_language": "ko",
                        "source_dataset_id": SOURCE_DATASET_ID,
                        "source_dataset_version": "v6-2026-05-30",
                        "source_file": str(output_file.relative_to(REPO_ROOT)).replace("\\", "/"),
                        "query_surface_policy": "Korean-only technical paraphrase with English/API anchors removed",
                        "evaluation_focus": ["rewrite", "anchor_recovery", "retrieval_stress"],
                        "updated_at": now,
                    }
                ),
                domain_id,
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
                    metadata,
                    domain_id
                ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
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
                    row.get("_domain_id"),
                ),
            )

        cursor.execute("DELETE FROM eval_dataset_item WHERE dataset_id = %s", (DATASET_ID,))
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
                ) VALUES (%s, %s, %s, %s, TRUE, %s)
                """,
                (
                    DATASET_ID,
                    row["sample_id"],
                    row["query_category"],
                    row["single_or_multi_chunk"],
                    row.get("_domain_id"),
                ),
            )


def run(
    *,
    output_file: Path,
    report_file: Path,
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
        rows = _build_rows(_source_rows(connection))
        validation = _validate_rows(connection, rows)
        if validation["status"] != "pass":
            connection.rollback()
            raise RuntimeError(json.dumps(validation, ensure_ascii=False, indent=2))
        _write_jsonl(rows, output_file)
        if skip_db:
            connection.rollback()
        else:
            _upsert_db(connection, rows, output_file)
            connection.commit()

    report = {
        "dataset_id": DATASET_ID,
        "dataset_key": DATASET_KEY,
        "version": VERSION_LABEL,
        "source_dataset_id": SOURCE_DATASET_ID,
        "source_dataset_version": "v6-2026-05-30",
        "output_file": str(output_file.relative_to(REPO_ROOT)).replace("\\", "/"),
        "skip_db": skip_db,
        "built_at": datetime.now(timezone.utc).isoformat(),
        "validation": validation,
        "sample_preview": [
            {
                "sample_id": row["sample_id"],
                "query": row["user_query_ko"],
                "source_sample_id": row["metadata"]["source_sample_id"],
                "source_query": row["metadata"]["source_user_query_ko"],
                "expected_chunk_ids": row["expected_chunk_ids"],
            }
            for row in rows[:8]
        ],
    }
    report_file.parent.mkdir(parents=True, exist_ok=True)
    report_file.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description="Build the Spring KR rewrite challenge dataset.")
    parser.add_argument("--output-file", default=str(OUTPUT_FILE))
    parser.add_argument("--report-file", default=str(REPORT_FILE))
    parser.add_argument("--skip-db", action="store_true")
    parser.add_argument("--db-host", default="localhost")
    parser.add_argument("--db-port", type=int, default=5432)
    parser.add_argument("--db-name", default="query_forge")
    parser.add_argument("--db-user", default="query_forge")
    parser.add_argument("--db-password", default="query_forge")
    args = parser.parse_args()

    report = run(
        output_file=Path(args.output_file),
        report_file=Path(args.report_file),
        skip_db=args.skip_db,
        db_host=args.db_host,
        db_port=args.db_port,
        db_name=args.db_name,
        db_user=args.db_user,
        db_password=args.db_password,
    )
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
