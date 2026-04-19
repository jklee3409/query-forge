from __future__ import annotations

import argparse
import json
import random
import re
from collections import defaultdict, deque
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import psycopg
from psycopg.types.json import Jsonb


WHITESPACE_PATTERN = re.compile(r"\s+")
SENTENCE_SPLIT_PATTERN = re.compile(r"(?<=[.!?])\s+")
KOREAN_CHAR_PATTERN = re.compile(r"[가-힣]")
QUERY_TOKEN_PATTERN = re.compile(r"[A-Za-z][A-Za-z0-9_./-]{2,}|[가-힣]{2,}")
IDENTIFIER_PATTERN = re.compile(
    r"@[A-Za-z][A-Za-z0-9_]+|"
    r"[A-Z][A-Za-z0-9]+(?:[A-Z][A-Za-z0-9]+)*|"
    r"[a-z]+(?:[A-Z][A-Za-z0-9]+)+|"
    r"[A-Za-z][A-Za-z0-9_.-]{3,}"
)

GENERIC_TERMS = {
    "section",
    "path",
    "overlap",
    "context",
    "chunk",
    "previous",
    "next",
    "example",
    "examples",
    "using",
    "user",
    "users",
    "default",
    "defaults",
    "guide",
    "reference",
    "table",
    "list",
    "lists",
    "code",
    "spring",
    "framework",
    "security",
    "boot",
    "data",
    "jpa",
    "web",
    "reactive",
    "servlet",
    "from",
    "with",
    "into",
    "that",
    "this",
    "when",
    "where",
    "what",
    "which",
    "creating",
    "custom",
    "functional",
    "supported",
    "object",
    "mapping",
    "metrics",
    "error",
    "errors",
    "response",
    "responses",
    "preventing",
    "overview",
    "introduction",
    "getting",
    "started",
    "basic",
    "advanced",
    "features",
    "properties",
    "configuration",
    "annotations",
    "types",
    "api",
    "core",
    "common",
    "note",
    "tips",
    "tip",
}

KOREAN_STOPWORDS = {
    "무엇",
    "방법",
    "설정",
    "사용",
    "적용",
    "설명",
    "기본",
    "동작",
    "왜",
    "어디",
    "언제",
    "어떻게",
}

LOWERCASE_TECH_TERMS = {
    "csrf",
    "cors",
    "jwt",
    "jpa",
    "jdbc",
    "jndi",
    "oauth2",
    "saml2",
    "x509",
    "json",
    "yaml",
    "xml",
    "http",
    "https",
    "rest",
    "cache",
    "redis",
    "kafka",
    "rabbitmq",
    "mongodb",
    "postgresql",
    "mysql",
    "h2",
}

UPPERCASE_ALLOWED_TERMS = {
    "CSRF",
    "CORS",
    "JWT",
    "JPA",
    "JDBC",
    "JSON",
    "YAML",
    "XML",
    "HTTP",
    "HTTPS",
    "AOT",
    "JNDI",
    "X509",
}

TITLECASE_TECH_TERMS = {
    "Actuator",
    "WebFlux",
    "WebMvc",
    "DataSource",
    "JdbcTemplate",
    "SecurityContext",
    "SecurityFilterChain",
    "Authentication",
    "Authorization",
    "PathPattern",
    "TransactionManager",
    "Micrometer",
    "GraalVM",
    "NativeImage",
    "AOT",
    "RSocket",
}

SINGLE_QUERY_TEMPLATES_PRIMARY = [
    "{t1} 기본 설정 뭐야?",
    "{t1} 언제 써?",
    "{t1} 설정 포인트 뭐야?",
    "{t1} 주의점 뭐야?",
    "{t1} 동작 원리 간단히 알려줘?",
    "{t1} 실무에서 보통 어떻게 써?",
]

