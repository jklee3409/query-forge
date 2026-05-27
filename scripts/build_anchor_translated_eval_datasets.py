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
from psycopg.types.json import Jsonb


VERSION_LABEL = "v1-2026-05-27"
EVALUATION_FOCUS = ["grounding", "short_user", "anchor_translation", "domain_retrieval"]
ANCHOR_TRANSLATION_POLICY = "translate_or_paraphrase_english_technical_anchors_to_korean_surface"


SPRING_QUERIES: tuple[str, ...] = (
    "보안 다이제스트 인증 필터 같이 쓸 때 포인트?",
    "보안 보통 어떻게 씀?",
    "전달 헤더 두 개 같이 쓸 때 포인트?",
    "자바 영속성 메서드 같이 쓸 때 포인트?",
    "스키마 적용 순서 뭐임?",
    "동기 호출 클라이언트와 새 클라이언트 같이 쓸 때 포인트?",
    "외부 인증과 로그인 뭐가 맞음?",
    "접근 토큰 응답 변환기 쓰는 예시?",
    "지연 시간 적용 순서 뭐임?",
    "인터페이스 빠르게 요약?",
    "표현식 언어 보통 어떻게 씀?",
    "보안 언제 씀?",
    "웹 테스트 클라이언트 어떻게 씀?",
    "공통 모듈 최신 버전 같이 쓸 때 포인트?",
    "웹 요청 방식과 서비스 뭐가 맞음?",
    "속성 경로 팩토리 빈 사용 포인트?",
    "보안 어떻게 씀?",
    "자바 영속성과 동기식 뭐가 맞음?",
    "관점 지향 프로그래밍 빠르게 요약?",
    "페이지 모델 뭐임?",
    "데이터 전달 객체 설정 어떻게 함?",
    "실행 실무에서 보통 어떻게 씀?",
    "자바 메시징 뭐임?",
    "엔티티 핵심만?",
    "질의 조건 어노테이션 핵심만?",
    "페이지 테스트 같이 쓰는 예시?",
    "보안 빠르게 요약?",
    "제어 역전 빠르게 요약?",
    "등록 클라이언트 무엇이며 같이 쓸 때 포인트?",
    "객체 관계 매핑 적용 순서 뭐임?",
    "클라우드 배포 플랫폼 뭐가 맞음?",
    "교환 호출과 비동기 교환 예시?",
    "인증 메타데이터 빠르게 요약?",
    "실행 권한 관리자 언제 씀?",
    "관리 속성 어노테이션 핵심만?",
    "새 요청 클라이언트 커스터마이징 포인트?",
    "웹 모형 제어기 적용 순서 뭐임?",
    "토큰 발급자 반응형 인증 관리자 선택기 사용 포인트?",
    "외부 인증 승인 클라이언트 관리자 어떻게 씀?",
    "사전 컴파일과 반영 같이 쓰는 예시?",
    "대리 가능 어노테이션 한줄 정리?",
    "자바 영속성 핵심만?",
    "보안 설정 어떻게 함?",
    "코틀린 빠르게 요약?",
    "애플리케이션 이벤트 발행자 설정 어떻게 함?",
    "저장소 메서드 문맥 사용 포인트?",
    "대리 팩토리 빈 핵심만?",
    "웹 클라이언트 응답 변환 포인트?",
    "분산 트랜잭션 관리자 어떻게 씀?",
    "관측 데이터 전송 예시?",
    "보안 최신 버전에서 쓰는 예시?",
    "웹소켓 메시지 브로커 활성화 요약?",
    "저장소 인터페이스 예시?",
    "보안 실무에서 보통 어떻게 씀?",
    "문서 설정으로 메시징 쓰는 예시?",
    "삭제 처리 예시?",
    "환경 객체 빠르게 요약?",
    "표현식 언어 적용 순서 뭐임?",
    "웹 클라이언트 핵심만?",
    "테스트 빠르게 요약?",
    "보안 모의 요청 후처리 핵심만?",
    "외부 인증 빠르게 요약?",
    "예제 매처 빠르게 요약?",
    "여러 부분 요청 처리기 활성화 한줄 정리?",
    "자바 영속성 빠르게 요약?",
    "보안 적용 순서 뭐임?",
    "보안 핵심만?",
    "단언 라이브러리 적용 순서 뭐임?",
    "코틀린 코루틴 같이 쓰는 예시?",
    "자바 영속성 설정 어떻게 함?",
    "예약 실행 적용 순서 뭐임?",
    "웹 모형 제어기와 반응형 웹 같이 쓸 때 포인트?",
    "경로 매칭 전략 설정 어떻게 함?",
    "지점 조건식 언제 씀?",
    "내부 빈 같이 쓸 때 포인트?",
    "사전 컴파일 빠르게 요약?",
    "플랫폼 트랜잭션 관리자 뭐임?",
    "가져오기 어노테이션 같이 쓸 때 포인트?",
    "여러 부분 양식 데이터 빠르게 요약?",
    "데이터베이스 초기화 어노테이션 뭐임?",
)


