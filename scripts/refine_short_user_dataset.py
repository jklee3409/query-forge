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
DATASET_ID_DEFAULT = "b2d47254-8655-4c9c-81ac-7615677ec5bd"
SNAPSHOT_REPORT_DEFAULT = REPO_ROOT / "data" / "reports" / "short_user_current_dump_2026-05-13.json"
OUTPUT_FILE_DEFAULT = REPO_ROOT / "data" / "eval" / "human_eval_short_user_test_80.jsonl"
REPORT_FILE_DEFAULT = REPO_ROOT / "data" / "reports" / "short_user_dataset_80_refined_2026-05-13.json"
VERSION_LABEL = "v5-2026-05-13"
REFINEMENT_MODE = "manual_grounded_rewrite_v1"

TERM_RE = re.compile(r"@[A-Za-z][A-Za-z0-9_]+|[A-Za-z][A-Za-z0-9_.:/+-]{2,}|[가-힣]{2,}")
SPACE_RE = re.compile(r"\s+")

GENERIC_QUERY_TERMS = {
    "설정",
    "방법",
    "사용",
    "설명",
    "예시",
    "비교",
    "차이",
    "기본",
    "동작",
    "요약",
    "언제",
    "왜",
    "어디",
    "무엇",
    "가이드",
    "spring",
    "security",
    "framework",
    "boot",
    "data",
}

QUERY_TEXTS = [
    "DigestAuthenticationFilter Digest Authentication 설정?",
    "X.509 로그아웃 세션관리?",
    "X-Forwarded-Ssl X-Forwarded-Prefix 역할?",
    "JPA traversal point _ 사용?",
    "@AspectJ aop namespace?",
    "RestClient RestTemplate migration?",
    "oauth2Login loginPage redirectionEndpoint?",
    "OAuth2AccessTokenResponseHttpMessageConverter 커스터마이징?",
    "streaming long-lived connection 웹스택?",
    "인터페이스 projection?",
    "SpEL varargs type conversion?",
    "UnboundID LDAP 설정?",
    "WebTestClient MockMvc assertion?",
    "Spring Data Commons 4.0.4?",
    "HTTP Service Client 인터페이스?",
    "PropertyPathFactoryBean 값 참조?",
    "oauth2-client namespace 설정?",
    "EntityCallback sync reactive 차이?",
    "aop namespace context schema?",
    "PagedModel JSON page?",
    "DTO projection @PersistenceCreator?",
    "executable jar unpack?",
    "JmsTemplate JmsClient receive?",
    "partial projection entity view?",
    "@QuerydslPredicate web binding?",
    "MockMvc HtmlUnit 통합 이유?",
    "Spring Security source code?",
    "IoC Container BeanFactory 관계?",
    "RegisteredClient clientId grantType?",
    "Hibernate DAO transaction?",
    "cloudfoundryapplication endpoint?",
    "exchange asyncExchange 차이?",
    "saml2Metadata endpoint 발행?",
    "RunAsManager RunAsManagerImpl?",
    "@ManagedAttribute read-only 속성?",
    "RestClient customization?",
    "MvcTestResult JSON AssertJ?",
    "JwtIssuerReactiveAuthenticationManagerResolver multi-tenancy?",
    "OAuth2AuthorizedClientManager 기본 publish?",
    "AOT runtime hints 이유?",
    "@Proxyable proxy type?",
    "Sort.by and 정렬?",
    "POST 403 CSRF?",
    "Kotlin override property @Transient?",
    "ApplicationEventPublisher custom event?",
    "RepositoryMethodContext metadata expose?",
    "ProxyFactoryBean interceptorNames bean name?",
    "exchangeToMono status mapping?",
    "DefaultMessageListenerContainer JtaTransactionManager?",
    "OpenTelemetry OTLP Zipkin starter?",
    "OAuth2AccessTokenResponseClient bean 등록하면 manager?",
    "WebSocketMessageBrokerStats 30분 INFO?",
    "Repository marker interface?",
    "AbstractPreAuthenticatedProcessingFilter 사용자정보?",
    "xmlns:jms schemaLocation?",
    "logout JSESSIONID cookie 삭제?",
    "PropertySource 계층 검색?",
    "ReflectiveIndexAccessor custom structure?",
    "PartEvent multipart streaming?",
    "web mocks autowire?",
    "SecurityMockMvcRequestPostProcessors static import?",
    "OAuth2 prompt parameter?",
    "ExampleMatcher string matching?",
    "MultipartResolver multipart/form-data 파라미터?",
    "repository fragment spring.factories?",
    "concurrent session limit?",
    "cache-control header disable?",
    "AssertJ MockMvc exception handling?",
    "kotlin.version BOM?",
    "Repository include exclude filter?",
    "@Scheduled Kotlin suspend?",
    "Spring MVC Spring WebFlux 선택?",
    "matching-strategy ant-path-matcher?",
    "named pointcut composition?",
    "inner bean id scope?",
    "AOT @Table @Document @Entity?",
    "PlatformTransactionManager ReactiveTransactionManager?",
    "@Import @Bean dependency injection?",
    "multipart CSRF token body url?",
    "@Sql before after test?",
]