SINGLE_QUERY_TEMPLATES_PAIR = [
    "{t1}랑 {t2} 차이 뭐야?",
    "{t1}에서 {t2}는 왜 필요해?",
    "{t1} 설정할 때 {t2}도 같이 봐야 해?",
    "{t1}랑 {t2} 같이 쓸 때 핵심 포인트 뭐야?",
]

MULTI_QUERY_TEMPLATES = [
    "{t1} 개념부터 설정까지 한 번에 정리해줘?",
    "{t1} 설정이랑 예시 같이 설명해줘?",
    "{t1} 적용 순서랑 주의점 같이 알려줘?",
    "{t1} 처음 적용할 때 순서대로 뭐 보면 돼?",
]


@dataclass(frozen=True)
class ChunkCandidate:
    chunk_id: str
    document_id: str
    section_path_text: str
    chunk_text: str
    token_len: int
    previous_chunk_id: str | None
    next_chunk_id: str | None
    source_id: str | None
    version_label: str | None
    title: str | None


def _normalize_query_text(text: str) -> str:
    return WHITESPACE_PATTERN.sub(" ", (text or "").strip()).lower()


def _normalize_whitespace(text: str) -> str:
    return WHITESPACE_PATTERN.sub(" ", (text or "").strip())


def _normalize_term(term: str) -> str:
    cleaned = term.strip().strip("`\"'()[]{}<>.,;:")
    if not cleaned:
        return ""
    if cleaned.startswith("@"):
        if len(cleaned) < 5:
            return ""
        if len(cleaned) >= 2 and not cleaned[1].isalpha():
            return ""
    if " " in cleaned:
        return ""
    if "(" in cleaned or ")" in cleaned:
        return ""
    if cleaned.lower().startswith(("this.", "that.", "it.")):
        return ""
    if ".set" in cleaned.lower() or ".get" in cleaned.lower():
        return ""
    if "." in cleaned and not cleaned.startswith("@"):
        segments = [segment for segment in cleaned.split(".") if segment]
        if not segments:
            return ""
        if len(segments) == 2 and segments[0].islower() and segments[1][:1].isupper():
            if segments[0] not in {"spring", "org", "java", "javax", "jakarta"}:
                return ""
        for seg in segments:
            if seg.lower() in {"happened", "therefore", "however", "whereas", "property", "population"}:
                return ""
    if len(cleaned) <= 2:
        return ""
    if cleaned.isdigit():
        return ""
    lower = cleaned.lower()
    if re.fullmatch(r"[a-z][A-Za-z0-9]+", cleaned) and lower not in LOWERCASE_TECH_TERMS:
        return ""
    if KOREAN_CHAR_PATTERN.search(cleaned):
        if lower in KOREAN_STOPWORDS:
            return ""
        return cleaned
    if lower == cleaned and not cleaned.startswith("@") and lower not in LOWERCASE_TECH_TERMS:
        return ""
    if re.fullmatch(r"[A-Z][a-z]+", cleaned) and cleaned not in TITLECASE_TECH_TERMS:
        return ""
    if cleaned.isupper() and cleaned not in UPPERCASE_ALLOWED_TERMS:
        return ""
    if lower in GENERIC_TERMS and not cleaned.startswith("@") and not any(ch.isupper() for ch in cleaned[1:]):
        return ""
    if len(cleaned) > 40:
        return ""
    return cleaned


def _has_common_suffix(term: str) -> str:
    for suffix in ("Manager", "Repository", "Filter", "Configurer", "Template", "Context", "Factory", "Client"):
        if term.endswith(suffix):
            return suffix
    return ""


def _is_pair_compatible(left: str, right: str) -> bool:
    if not left or not right:
        return False
    if left.lower() == right.lower():
        return False
    if left.startswith("@") and right.startswith("@"):
        return True
    if left.lower() in LOWERCASE_TECH_TERMS and right.lower() in LOWERCASE_TECH_TERMS:
        return True
    left_suffix = _has_common_suffix(left)
    right_suffix = _has_common_suffix(right)
    if left_suffix and left_suffix == right_suffix:
        return True
    if len(left) >= 6 and len(right) >= 6 and left[:5].lower() == right[:5].lower():
        return True
    return False