POSTGRESQL_QUERIES: tuple[str, ...] = (
    "객체 최고권한자 누가 가능?",
    "현재 트랜잭션 커밋됨?",
    "질의 결과 어디서 봄?",
    "연결 옵션 뭐 넣음?",
    "데이터 디렉터리 경로 문제?",
    "사용된 매개변수 어디서?",
    "값 옵션 뭐 바뀜?",
    "질의 출력 어디로?",
    "원격 데이터베이스 커서 닫기?",
    "반환값 뭐 옴?",
    "결과 집합 정보?",
    "데이터베이스 확인?",
    "행 다시 부르면?",
    "저장점 계속 유효함?",
    "새 테이블 결과 생성?",
    "트리거 정의 확장 종속?",
    "결과 열 메타정보?",
    "데이터베이스 이름 현재 연결?",
    "함수 호출 때 연결?",
    "오류 안 나게 하려면?",
    "이후 작업 안 됨?",
    "객체 이름 권한?",
    "링크 기호 같은 항목 처리?",
    "파일 못 쓰는 경우?",
    "처리기 검증기 옵션 권한?",
    "외래 키 삭제 동작?",
    "포함 객체 권한 회수?",
    "라벨 제공자 모듈 어디서?",
    "선택 선호 기준?",
    "테이블 열 권한?",
    "테이블 옵션 복원?",
    "데이터베이스 서버 상태?",
    "압축 수준 슬롯?",
    "출력 파일 어디 생성?",
    "기본 키 값 생성?",
    "행 집합 커서 이동?",
    "지정 이름 연결?",
    "인자 모드와 인자 타입 지움?",
    "현재 정렬 규칙 갱신?",
    "실행 시 매개변수 현재값?",
    "최고권한자 명령 필요?",
    "비동기 질의 진행?",
    "리터럴 문자열 실행?",
    "보안 정의자 제한?",
    "테이블 접근 용도?",
    "질의 행 저장됨?",
    "대기 서버 동작 차이?",
    "질의 모드 영향?",
    "패턴 인자 확장?",
    "매개변수 기본값?",
    "행에서 이동?",
    "테이블 이름 뭐임?",
    "가장 오래된 트랜잭션 값?",
    "사용자 소유자 삭제?",
    "알림 던지기 언제?",
    "열 이름 변경?",
    "새 소유자와 새 스키마 변경?",
    "백그라운드 작업자 상태?",
    "기존 연결 종료?",
    "명령 기록 파일 어디?",
    "구조화 질의 언어 예약어?",
    "권한과 라벨 차이?",
    "진행 중인 파이프라인 결과 읽기?",
    "데이터베이스 생성 보조 명령 맞음?",
    "프레임 절 현재 행?",
    "시스템 카탈로그 주의?",
    "연결 상태 프롬프트 표시?",
    "페이지 건너뛰기 동작 끄기?",
    "범위 정수 분포?",
    "트랜잭션 안전 맞음?",
    "연결 문자열 데이터베이스 무시?",
    "파일 이름 보기?",
    "식별자 기본값 변환?",
    "파티션 계층 복원?",
    "중재 제약 조건 뭐임?",
    "합성 전체 백업 어디?",
    "디버그 수준 로그?",
    "잠금 절 옵션?",
    "클라이언트 세션 변수?",
    "데이터베이스 설정 출력?",
)