QUERY_OVERRIDES = {
    f"test-short-user-{index:03d}": query
    for index, query in enumerate(QUERY_TEXTS, start=1)
}

CHUNK_OVERRIDES = {
    "test-short-user-002": [
        "chk_48ed67233154801a",
        "chk_dc783d2192b9a2b6",
        "chk_5bb7bb07fe00b990",
    ],
    "test-short-user-005": [
        "chk_dfc7d7b7a123bf4c",
        "chk_7975e4dfe69cec70",
    ],
    "test-short-user-006": [
        "chk_dc7039c3af2da1bf",
        "chk_8e06e56857b6cad8",
    ],
    "test-short-user-007": [
        "chk_44fa7695b128233a",
        "chk_c253dac622600da1",
    ],
    "test-short-user-008": [
        "chk_8db418a9574e2183",
    ],
    "test-short-user-017": [
        "chk_a889d58213be7b38",
    ],
    "test-short-user-019": [
        "chk_dfc7d7b7a123bf4c",
        "chk_cf34048338b6558e",
    ],
    "test-short-user-021": [
        "chk_238d42a4c25d36e7",
    ],
    "test-short-user-030": [
        "chk_0b6f97073a915013",
        "chk_f6295715e06e611e",
    ],
    "test-short-user-031": [
        "chk_b25560e354daef75",
    ],
    "test-short-user-039": [
        "chk_18a75b33fb672614",
    ],
    "test-short-user-043": [
        "chk_0cc34a472c79ddde",
    ],
    "test-short-user-049": [
        "chk_0384938b729fb0ba",
    ],
    "test-short-user-050": [
        "chk_c44e3c0a38982d7b",
    ],
    "test-short-user-051": [
        "chk_a4e4d96073724641",
        "chk_18a75b33fb672614",
    ],
    "test-short-user-055": [
        "chk_431d2489f423fb33",
    ],
    "test-short-user-058": [
        "chk_a6b5861bc91b3946",
    ],
    "test-short-user-059": [
        "chk_6188403fc5369e40",
    ],
    "test-short-user-069": [
        "chk_10011feee53ee4d3",
    ],
    "test-short-user-071": [
        "chk_ad189f622b52d533",
    ],
    "test-short-user-077": [
        "chk_5dc3a5d90093ea5a",
    ],
}