def _rotate_templates(templates: list[str], seed_text: str) -> list[str]:
    if not templates:
        return []
    offset = sum(ord(ch) for ch in seed_text) % len(templates)
    return templates[offset:] + templates[:offset]


def _is_query_quality_acceptable(query: str) -> bool:
    normalized = _normalize_whitespace(query)
    if len(normalized) < 10 or len(normalized) > 56:
        return False
    if not KOREAN_CHAR_PATTERN.search(normalized):
        return False
    tokens = QUERY_TOKEN_PATTERN.findall(normalized)
    if len(tokens) < 2:
        return False
    return True


def _extract_keypoint(chunk_text: str) -> str:
    normalized = _normalize_whitespace(chunk_text)
    if not normalized:
        return ""
    first_sentence = SENTENCE_SPLIT_PATTERN.split(normalized)[0].strip()
    if len(first_sentence) > 280:
        first_sentence = first_sentence[:280].rstrip() + "..."
    return first_sentence


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
    return row[0], row[1]


def _fetch_dataset_rows(connection: psycopg.Connection[Any], dataset_id: str) -> list[dict[str, Any]]:
    with connection.cursor() as cursor:
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
                   s.single_or_multi_chunk
            FROM eval_dataset_item edi
            JOIN eval_samples s ON s.sample_id = edi.sample_id
            WHERE edi.dataset_id = %s
              AND edi.active = TRUE
            ORDER BY s.sample_id
            """,
            (dataset_id,),
        )
        rows = cursor.fetchall()
    return [
        {
            "sample_id": row[0],
            "user_query_ko": row[1],
            "expected_doc_ids": list(row[2] or []),
            "expected_chunk_ids": list(row[3] or []),
            "expected_answer_key_points": list(row[4] or []),
            "source_product": row[5],
            "source_version_if_available": row[6],
            "query_category": row[7],
            "difficulty": row[8],
            "single_or_multi_chunk": row[9],
        }
        for row in rows
    ]


def _fetch_chunks(
    connection: psycopg.Connection[Any],
    chunk_ids: list[str],
) -> dict[str, tuple[str, str]]:
    if not chunk_ids:
        return {}
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT chunk_id, document_id, chunk_text
            FROM corpus_chunks
            WHERE chunk_id = ANY(%s)
            """,
            (chunk_ids,),
        )
        rows = cursor.fetchall()
    return {row[0]: (row[1], row[2] or "") for row in rows}