@dataclass(frozen=True, slots=True)
class DatasetSpec:
    name: str
    source_dataset_id: str
    dataset_key: str
    dataset_name: str
    description: str
    output_file: Path
    sample_prefix: str
    queries: tuple[str, ...]

    @property
    def dataset_id(self) -> str:
        return str(uuid.uuid5(uuid.NAMESPACE_URL, f"query-forge:{self.dataset_key}:{VERSION_LABEL}"))


DATASETS: tuple[DatasetSpec, ...] = (
    DatasetSpec(
        name="spring",
        source_dataset_id="b2d47254-8655-4c9c-81ac-7615677ec5bd",
        dataset_key="spring_kr_anchor_translated_short_user_80",
        dataset_name="Spring KR Anchor-Translated Short User Eval 80",
        description=(
            "Separate Korean short-user evaluation dataset copied from Spring KR Short User Eval 80 "
            "with English technical anchors translated or paraphrased into Korean."
        ),
        output_file=Path("data/eval/spring_kr_anchor_translated_short_user_test_80.jsonl"),
        sample_prefix="spring-kr-anchor-translated-short-user",
        queries=SPRING_QUERIES,
    ),
    DatasetSpec(
        name="postgresql",
        source_dataset_id="862642e6-10bd-538d-9ba8-5de7f1f26d3c",
        dataset_key="postgresql_kr_anchor_translated_short_user_80",
        dataset_name="PostgreSQL KR Anchor-Translated Short User Eval 80",
        description=(
            "Separate Korean short-user evaluation dataset copied from PostgreSQL KR Short User Eval 80 "
            "with English technical anchors translated or paraphrased into Korean."
        ),
        output_file=Path("data/eval/postgresql_kr_anchor_translated_short_user_test_80.jsonl"),
        sample_prefix="postgresql-kr-anchor-translated-short-user",
        queries=POSTGRESQL_QUERIES,
    ),
)


def _as_jsonable(value: Any, fallback: Any) -> Any:
    if value is None:
        return fallback
    return value