CHUNK_CHANGE_REASONS = {
    "test-short-user-002": "요약 전용 summary chunk 하나로는 설정형 질의를 grounding 할 수 없어서 X.509, logout, session management 본문으로 재정렬했다.",
    "test-short-user-005": "AOP 소개문은 schema-based vs @AspectJ 질문을 답하지 못해 schema-based AOP와 @AspectJ support 본문으로 이동했다.",
    "test-short-user-006": "Client Request Factories chunk는 migration 의도를 설명하지 못해 REST clients overview와 RestTemplate migration 섹션으로 이동했다.",
    "test-short-user-007": "부분 overlap chunk 하나만으로는 loginPage와 redirectionEndpoint 둘 다 안정적으로 grounding 되지 않아 redirection endpoint 섹션을 함께 묶었다.",
    "test-short-user-008": "현재 chunk는 converter 예시를 overlap 문맥에만 걸고 있어 setAccessTokenResponseConverter를 직접 설명하는 response-parameters chunk로 옮겼다.",
    "test-short-user-017": "OAuth 2.0 client 설정 질문에 무관한 <headers> chunk를 제거하고 <oauth2-client> 설정 chunk만 유지했다.",
    "test-short-user-019": "util:set chunk는 AOP/context schema 의도와 무관해서 aop namespace와 context schema chunk로 교체했다.",
    "test-short-user-021": "Open Projections chunk는 생성자 선택 문제를 답하지 못해 @PersistenceCreator를 직접 언급하는 DTO projection chunk로 정리했다.",
    "test-short-user-030": "Spurious warnings chunk는 부수 이슈라서 DAO API + declarative transaction demarcation 조합으로 바꿨다.",
    "test-short-user-031": "custom context path chunk는 endpoint 질문을 흐려서 /cloudfoundryapplication route를 담은 base Cloud Foundry chunk만 남겼다.",
    "test-short-user-039": "기존 target은 default manager publication을 직접 설명하지 않아 automatic publication chunk로 재정렬했다.",
    "test-short-user-043": "session-loss FAQ chunk는 POST 403/CSRF와 무관해서 POST 보호 기본 동작을 설명하는 CSRF core chunk로 교체했다.",
    "test-short-user-049": "MessageListenerAdapter 설명은 부수 정보라서 JtaTransactionManager + DefaultMessageListenerContainer 조합을 직접 설명하는 transaction chunk만 유지했다.",
    "test-short-user-050": "logging correlation/custom spans는 starter dependency 질문을 답하지 못해 tracing overview/support chunk로 재정렬했다.",
    "test-short-user-051": "token response client bean 등록과 manager 자동 publish를 함께 grounding 하도록 customization chunk와 auto-publication chunk를 묶었다.",
    "test-short-user-055": "jee schema chunk는 완전히 오정렬되어 JMS Namespace Support chunk로 교체했다.",
    "test-short-user-058": "Indexing into Strings chunk는 incidental context여서 ReflectiveIndexAccessor를 설명하는 custom structures chunk만 남겼다.",
    "test-short-user-059": "기존 WebClient multipart chunks는 요청 조립 설명 위주라 PartEvent streaming 본문으로 이동했다.",
    "test-short-user-069": "일반 Kotlin Support intro는 BOM override 근거가 약해 kotlin.version override를 직접 적은 chunk만 남겼다.",
    "test-short-user-071": "TaskScheduler overview는 suspend 지원을 설명하지 않아 Kotlin suspending functions 전용 @Scheduled chunk로 교체했다.",
    "test-short-user-077": "두 번째 general transaction chunk는 노이즈여서 두 TransactionManager 타입을 직접 소개하는 첫 chunk만 유지했다.",
}


@dataclass(frozen=True)
class ChunkInfo:
    chunk_id: str
    document_id: str
    section_path_text: str
    chunk_text: str
    source_id: str
    version_label: str


def _normalize(text: str) -> str:
    return SPACE_RE.sub(" ", (text or "").strip())


def _load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def _extract_terms(*values: str, max_terms: int = 8) -> list[str]:
    seen: set[str] = set()
    terms: list[str] = []
    for value in values:
        for raw in TERM_RE.findall(value or ""):
            term = raw.strip("`\"'()[]{}<>.,;:")
            if len(term) < 2:
                continue
            lowered = term.lower()
            if lowered in GENERIC_QUERY_TERMS:
                continue
            if lowered in seen:
                continue
            seen.add(lowered)
            terms.append(term)
            if len(terms) >= max_terms:
                return terms
    return terms


def _clean_chunk_text(text: str) -> str:
    lines: list[str] = []
    for raw_line in (text or "").replace("\r\n", "\n").replace("\r", "\n").split("\n"):
        line = raw_line.strip()
        if not line:
            continue
        if line.startswith("Overlap context from previous chunk:"):
            continue
        if line.startswith("Section Path:"):
            continue
        if line in {"```java", "```kotlin", "```xml", "```yaml", "```properties", "```", "- Java", "- Kotlin", "- Xml", "- YAML", "- Properties"}:
            continue
        lines.append(line.replace("```", " "))
    return _normalize(" ".join(lines))


def _select_snippet(text: str, terms: list[str], *, max_chars: int = 520) -> str:
    cleaned = _normalize(text)
    if len(cleaned) <= max_chars:
        return cleaned

    lowered = cleaned.lower()
    match_positions = [lowered.find(term.lower()) for term in terms if term and lowered.find(term.lower()) >= 0]
    if match_positions:
        start = max(0, min(match_positions) - 140)
    else:
        start = 0
    end = min(len(cleaned), start + max_chars)
    snippet = cleaned[start:end]
    if start > 0:
        snippet = "... " + snippet
    if end < len(cleaned):
        snippet = snippet + " ..."
    return snippet


def _build_expected_points(
    *,
    query_text: str,
    source_query_text: str,
    chunk_infos: list[ChunkInfo],
) -> list[str]:
    anchor_terms = _extract_terms(query_text, source_query_text, max_terms=10)
    points: list[str] = []
    for chunk in chunk_infos:
        snippet = _select_snippet(_clean_chunk_text(chunk.chunk_text), anchor_terms)
        if snippet:
            points.append(f"Section Path: {chunk.section_path_text}. {snippet}")
        else:
            points.append(f"Section Path: {chunk.section_path_text}")
    return points