def _audit_mapping(
    connection: psycopg.Connection[Any],
    rows: list[dict[str, Any]],
) -> dict[str, Any]:
    structural_issues: list[dict[str, Any]] = []
    low_overlap_warnings: list[dict[str, Any]] = []
    token_overlap_scores: list[float] = []

    for row in rows:
        sample_id = row["sample_id"]
        query = row["user_query_ko"] or ""
        expected_doc_ids = set(row["expected_doc_ids"] or [])
        expected_chunk_ids = list(row["expected_chunk_ids"] or [])

        if not expected_chunk_ids:
            structural_issues.append({"sample_id": sample_id, "type": "empty_expected_chunk_ids"})
            continue

        chunk_map = _fetch_chunks(connection, expected_chunk_ids)
        if len(chunk_map) != len(expected_chunk_ids):
            missing = [chunk_id for chunk_id in expected_chunk_ids if chunk_id not in chunk_map]
            structural_issues.append({"sample_id": sample_id, "type": "missing_chunk", "missing_chunk_ids": missing})
            continue

        chunk_doc_ids = {chunk_map[chunk_id][0] for chunk_id in expected_chunk_ids}
        if not chunk_doc_ids.issubset(expected_doc_ids):
            structural_issues.append(
                {
                    "sample_id": sample_id,
                    "type": "chunk_doc_mismatch",
                    "chunk_doc_ids": sorted(chunk_doc_ids),
                    "expected_doc_ids": sorted(expected_doc_ids),
                }
            )

        chunk_text = "\n".join(chunk_map[chunk_id][1].lower() for chunk_id in expected_chunk_ids)
        tokens = {
            token.lower()
            for token in QUERY_TOKEN_PATTERN.findall(query)
            if token.lower() not in KOREAN_STOPWORDS
        }
        if tokens:
            token_hits = sum(1 for token in tokens if token in chunk_text)
            overlap = token_hits / len(tokens)
            token_overlap_scores.append(overlap)
            if overlap < 0.15:
                low_overlap_warnings.append(
                    {
                        "sample_id": sample_id,
                        "type": "low_token_overlap",
                        "overlap": overlap,
                        "tokens": sorted(tokens),
                    }
                )
        else:
            token_overlap_scores.append(1.0)

    return {
        "sample_count": len(rows),
        "issue_count": len(structural_issues),
        "warning_count": len(low_overlap_warnings),
        "avg_token_overlap": round(sum(token_overlap_scores) / len(token_overlap_scores), 4)
        if token_overlap_scores
        else None,
        "issues": structural_issues,
        "warnings": low_overlap_warnings,
    }


def _fetch_synthetic_query_texts(connection: psycopg.Connection[Any]) -> set[str]:
    with connection.cursor() as cursor:
        cursor.execute("SELECT query_text FROM synthetic_queries_raw_all")
        rows = cursor.fetchall()
    return {
        _normalize_query_text(row[0])
        for row in rows
        if row and row[0]
    }