def _fetch_source_rows(connection: psycopg.Connection[Any], dataset_id: str) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT
                dataset_id::text,
                dataset_key,
                dataset_name,
                metadata,
                domain_id::text
            FROM eval_dataset
            WHERE dataset_id = %s
            """,
            (dataset_id,),
        )
        dataset_row = cursor.fetchone()
        if not dataset_row:
            raise RuntimeError(f"Source dataset not found: {dataset_id}")

        cursor.execute(
            """
            SELECT
                s.sample_id,
                s.split,
                s.user_query_ko,
                s.user_query_en,
                s.query_language,
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
                s.domain_id::text
            FROM eval_dataset_item i
            JOIN eval_samples s ON s.sample_id = i.sample_id
            WHERE i.dataset_id = %s
              AND i.active = TRUE
            ORDER BY s.sample_id
            """,
            (dataset_id,),
        )
        rows = cursor.fetchall()

    source_dataset = {
        "dataset_id": dataset_row[0],
        "dataset_key": dataset_row[1],
        "dataset_name": dataset_row[2],
        "metadata": _as_jsonable(dataset_row[3], {}),
        "domain_id": dataset_row[4],
    }
    source_rows = [
        {
            "sample_id": row[0],
            "split": row[1],
            "user_query_ko": row[2],
            "user_query_en": row[3],
            "query_language": row[4],
            "dialog_context": _as_jsonable(row[5], {}),
            "expected_doc_ids": _as_jsonable(row[6], []),
            "expected_chunk_ids": _as_jsonable(row[7], []),
            "expected_answer_key_points": _as_jsonable(row[8], []),
            "query_category": row[9],
            "difficulty": row[10],
            "single_or_multi_chunk": row[11],
            "source_product": row[12],
            "source_version_if_available": row[13],
            "metadata": _as_jsonable(row[14], {}),
            "domain_id": row[15],
        }
        for row in rows
    ]
    return source_dataset, source_rows


def _target_method(row: dict[str, Any]) -> str:
    metadata = row.get("metadata") or {}
    return str(metadata.get("target_method") or metadata.get("source_generation_strategy") or "A")


def _build_rows(spec: DatasetSpec, source_dataset: dict[str, Any], source_rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    if len(source_rows) != 80:
        raise RuntimeError(f"{spec.name} source row count mismatch: {len(source_rows)}")
    if len(spec.queries) != len(source_rows):
        raise RuntimeError(f"{spec.name} query override count mismatch: {len(spec.queries)} != {len(source_rows)}")

    now = datetime.now(timezone.utc).isoformat()
    rows: list[dict[str, Any]] = []
    for index, (source_row, query) in enumerate(zip(source_rows, spec.queries), start=1):
        metadata = dict(source_row.get("metadata") or {})
        target_method = _target_method(source_row)
        metadata.update(
            {
                "updated_at": now,
                "dataset_key": spec.dataset_key,
                "query_style": "short_user",
                "query_language": "ko",
                "target_method": target_method,
                "generation_mode": "source_dataset_anchor_translated_short_user_v1",
                "query_surface_language": "ko_anchor_translated_short_user",
                "anchor_translation_policy": ANCHOR_TRANSLATION_POLICY,
                "source_dataset_id": source_dataset["dataset_id"],
                "source_dataset_key": source_dataset["dataset_key"],
                "source_dataset_name": source_dataset["dataset_name"],
                "source_sample_id": source_row["sample_id"],
                "source_user_query_ko": source_row["user_query_ko"],
                "source_query_surface_language": (source_row.get("metadata") or {}).get("query_surface_language"),
                "source_anchor_removed": True,
                "evaluation_focus": EVALUATION_FOCUS,
            }
        )

        rows.append(
            {
                "sample_id": f"{spec.sample_prefix}-{index:03d}",
                "split": source_row["split"],
                "query_language": "ko",
                "user_query_ko": query,
                "user_query_en": None,
                "dialog_context": source_row["dialog_context"],
                "expected_doc_ids": source_row["expected_doc_ids"],
                "expected_chunk_ids": source_row["expected_chunk_ids"],
                "expected_answer_key_points": source_row["expected_answer_key_points"],
                "query_category": source_row["query_category"],
                "difficulty": source_row["difficulty"],
                "single_or_multi_chunk": source_row["single_or_multi_chunk"],
                "source_product": source_row["source_product"],
                "source_version_if_available": source_row["source_version_if_available"],
                "target_method": target_method,
                "evaluation_focus": EVALUATION_FOCUS,
                "metadata": metadata,
            }
        )
    return rows


def _validate_rows(spec: DatasetSpec, rows: list[dict[str, Any]], source_rows: list[dict[str, Any]]) -> dict[str, Any]:
    issues: list[str] = []
    if len(rows) != 80:
        issues.append(f"row count mismatch: {len(rows)}")

    queries = [row["user_query_ko"] for row in rows]
    duplicates = [query for query, count in Counter(queries).items() if count > 1]
    if duplicates:
        issues.append(f"duplicate translated queries: {duplicates[:5]}")

    ascii_queries = [row["sample_id"] for row in rows if re.search(r"[A-Za-z]", row["user_query_ko"])]
    if ascii_queries:
        issues.append(f"queries still containing ASCII anchors: {ascii_queries[:10]}")

    missing_hangul = [row["sample_id"] for row in rows if not re.search(r"[가-힣]", row["user_query_ko"])]
    if missing_hangul:
        issues.append(f"queries without Hangul: {missing_hangul[:10]}")

    for row, source_row in zip(rows, source_rows):
        for field in ("expected_doc_ids", "expected_chunk_ids", "expected_answer_key_points"):
            if row[field] != source_row[field]:
                issues.append(f"{row['sample_id']} changed {field}")
        if not row["expected_chunk_ids"]:
            issues.append(f"{row['sample_id']} has no expected chunks")

    return {
        "status": "pass" if not issues else "fail",
        "issues": issues,
        "dataset_key": spec.dataset_key,
        "dataset_id": spec.dataset_id,
        "source_dataset_id": spec.source_dataset_id,
        "counts": {"rows": len(rows)},
        "single_multi_distribution": dict(Counter(row["single_or_multi_chunk"] for row in rows)),
        "target_method_distribution": dict(Counter(row["target_method"] for row in rows)),
        "ascii_query_count": len(ascii_queries),
    }


def _write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n", encoding="utf-8")


def _upsert_dataset(
    connection: psycopg.Connection[Any],
    spec: DatasetSpec,
    source_dataset: dict[str, Any],
    rows: list[dict[str, Any]],
) -> None:
    now = datetime.now(timezone.utc).isoformat()
    domain_id = source_dataset["domain_id"]
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
                spec.dataset_id,
                spec.dataset_key,
                spec.dataset_name,
                spec.description,
                VERSION_LABEL,
                "test_only",
                len(rows),
                Jsonb(dict(Counter(row["query_category"] for row in rows))),
                Jsonb(dict(Counter(row["single_or_multi_chunk"] for row in rows))),
                Jsonb(
                    {
                        "query_language": "ko",
                        "dataset_family": "anchor_translated_short_user_80",
                        "source_dataset_id": source_dataset["dataset_id"],
                        "source_dataset_key": source_dataset["dataset_key"],
                        "source_dataset_name": source_dataset["dataset_name"],
                        "source_dataset_preserved": True,
                        "source_file": str(spec.output_file).replace("\\", "/"),
                        "generation_mode": "source_dataset_anchor_translated_short_user_v1",
                        "query_surface_language": "ko_anchor_translated_short_user",
                        "anchor_translation_policy": ANCHOR_TRANSLATION_POLICY,
                        "evaluation_focus": EVALUATION_FOCUS,
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
                    domain_id,
                ),
            )

        cursor.execute("DELETE FROM eval_dataset_item WHERE dataset_id = %s", (spec.dataset_id,))
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
                (spec.dataset_id, row["sample_id"], row["query_category"], row["single_or_multi_chunk"], domain_id),
            )


def _select_specs(names: set[str] | None) -> list[DatasetSpec]:
    if not names:
        return list(DATASETS)
    selected = [spec for spec in DATASETS if spec.name in names]
    missing = sorted(names - {spec.name for spec in selected})
    if missing:
        raise RuntimeError(f"Unknown dataset names: {', '.join(missing)}")
    return selected


def run(
    *,
    dataset_names: set[str] | None,
    skip_db: bool,
    db_host: str,
    db_port: int,
    db_name: str,
    db_user: str,
    db_password: str,
) -> dict[str, Any]:
    results: list[dict[str, Any]] = []
    with psycopg.connect(
        host=db_host,
        port=db_port,
        dbname=db_name,
        user=db_user,
        password=db_password,
        autocommit=False,
    ) as connection:
        for spec in _select_specs(dataset_names):
            source_dataset, source_rows = _fetch_source_rows(connection, spec.source_dataset_id)
            rows = _build_rows(spec, source_dataset, source_rows)
            validation = _validate_rows(spec, rows, source_rows)
            if validation["status"] != "pass":
                raise RuntimeError(json.dumps(validation, ensure_ascii=False, indent=2))
            _write_jsonl(spec.output_file, rows)
            if not skip_db:
                _upsert_dataset(connection, spec, source_dataset, rows)
            results.append(
                {
                    "name": spec.name,
                    "output_file": str(spec.output_file),
                    "dataset_id": spec.dataset_id,
                    "dataset_key": spec.dataset_key,
                    "source_dataset_id": spec.source_dataset_id,
                    "validation": validation,
                }
            )
        if skip_db:
            connection.rollback()
        else:
            connection.commit()
    return {"version": VERSION_LABEL, "skip_db": skip_db, "results": results}


def main() -> None:
    parser = argparse.ArgumentParser(description="Build Spring/PostgreSQL anchor-translated KO eval datasets.")
    parser.add_argument("--dataset", action="append", choices=[spec.name for spec in DATASETS])
    parser.add_argument("--skip-db", action="store_true")
    parser.add_argument("--db-host", default="localhost")
    parser.add_argument("--db-port", type=int, default=5432)
    parser.add_argument("--db-name", default="query_forge")
    parser.add_argument("--db-user", default="query_forge")
    parser.add_argument("--db-password", default="query_forge")
    args = parser.parse_args()

    result = run(
        dataset_names=set(args.dataset) if args.dataset else None,
        skip_db=args.skip_db,
        db_host=args.db_host,
        db_port=args.db_port,
        db_name=args.db_name,
        db_user=args.db_user,
        db_password=args.db_password,
    )
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