def _term_overlap(query_text: str, chunk_infos: list[ChunkInfo]) -> float:
    terms = _extract_terms(query_text, max_terms=8)
    if not terms:
        return 0.0
    haystack = _normalize(" ".join(chunk.section_path_text + " " + chunk.chunk_text for chunk in chunk_infos)).lower()
    matched = sum(1 for term in terms if term.lower() in haystack)
    return round(matched / len(terms), 4)


def _connect(*, db_host: str, db_port: int, db_name: str, db_user: str, db_password: str) -> psycopg.Connection[Any]:
    return psycopg.connect(
        host=db_host,
        port=db_port,
        dbname=db_name,
        user=db_user,
        password=db_password,
        autocommit=False,
    )


def _fetch_dataset_meta(connection: psycopg.Connection[Any], dataset_id: str) -> dict[str, Any]:
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
        "metadata": dict(row[5] or {}),
    }


def _fetch_active_db_context(connection: psycopg.Connection[Any], dataset_id: str) -> dict[str, dict[str, Any]]:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT es.sample_id,
                   es.split,
                   es.user_query_en,
                   es.query_language,
                   es.dialog_context
            FROM eval_dataset_item edi
            JOIN eval_samples es
              ON es.sample_id = edi.sample_id
            WHERE edi.dataset_id = %s
              AND edi.active = TRUE
            ORDER BY es.sample_id
            """,
            (dataset_id,),
        )
        rows = cursor.fetchall()
    return {
        str(row[0]): {
            "split": str(row[1] or "test"),
            "user_query_en": str(row[2] or ""),
            "query_language": str(row[3] or ""),
            "dialog_context": dict(row[4] or {}),
        }
        for row in rows
    }


def _fetch_chunk_map(connection: psycopg.Connection[Any], chunk_ids: list[str]) -> dict[str, ChunkInfo]:
    unique_chunk_ids = list(dict.fromkeys(chunk_ids))
    if not unique_chunk_ids:
        return {}
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
            (unique_chunk_ids,),
        )
        rows = cursor.fetchall()
    return {
        str(row[0]): ChunkInfo(
            chunk_id=str(row[0]),
            document_id=str(row[1]),
            section_path_text=str(row[2] or ""),
            chunk_text=str(row[3] or ""),
            source_id=str(row[4] or ""),
            version_label=str(row[5] or ""),
        )
        for row in rows
    }


def _fetch_existing_synthetic_ids(connection: psycopg.Connection[Any], synthetic_ids: list[str]) -> set[str]:
    unique_ids = list(dict.fromkeys(synthetic_ids))
    if not unique_ids:
        return set()
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT synthetic_query_id::text
            FROM synthetic_queries_raw_all
            WHERE synthetic_query_id = ANY(%s)
            """,
            (unique_ids,),
        )
        rows = cursor.fetchall()
    return {str(row[0]) for row in rows}


def _load_pre_audit(pre_audit_path: Path | None) -> dict[str, dict[str, Any]]:
    if not pre_audit_path or not pre_audit_path.exists():
        return {}
    report = _load_json(pre_audit_path)
    flagged_samples = report.get("flagged_samples") or []
    return {
        str(item["sample_id"]): {
            "flags": [str(flag["type"]) for flag in (item.get("flags") or [])],
            "reasons": [str(flag["reason"]) for flag in (item.get("flags") or [])],
        }
        for item in flagged_samples
        if item.get("sample_id")
    }


def _validate_override_coverage(base_rows: list[dict[str, Any]]) -> None:
    sample_ids = {str(row["sample_id"]) for row in base_rows}
    if set(QUERY_OVERRIDES) != sample_ids:
        missing = sorted(sample_ids - set(QUERY_OVERRIDES))
        extra = sorted(set(QUERY_OVERRIDES) - sample_ids)
        raise RuntimeError(
            f"query override coverage mismatch: missing={missing[:5]} extra={extra[:5]}"
        )
    unknown_chunk_override_ids = sorted(set(CHUNK_OVERRIDES) - sample_ids)
    if unknown_chunk_override_ids:
        raise RuntimeError(f"chunk override sample ids not found in dataset: {unknown_chunk_override_ids}")