def _fetch_corpus_chunk_candidates(
    connection: psycopg.Connection[Any],
    min_token_len: int,
) -> list[ChunkCandidate]:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT c.chunk_id,
                   c.document_id,
                   COALESCE(c.section_path_text, ''),
                   COALESCE(c.chunk_text, ''),
                   COALESCE(c.token_len, 0),
                   c.previous_chunk_id,
                   c.next_chunk_id,
                   d.source_id,
                   d.version_label,
                   COALESCE(d.title, '')
            FROM corpus_chunks c
            JOIN corpus_documents d ON d.document_id = c.document_id
            WHERE d.is_active = TRUE
              AND c.chunk_text IS NOT NULL
              AND c.token_len >= %s
            ORDER BY d.source_id, d.version_label, c.document_id, c.chunk_index_in_document
            """,
            (min_token_len,),
        )
        rows = cursor.fetchall()

    return [
        ChunkCandidate(
            chunk_id=row[0],
            document_id=row[1],
            section_path_text=row[2],
            chunk_text=row[3],
            token_len=int(row[4] or 0),
            previous_chunk_id=row[5],
            next_chunk_id=row[6],
            source_id=row[7],
            version_label=row[8],
            title=row[9],
        )
        for row in rows
    ]


def _extract_terms(section_path_text: str, title: str, chunk_text: str, max_terms: int = 4) -> list[str]:
    raw_text = f"{section_path_text}\n{title}\n{chunk_text[:650]}"
    scored_terms: list[tuple[int, str]] = []
    seen: set[str] = set()
    for token in IDENTIFIER_PATTERN.findall(raw_text):
        term = _normalize_term(token)
        if not term:
            continue
        dedupe_key = term.lower()
        if dedupe_key in seen:
            continue
        seen.add(dedupe_key)

        score = 0
        if term.startswith("@"):
            score += 5
        if "." in term or "-" in term or "_" in term or "/" in term:
            score += 3
        if any(ch.isdigit() for ch in term):
            score += 2
        if any(ch.isupper() for ch in term[1:]):
            score += 3
        if term in TITLECASE_TECH_TERMS:
            score += 2
        if term.lower() in LOWERCASE_TECH_TERMS:
            score += 2
        for suffix in ("Manager", "Repository", "Filter", "Configurer", "Template", "Context", "Factory", "Client"):
            if term.endswith(suffix):
                score += 2
                break
        if score <= 0:
            continue
        scored_terms.append((score, term))

    scored_terms.sort(key=lambda item: (-item[0], item[1].lower()))
    terms = [term for _, term in scored_terms[:max_terms]]

    if terms:
        return terms

    section_hint = section_path_text.split(">")[-1].strip() if section_path_text else ""
    fallback = _normalize_term(section_hint)
    if fallback:
        return [fallback]
    return []


def _candidate_queries_single(terms: list[str]) -> list[str]:
    if not terms:
        return []
    t1 = terms[0]
    candidates: list[str] = []
    primary_templates = _rotate_templates(SINGLE_QUERY_TEMPLATES_PRIMARY, seed_text=t1)
    candidates.extend(template.format(t1=t1) for template in primary_templates)
    if len(terms) > 1:
        t2 = terms[1]
        if _is_pair_compatible(t1, t2):
            pair_templates = _rotate_templates(SINGLE_QUERY_TEMPLATES_PAIR, seed_text=f"{t1}:{t2}")
            candidates.extend(template.format(t1=t1, t2=t2) for template in pair_templates)
        else:
            candidates.append(f"{t1} 설정할 때 {t2}도 같이 봐야 해?")
    return candidates


def _candidate_queries_multi(terms_left: list[str], terms_right: list[str]) -> list[str]:
    left = terms_left[0] if terms_left else ""
    right = terms_right[0] if terms_right else ""
    if not left and right:
        left = right
    if left and left.lower() in GENERIC_TERMS and right:
        left = right
    if not left:
        return []
    templates = _rotate_templates(MULTI_QUERY_TEMPLATES, seed_text=left)
    return [template.format(t1=left) for template in templates]


def _pick_valid_query(
    candidates: list[str],
    banned_queries: set[str],
    used_queries: set[str],
) -> str:
    for query in candidates:
        normalized = _normalize_whitespace(query)
        normalized = normalized.rstrip(".!;:")
        if not normalized.endswith("?"):
            normalized = normalized + "?"
        normalized_query = _normalize_query_text(normalized)
        if normalized_query in banned_queries:
            continue
        if normalized_query in used_queries:
            continue
        if not _is_query_quality_acceptable(normalized):
            continue
        return normalized
    return ""


def _prepare_row(
    *,
    user_query: str,
    expected_doc_ids: list[str],
    expected_chunk_ids: list[str],
    expected_answer_key_points: list[str],
    single_or_multi_chunk: str,
    source_product: str | None,
    source_version: str | None,
) -> dict[str, Any]:
    difficulty = "hard" if single_or_multi_chunk == "multi" else "medium"
    return {
        "sample_id": "",
        "split": "test",
        "user_query_ko": user_query,
        "dialog_context": {},
        "expected_doc_ids": expected_doc_ids,
        "expected_chunk_ids": expected_chunk_ids,
        "expected_answer_key_points": expected_answer_key_points,
        "query_category": "short_user",
        "difficulty": difficulty,
        "single_or_multi_chunk": single_or_multi_chunk,
        "source_product": source_product,
        "source_version_if_available": source_version,
    }


def _build_regenerated_rows(
    connection: psycopg.Connection[Any],
    *,
    target_total: int,
    target_multi_count: int,
    min_chunk_tokens: int,
    seed: int,
    banned_queries: set[str],
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    if target_multi_count < 0 or target_multi_count > target_total:
        raise ValueError("target_multi_count must be within [0, target_total]")

    rng = random.Random(seed)
    chunk_candidates = _fetch_corpus_chunk_candidates(connection, min_token_len=min_chunk_tokens)
    chunk_by_id = {candidate.chunk_id: candidate for candidate in chunk_candidates}

    singles_by_source: dict[str, deque[ChunkCandidate]] = defaultdict(deque)
    multis_by_source: dict[str, deque[tuple[ChunkCandidate, ChunkCandidate]]] = defaultdict(deque)

    for candidate in chunk_candidates:
        source_key = candidate.source_id or "unknown"
        terms = _extract_terms(candidate.section_path_text, candidate.title or "", candidate.chunk_text)
        if terms:
            singles_by_source[source_key].append(candidate)

        if not candidate.next_chunk_id:
            continue
        next_candidate = chunk_by_id.get(candidate.next_chunk_id)
        if not next_candidate:
            continue
        if next_candidate.document_id != candidate.document_id:
            continue
        right_terms = _extract_terms(
            next_candidate.section_path_text,
            next_candidate.title or "",
            next_candidate.chunk_text,
        )
        if terms and right_terms:
            multis_by_source[source_key].append((candidate, next_candidate))

    for source, queue in singles_by_source.items():
        items = list(queue)
        rng.shuffle(items)
        singles_by_source[source] = deque(items)

    for source, queue in multis_by_source.items():
        items = list(queue)
        rng.shuffle(items)
        multis_by_source[source] = deque(items)

    used_queries: set[str] = set()
    used_chunk_ids: set[str] = set()
    selected_multi: list[dict[str, Any]] = []
    selected_single: list[dict[str, Any]] = []

    multi_sources = sorted(multis_by_source.keys())
    single_sources = sorted(singles_by_source.keys())

    while len(selected_multi) < target_multi_count:
        progressed = False
        for source in multi_sources:
            queue = multis_by_source[source]
            while queue:
                left, right = queue.popleft()
                if left.chunk_id in used_chunk_ids or right.chunk_id in used_chunk_ids:
                    continue
                left_terms = _extract_terms(left.section_path_text, left.title or "", left.chunk_text)
                right_terms = _extract_terms(right.section_path_text, right.title or "", right.chunk_text)
                query = _pick_valid_query(
                    _candidate_queries_multi(left_terms, right_terms),
                    banned_queries=banned_queries,
                    used_queries=used_queries,
                )
                if not query:
                    continue
                key_points = [_extract_keypoint(left.chunk_text), _extract_keypoint(right.chunk_text)]
                key_points = [point for point in key_points if point]
                if not key_points:
                    continue
                row = _prepare_row(
                    user_query=query,
                    expected_doc_ids=[left.document_id],
                    expected_chunk_ids=[left.chunk_id, right.chunk_id],
                    expected_answer_key_points=key_points,
                    single_or_multi_chunk="multi",
                    source_product=left.source_id,
                    source_version=left.version_label,
                )
                selected_multi.append(row)
                used_queries.add(_normalize_query_text(query))
                used_chunk_ids.add(left.chunk_id)
                used_chunk_ids.add(right.chunk_id)
                progressed = True
                break
            if len(selected_multi) >= target_multi_count:
                break
        if not progressed:
            break

    target_single_count = target_total - target_multi_count
    while len(selected_single) < target_single_count:
        progressed = False
        for source in single_sources:
            queue = singles_by_source[source]
            while queue:
                candidate = queue.popleft()
                if candidate.chunk_id in used_chunk_ids:
                    continue
                terms = _extract_terms(candidate.section_path_text, candidate.title or "", candidate.chunk_text)
                query = _pick_valid_query(
                    _candidate_queries_single(terms),
                    banned_queries=banned_queries,
                    used_queries=used_queries,
                )
                if not query:
                    continue
                key_point = _extract_keypoint(candidate.chunk_text)
                if not key_point:
                    continue
                row = _prepare_row(
                    user_query=query,
                    expected_doc_ids=[candidate.document_id],
                    expected_chunk_ids=[candidate.chunk_id],
                    expected_answer_key_points=[key_point],
                    single_or_multi_chunk="single",
                    source_product=candidate.source_id,
                    source_version=candidate.version_label,
                )
                selected_single.append(row)
                used_queries.add(_normalize_query_text(query))
                used_chunk_ids.add(candidate.chunk_id)
                progressed = True
                break
            if len(selected_single) >= target_single_count:
                break
        if not progressed:
            break

    if len(selected_multi) < target_multi_count:
        raise RuntimeError(
            f"failed to build enough multi-chunk rows: requested={target_multi_count}, built={len(selected_multi)}"
        )
    if len(selected_single) < target_single_count:
        raise RuntimeError(
            f"failed to build enough single-chunk rows: requested={target_single_count}, built={len(selected_single)}"
        )

    final_rows: list[dict[str, Any]] = []
    single_queue = deque(selected_single)
    multi_queue = deque(selected_multi)
    while single_queue or multi_queue:
        if single_queue:
            final_rows.append(single_queue.popleft())
        if single_queue:
            final_rows.append(single_queue.popleft())
        if single_queue:
            final_rows.append(single_queue.popleft())
        if multi_queue:
            final_rows.append(multi_queue.popleft())
    final_rows = final_rows[:target_total]

    if len(final_rows) != target_total:
        raise RuntimeError(f"final row size mismatch: expected={target_total}, actual={len(final_rows)}")

    for index, row in enumerate(final_rows, start=1):
        row["sample_id"] = f"test-short-user-{index:03d}"

    source_distribution: dict[str, int] = defaultdict(int)
    for row in final_rows:
        source_distribution[row.get("source_product") or "unknown"] += 1

    summary = {
        "selected_single_count": sum(1 for row in final_rows if row["single_or_multi_chunk"] == "single"),
        "selected_multi_count": sum(1 for row in final_rows if row["single_or_multi_chunk"] == "multi"),
        "source_distribution": dict(sorted(source_distribution.items())),
        "candidate_pool": {
            "chunks": len(chunk_candidates),
            "single_sources": {source: len(queue) for source, queue in singles_by_source.items()},
            "multi_sources": {source: len(queue) for source, queue in multis_by_source.items()},
        },
    }
    return final_rows, summary


def _write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as target:
        for row in rows:
            target.write(json.dumps(row, ensure_ascii=False) + "\n")


def _upsert_eval_samples(
    connection: psycopg.Connection[Any],
    rows: list[dict[str, Any]],
    *,
    dataset_key: str,
) -> None:
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
                    row.get("source_product"),
                    row.get("source_version_if_available"),
                    Jsonb(
                        {
                            "dataset_key": dataset_key,
                            "query_style": "short_user",
                            "generation_mode": "corpus_grounded_new_query",
                            "updated_at": datetime.now(timezone.utc).isoformat(),
                        }
                    ),
                ),
            )


def _refresh_dataset_items(
    connection: psycopg.Connection[Any],
    dataset_id: str,
    rows: list[dict[str, Any]],
) -> None:
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
    dataset_id: str,
    total_items: int,
    single_count: int,
    multi_count: int,
    source_file: str,
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
                "수집/정제/청킹된 코퍼스 기준으로 신규 생성한 short-user retrieval-aware 평가셋 (80문항)",
                "v3-2026-04-19",
                total_items,
                Jsonb({"short_user": total_items}),
                Jsonb({"single": single_count, "multi": multi_count}),
                Jsonb(
                    {
                        "regenerated_at": datetime.now(timezone.utc).isoformat(),
                        "generation_mode": "corpus_grounded_new_query",
                        "source_file": source_file,
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
    target_multi_count: int,
    min_chunk_tokens: int,
    seed: int,
    db_host: str,
    db_port: int,
    db_name: str,
    db_user: str,
    db_password: str,
) -> dict[str, Any]:
    if target_total <= 0:
        raise ValueError("target_total must be positive")

    connection = _connect(db_host, db_port, db_name, db_user, db_password)
    try:
        dataset_key, dataset_name = _fetch_dataset_meta(connection, dataset_id)
        before_rows = _fetch_dataset_rows(connection, dataset_id)
        before_audit = _audit_mapping(connection, before_rows)
        banned_queries = _fetch_synthetic_query_texts(connection)

        regenerated_rows, generation_summary = _build_regenerated_rows(
            connection,
            target_total=target_total,
            target_multi_count=target_multi_count,
            min_chunk_tokens=min_chunk_tokens,
            seed=seed,
            banned_queries=banned_queries,
        )

        _write_jsonl(output_file, regenerated_rows)
        _upsert_eval_samples(connection, regenerated_rows, dataset_key=dataset_key)
        _refresh_dataset_items(connection, dataset_id=dataset_id, rows=regenerated_rows)

        single_count = sum(1 for row in regenerated_rows if row["single_or_multi_chunk"] == "single")
        multi_count = sum(1 for row in regenerated_rows if row["single_or_multi_chunk"] == "multi")
        _update_dataset_meta(
            connection,
            dataset_id=dataset_id,
            total_items=len(regenerated_rows),
            single_count=single_count,
            multi_count=multi_count,
            source_file=str(output_file).replace("/", "\\"),
        )
        connection.commit()

        after_rows = _fetch_dataset_rows(connection, dataset_id)
        after_audit = _audit_mapping(connection, after_rows)
        if after_audit["issue_count"] > 0:
            raise RuntimeError(
                f"post-update dataset mapping audit failed with {after_audit['issue_count']} issues"
            )

        synthetic_overlap_count = sum(
            1 for row in regenerated_rows if _normalize_query_text(row["user_query_ko"]) in banned_queries
        )

        summary = {
            "dataset_id": dataset_id,
            "dataset_name_before": dataset_name,
            "target_total": target_total,
            "generation_mode": "corpus_grounded_new_query",
            "single_multi_distribution": {
                "single": single_count,
                "multi": multi_count,
            },
            "synthetic_query_exact_overlap": synthetic_overlap_count,
            "output_file": str(output_file),
            "report_file": str(report_file),
            "audit_before": {
                "sample_count": before_audit["sample_count"],
                "issue_count": before_audit["issue_count"],
                "warning_count": before_audit["warning_count"],
                "avg_token_overlap": before_audit["avg_token_overlap"],
            },
            "audit_after": {
                "sample_count": after_audit["sample_count"],
                "issue_count": after_audit["issue_count"],
                "warning_count": after_audit["warning_count"],
                "avg_token_overlap": after_audit["avg_token_overlap"],
            },
            "generation_summary": generation_summary,
            "preview": [
                {
                    "sample_id": row["sample_id"],
                    "user_query_ko": row["user_query_ko"],
                    "expected_doc_ids": row["expected_doc_ids"],
                    "expected_chunk_ids": row["expected_chunk_ids"],
                    "single_or_multi_chunk": row["single_or_multi_chunk"],
                }
                for row in regenerated_rows[:12]
            ],
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
        description="Regenerate short-user eval dataset from corpus chunks and run mapping audit."
    )
    parser.add_argument("--dataset-id", default="b2d47254-8655-4c9c-81ac-7615677ec5bd")
    parser.add_argument("--output-file", default="data/eval/human_eval_short_user_test_80.jsonl")
    parser.add_argument(
        "--report-file",
        default="data/reports/short_user_dataset_80_regenerated_audit_2026-04-19.json",
    )
    parser.add_argument("--target-total", type=int, default=80)
    parser.add_argument("--target-multi-count", type=int, default=20)
    parser.add_argument("--min-chunk-tokens", type=int, default=140)
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
        target_multi_count=args.target_multi_count,
        min_chunk_tokens=args.min_chunk_tokens,
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