def _build_refined_rows(
    *,
    snapshot_rows: list[dict[str, Any]],
    db_context: dict[str, dict[str, Any]],
    chunk_map: dict[str, ChunkInfo],
    dataset_key: str,
    refined_at: str,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], dict[str, Any]]:
    refined_rows: list[dict[str, Any]] = []
    sample_report_rows: list[dict[str, Any]] = []
    invalid_chunk_ids: list[str] = []
    invalid_product_samples: list[str] = []
    invalid_version_samples: list[str] = []
    changed_chunk_count = 0
    changed_doc_count = 0

    for snapshot_row in snapshot_rows:
        sample_id = str(snapshot_row["sample_id"])
        if sample_id not in db_context:
            raise RuntimeError(f"active DB context missing sample: {sample_id}")
        query_text = QUERY_OVERRIDES[sample_id]
        before_chunk_ids = [str(chunk_id) for chunk_id in snapshot_row["expected_chunk_ids"]]
        after_chunk_ids = [str(chunk_id) for chunk_id in CHUNK_OVERRIDES.get(sample_id, before_chunk_ids)]
        if before_chunk_ids != after_chunk_ids:
            changed_chunk_count += 1

        chunk_infos: list[ChunkInfo] = []
        for chunk_id in after_chunk_ids:
            chunk_info = chunk_map.get(chunk_id)
            if not chunk_info:
                invalid_chunk_ids.append(chunk_id)
                continue
            chunk_infos.append(chunk_info)
        if len(chunk_infos) != len(after_chunk_ids):
            continue

        after_doc_ids = list(dict.fromkeys(chunk.document_id for chunk in chunk_infos))
        before_doc_ids = [str(doc_id) for doc_id in snapshot_row["expected_doc_ids"]]
        if before_doc_ids != after_doc_ids:
            changed_doc_count += 1

        source_product = str(snapshot_row.get("source_product") or "")
        source_version = str(snapshot_row.get("source_version_if_available") or "")
        if any(chunk.source_id != source_product for chunk in chunk_infos):
            invalid_product_samples.append(sample_id)
        if source_version and any(chunk.version_label != source_version for chunk in chunk_infos):
            invalid_version_samples.append(sample_id)

        metadata = dict(snapshot_row.get("metadata") or {})
        metadata["dataset_key"] = metadata.get("dataset_key") or dataset_key
        metadata["query_style"] = metadata.get("query_style") or "short_user"
        metadata["refined_at"] = refined_at
        metadata["refinement_mode"] = REFINEMENT_MODE
        metadata["baseline_snapshot_report"] = str(SNAPSHOT_REPORT_DEFAULT.relative_to(REPO_ROOT)).replace("\\", "/")

        source_query_text = str(((snapshot_row.get("source_synthetic") or {}).get("query_text")) or "")
        expected_points = _build_expected_points(
            query_text=query_text,
            source_query_text=source_query_text,
            chunk_infos=chunk_infos,
        )

        row = {
            "sample_id": sample_id,
            "split": db_context[sample_id]["split"],
            "user_query_ko": query_text,
            "dialog_context": db_context[sample_id]["dialog_context"],
            "expected_doc_ids": after_doc_ids,
            "expected_chunk_ids": after_chunk_ids,
            "expected_answer_key_points": expected_points,
            "query_category": str(snapshot_row["query_category"]),
            "difficulty": str(snapshot_row["difficulty"]),
            "single_or_multi_chunk": "multi" if len(after_chunk_ids) > 1 else "single",
            "source_product": snapshot_row.get("source_product"),
            "source_version_if_available": snapshot_row.get("source_version_if_available"),
            "metadata": metadata,
        }
        refined_rows.append(row)

        before_chunk_infos = [chunk_map[str(chunk_id)] for chunk_id in before_chunk_ids if str(chunk_id) in chunk_map]
        sample_report_rows.append(
            {
                "sample_id": sample_id,
                "source_synthetic_query_id": metadata.get("source_synthetic_query_id"),
                "source_generation_strategy": metadata.get("source_generation_strategy"),
                "source_query_type": metadata.get("source_query_type"),
                "source_synthetic_query_text": source_query_text,
                "before_query": str(snapshot_row["user_query_ko"]),
                "after_query": query_text,
                "before_expected_chunk_ids": before_chunk_ids,
                "after_expected_chunk_ids": after_chunk_ids,
                "before_expected_doc_ids": before_doc_ids,
                "after_expected_doc_ids": after_doc_ids,
                "before_sections": [chunk.section_path_text for chunk in before_chunk_infos],
                "after_sections": [chunk.section_path_text for chunk in chunk_infos],
                "changed_expected_chunk_ids": before_chunk_ids != after_chunk_ids,
                "changed_expected_doc_ids": before_doc_ids != after_doc_ids,
                "before_query_chunk_overlap": _term_overlap(str(snapshot_row["user_query_ko"]), before_chunk_infos),
                "after_query_chunk_overlap": _term_overlap(query_text, chunk_infos),
            }
        )

    if invalid_chunk_ids:
        raise RuntimeError(f"missing chunk ids referenced by overrides: {sorted(set(invalid_chunk_ids))}")
    if invalid_product_samples:
        raise RuntimeError(f"source_product mismatch in refined chunks: {sorted(set(invalid_product_samples))}")
    if invalid_version_samples:
        raise RuntimeError(f"source_version mismatch in refined chunks: {sorted(set(invalid_version_samples))}")

    summary = {
        "changed_query_count": len(refined_rows),
        "changed_chunk_count": changed_chunk_count,
        "changed_doc_count": changed_doc_count,
        "single_count": sum(1 for row in refined_rows if row["single_or_multi_chunk"] == "single"),
        "multi_count": sum(1 for row in refined_rows if row["single_or_multi_chunk"] == "multi"),
    }
    return refined_rows, sample_report_rows, summary


def _write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n",
        encoding="utf-8",
    )


def _upsert_eval_samples(connection: psycopg.Connection[Any], rows: list[dict[str, Any]]) -> None:
    with connection.cursor() as cursor:
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
                    Jsonb(row["metadata"]),
                ),
            )


def _refresh_dataset_items(connection: psycopg.Connection[Any], dataset_id: str, rows: list[dict[str, Any]]) -> None:
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
    dataset_metadata: dict[str, Any],
    total_items: int,
    single_count: int,
    multi_count: int,
    output_file: Path,
    report_file: Path,
    refined_at: str,
) -> None:
    metadata = dict(dataset_metadata)
    metadata["query_language"] = "ko"
    metadata["source_file"] = str(output_file.relative_to(REPO_ROOT)).replace("\\", "/")
    metadata["refined_report"] = str(report_file.relative_to(REPO_ROOT)).replace("\\", "/")
    metadata["refined_at"] = refined_at
    metadata["refinement_mode"] = REFINEMENT_MODE

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
                "기존 synthetic provenance를 유지한 채 짧은 한국어 developer query와 grounded expected chunks를 수동 정제한 retrieval-aware short-user 평가셋 (80문항)",
                VERSION_LABEL,
                total_items,
                Jsonb({"short_user": total_items}),
                Jsonb({"single": single_count, "multi": multi_count}),
                Jsonb(metadata),
                dataset_id,
            ),
        )


def _build_report(
    *,
    dataset_meta_before: dict[str, Any],
    refined_rows: list[dict[str, Any]],
    sample_report_rows: list[dict[str, Any]],
    pre_audit_map: dict[str, dict[str, Any]],
    output_file: Path,
    report_file: Path,
    refined_at: str,
    synthetic_id_validation: dict[str, Any],
) -> dict[str, Any]:
    flag_counter: Counter[str] = Counter()
    retrieval_problem_count = 0
    rewrite_problem_count = 0
    grounding_problem_count = 0
    generation_problem_count = 0
    chunk_change_rows: list[dict[str, Any]] = []

    enriched_rows: list[dict[str, Any]] = []
    for row in sample_report_rows:
        pre_audit = pre_audit_map.get(row["sample_id"], {})
        flags = [str(flag) for flag in (pre_audit.get("flags") or [])]
        reasons = [str(reason) for reason in (pre_audit.get("reasons") or [])]
        flag_counter.update(flags)

        retrieval_problem = any(flag in {"B", "D"} for flag in flags)
        rewrite_problem = any(flag in {"A", "E", "F"} for flag in flags)
        grounding_problem = retrieval_problem or row["changed_expected_chunk_ids"]
        generation_problem = "C" in flags
        if retrieval_problem:
            retrieval_problem_count += 1
        if rewrite_problem:
            rewrite_problem_count += 1
        if grounding_problem:
            grounding_problem_count += 1
        if generation_problem:
            generation_problem_count += 1

        overlap_delta = round(
            float(row["after_query_chunk_overlap"]) - float(row["before_query_chunk_overlap"]),
            4,
        )
        row_report = {
            **row,
            "before_flags": flags,
            "before_flag_reasons": reasons,
            "retrieval_problem": retrieval_problem,
            "rewrite_problem": rewrite_problem,
            "grounding_problem": grounding_problem,
            "generation_oriented_problem": generation_problem,
            "chunk_change_reason": CHUNK_CHANGE_REASONS.get(row["sample_id"]),
            "query_chunk_overlap_delta": overlap_delta,
        }
        enriched_rows.append(row_report)
        if row["changed_expected_chunk_ids"]:
            chunk_change_rows.append(
                {
                    "sample_id": row["sample_id"],
                    "before_expected_chunk_ids": row["before_expected_chunk_ids"],
                    "after_expected_chunk_ids": row["after_expected_chunk_ids"],
                    "before_sections": row["before_sections"],
                    "after_sections": row["after_sections"],
                    "reason": CHUNK_CHANGE_REASONS.get(row["sample_id"]),
                }
            )

    largest_overlap_gains = sorted(
        (
            {
                "sample_id": row["sample_id"],
                "before_query": row["before_query"],
                "after_query": row["after_query"],
                "before_query_chunk_overlap": row["before_query_chunk_overlap"],
                "after_query_chunk_overlap": row["after_query_chunk_overlap"],
                "query_chunk_overlap_delta": row["query_chunk_overlap_delta"],
            }
            for row in enriched_rows
        ),
        key=lambda item: item["query_chunk_overlap_delta"],
        reverse=True,
    )[:10]

    report = {
        "dataset_before": dataset_meta_before,
        "dataset_after": {
            "dataset_id": dataset_meta_before["dataset_id"],
            "dataset_key": dataset_meta_before["dataset_key"],
            "dataset_name": "짧은 사용자 질의 평가 데이터셋 (80문항)",
            "version": VERSION_LABEL,
            "total_items": len(refined_rows),
            "metadata": {
                **dict(dataset_meta_before.get("metadata") or {}),
                "query_language": "ko",
                "source_file": str(output_file.relative_to(REPO_ROOT)).replace("\\", "/"),
                "refined_report": str(report_file.relative_to(REPO_ROOT)).replace("\\", "/"),
                "refined_at": refined_at,
                "refinement_mode": REFINEMENT_MODE,
            },
        },
        "refined_at": refined_at,
        "refinement_mode": REFINEMENT_MODE,
        "sample_count": len(refined_rows),
        "problem_stats_before": {
            "flag_counts": dict(flag_counter),
            "retrieval_problem_count": retrieval_problem_count,
            "rewrite_problem_count": rewrite_problem_count,
            "grounding_problem_count": grounding_problem_count,
            "generation_oriented_problem_count": generation_problem_count,
        },
        "change_summary": {
            "changed_query_count": len(refined_rows),
            "changed_expected_chunk_count": len(chunk_change_rows),
            "changed_expected_doc_count": sum(1 for row in enriched_rows if row["changed_expected_doc_ids"]),
            "single_count_after": sum(1 for row in refined_rows if row["single_or_multi_chunk"] == "single"),
            "multi_count_after": sum(1 for row in refined_rows if row["single_or_multi_chunk"] == "multi"),
        },
        "changed_expected_chunk_ids": chunk_change_rows,
        "largest_query_chunk_overlap_gains": largest_overlap_gains,
        "synthetic_provenance_validation": synthetic_id_validation,
        "samples": enriched_rows,
    }
    return report


def run(
    *,
    dataset_id: str,
    snapshot_report: Path,
    pre_audit_report: Path | None,
    output_file: Path,
    report_file: Path,
    skip_db: bool,
    db_host: str,
    db_port: int,
    db_name: str,
    db_user: str,
    db_password: str,
) -> dict[str, Any]:
    snapshot_rows = _load_json(snapshot_report)
    if not isinstance(snapshot_rows, list) or not snapshot_rows:
        raise RuntimeError(f"snapshot report must contain a non-empty list: {snapshot_report}")
    _validate_override_coverage(snapshot_rows)

    dataset_meta_before: dict[str, Any]
    db_context: dict[str, dict[str, Any]]
    chunk_map: dict[str, ChunkInfo]
    synthetic_id_validation: dict[str, Any]
    refined_rows: list[dict[str, Any]]
    sample_report_rows: list[dict[str, Any]]
    summary: dict[str, Any]
    refined_at = datetime.now(timezone.utc).isoformat()

    with _connect(
        db_host=db_host,
        db_port=db_port,
        db_name=db_name,
        db_user=db_user,
        db_password=db_password,
    ) as connection:
        dataset_meta_before = _fetch_dataset_meta(connection, dataset_id)
        db_context = _fetch_active_db_context(connection, dataset_id)

        if set(db_context) != {str(row["sample_id"]) for row in snapshot_rows}:
            missing_in_db = sorted({str(row["sample_id"]) for row in snapshot_rows} - set(db_context))
            extra_in_db = sorted(set(db_context) - {str(row["sample_id"]) for row in snapshot_rows})
            raise RuntimeError(f"dataset/sample snapshot mismatch: missing_in_db={missing_in_db[:5]} extra_in_db={extra_in_db[:5]}")

        all_chunk_ids = sorted(
            {
                str(chunk_id)
                for row in snapshot_rows
                for chunk_id in (CHUNK_OVERRIDES.get(str(row["sample_id"])) or row["expected_chunk_ids"])
            }
        )
        chunk_map = _fetch_chunk_map(connection, all_chunk_ids)

        refined_rows, sample_report_rows, summary = _build_refined_rows(
            snapshot_rows=snapshot_rows,
            db_context=db_context,
            chunk_map=chunk_map,
            dataset_key=dataset_meta_before["dataset_key"],
            refined_at=refined_at,
        )

        synthetic_ids = [
            str((row.get("metadata") or {}).get("source_synthetic_query_id"))
            for row in snapshot_rows
            if (row.get("metadata") or {}).get("source_synthetic_query_id")
        ]
        existing_synthetic_ids = _fetch_existing_synthetic_ids(connection, synthetic_ids)
        missing_synthetic_ids = sorted(set(synthetic_ids) - existing_synthetic_ids)
        synthetic_id_validation = {
            "expected_count": len(set(synthetic_ids)),
            "found_count": len(existing_synthetic_ids),
            "missing_ids": missing_synthetic_ids,
        }
        if missing_synthetic_ids:
            raise RuntimeError(f"missing synthetic provenance ids: {missing_synthetic_ids[:5]}")

        _write_jsonl(output_file, refined_rows)

        if not skip_db:
            _upsert_eval_samples(connection, refined_rows)
            _refresh_dataset_items(connection, dataset_id, refined_rows)
            _update_dataset_meta(
                connection,
                dataset_id=dataset_id,
                dataset_metadata=dataset_meta_before["metadata"],
                total_items=len(refined_rows),
                single_count=summary["single_count"],
                multi_count=summary["multi_count"],
                output_file=output_file,
                report_file=report_file,
                refined_at=refined_at,
            )
            connection.commit()
        else:
            connection.rollback()

    pre_audit_map = _load_pre_audit(pre_audit_report)
    report = _build_report(
        dataset_meta_before=dataset_meta_before,
        refined_rows=refined_rows,
        sample_report_rows=sample_report_rows,
        pre_audit_map=pre_audit_map,
        output_file=output_file,
        report_file=report_file,
        refined_at=refined_at,
        synthetic_id_validation=synthetic_id_validation,
    )
    report_file.parent.mkdir(parents=True, exist_ok=True)
    report_file.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    return {
        "dataset_id": dataset_id,
        "dataset_key": dataset_meta_before["dataset_key"],
        "dataset_version_before": dataset_meta_before["version"],
        "dataset_version_after": VERSION_LABEL,
        "sample_count": len(refined_rows),
        "changed_query_count": summary["changed_query_count"],
        "changed_expected_chunk_count": summary["changed_chunk_count"],
        "changed_expected_doc_count": summary["changed_doc_count"],
        "single_count_after": summary["single_count"],
        "multi_count_after": summary["multi_count"],
        "output_file": str(output_file),
        "report_file": str(report_file),
        "skip_db": skip_db,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Refine the short-user-80 Korean eval dataset with stronger grounding.")
    parser.add_argument("--dataset-id", default=DATASET_ID_DEFAULT)
    parser.add_argument("--snapshot-report", default=str(SNAPSHOT_REPORT_DEFAULT))
    parser.add_argument("--pre-audit-report")
    parser.add_argument("--output-file", default=str(OUTPUT_FILE_DEFAULT))
    parser.add_argument("--report-file", default=str(REPORT_FILE_DEFAULT))
    parser.add_argument("--skip-db", action="store_true")
    parser.add_argument("--db-host", default="localhost")
    parser.add_argument("--db-port", type=int, default=5432)
    parser.add_argument("--db-name", default="query_forge")
    parser.add_argument("--db-user", default="query_forge")
    parser.add_argument("--db-password", default="query_forge")
    args = parser.parse_args()

    summary = run(
        dataset_id=args.dataset_id,
        snapshot_report=Path(args.snapshot_report),
        pre_audit_report=Path(args.pre_audit_report) if args.pre_audit_report else None,
        output_file=Path(args.output_file),
        report_file=Path(args.report_file),
        skip_db=bool(args.skip_db),
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
