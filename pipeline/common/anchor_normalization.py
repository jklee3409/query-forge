from __future__ import annotations

import re
import unicodedata
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Any

DEFAULT_MAPPING_VERSION = "anchor-map-v1"
DEFAULT_NORMALIZATION_VERSION = "anchor-normalize-v1"
CANONICAL_ANCHOR_RUNTIME_SCHEMA_VERSION = "canonical-anchor-runtime-v1"

SUPPORTED_ALIAS_LANGUAGES = {"en", "ko", "und"}
SUPPORTED_TERM_TYPES = {
    "product",
    "annotation",
    "class",
    "interface",
    "config_key",
    "cli",
    "artifact",
    "api",
    "property",
    "concept",
}

_PHRASE_FOLDING_TERM_TYPES = {"concept"}
_JAVA_IDENTIFIER_RE = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*\Z")
_WHITESPACE_RE = re.compile(r"\s+", re.UNICODE)
_HANGUL_BETWEEN_SPACE_RE = re.compile(r"(?<=[\uac00-\ud7a3])\s+(?=[\uac00-\ud7a3])")
_WRAPPER_PUNCTUATION = " \t\r\n.,;:!?()[]{}<>\"'`"

APPROVED_ACTIVE_MAPPING_SQL = """
SELECT m.mapping_id,
       m.alias_text,
       m.normalized_alias,
       m.display_alias,
       m.alias_language,
       m.canonical_term_id,
       m.alias_term_id,
       m.confidence,
       m.review_status,
       m.mapping_status,
       m.source,
       m.provenance,
       m.metadata,
       t.canonical_form,
       t.normalized_form AS canonical_normalized_form,
       t.term_type
FROM canonical_anchor_mapping m
JOIN corpus_glossary_terms t
  ON t.term_id = m.canonical_term_id
WHERE m.mapping_version = %s
  AND m.normalization_version = %s
  AND m.alias_language = %s
  AND m.normalized_alias = %s
  AND m.review_status = 'approved'
  AND m.mapping_status = 'active'
  AND t.is_active = TRUE
ORDER BY m.confidence DESC NULLS LAST, m.mapping_id
"""

PENDING_MAPPING_SQL = """
SELECT m.mapping_id,
       m.alias_text,
       m.normalized_alias,
       m.display_alias,
       m.alias_language,
       m.canonical_term_id,
       m.alias_term_id,
       m.confidence,
       m.review_status,
       m.mapping_status,
       m.source,
       m.provenance,
       m.metadata,
       t.canonical_form,
       t.normalized_form AS canonical_normalized_form,
       t.term_type
FROM canonical_anchor_mapping m
JOIN corpus_glossary_terms t
  ON t.term_id = m.canonical_term_id
WHERE m.mapping_version = %s
  AND m.normalization_version = %s
  AND m.alias_language = %s
  AND m.normalized_alias = %s
  AND m.review_status = 'pending'
  AND m.mapping_status = 'active'
  AND t.is_active = TRUE
ORDER BY m.confidence DESC NULLS LAST, m.mapping_id
"""

SELF_FALLBACK_SQL = """
SELECT term_id,
       canonical_form,
       normalized_form AS canonical_normalized_form,
       term_type
FROM corpus_glossary_terms
WHERE is_active = TRUE
  AND term_type = %s
  AND normalized_form = %s
ORDER BY evidence_count DESC, canonical_form, term_id
"""

_MAPPING_COLUMNS = (
    "mapping_id",
    "alias_text",
    "normalized_alias",
    "display_alias",
    "alias_language",
    "canonical_term_id",
    "alias_term_id",
    "confidence",
    "review_status",
    "mapping_status",
    "source",
    "provenance",
    "metadata",
    "canonical_form",
    "canonical_normalized_form",
    "term_type",
)

_SELF_FALLBACK_COLUMNS = (
    "term_id",
    "canonical_form",
    "canonical_normalized_form",
    "term_type",
)


@dataclass(frozen=True, slots=True)
class AnchorAliasNormalization:
    input_alias: str
    display_alias: str
    normalized_alias: str
    alias_language: str
    term_type: str
    normalization_version: str

    def as_payload(self) -> dict[str, str]:
        return {
            "input_alias": self.input_alias,
            "display_alias": self.display_alias,
            "normalized_alias": self.normalized_alias,
            "alias_language": self.alias_language,
            "term_type": self.term_type,
            "normalization_version": self.normalization_version,
        }


@dataclass(frozen=True, slots=True)
class AnchorResolutionInput:
    alias_text: str
    alias_language: str
    term_type: str
    source_field: str | None = None


def normalize_anchor_alias(
    alias_text: Any,
    alias_language: str,
    term_type: str,
    normalization_version: str = DEFAULT_NORMALIZATION_VERSION,
) -> AnchorAliasNormalization:
    """Normalize an alias for canonical-anchor lookup without changing source text."""
    _validate_normalization_version(normalization_version)
    language = _normalize_alias_language(alias_language)
    normalized_term_type = _normalize_term_type(term_type)
    input_alias = "" if alias_text is None else str(alias_text)
    display_alias = _display_alias(input_alias)
    normalized_alias = _normalized_alias(display_alias, language, normalized_term_type)
    return AnchorAliasNormalization(
        input_alias=input_alias,
        display_alias=display_alias,
        normalized_alias=normalized_alias,
        alias_language=language,
        term_type=normalized_term_type,
        normalization_version=normalization_version,
    )


def resolve_canonical_anchors(
    items: Sequence[AnchorResolutionInput | Mapping[str, Any]],
    *,
    connection: Any | None = None,
    mapping_version: str = DEFAULT_MAPPING_VERSION,
    normalization_version: str = DEFAULT_NORMALIZATION_VERSION,
    source_context: Mapping[str, Any] | None = None,
    fallback_term_candidates: Sequence[Mapping[str, Any]] | None = None,
    include_pending: bool = False,
) -> dict[str, Any]:
    """Resolve aliases to canonical anchor metadata without mutating source fields."""
    _validate_mapping_version(mapping_version)
    _validate_normalization_version(normalization_version)
    source_context_payload = dict(source_context or {})
    anchors: list[dict[str, Any]] = []
    canonical_terms: list[str] = []
    canonical_term_ids: list[str] = []
    unresolved_aliases: list[str] = []

    for raw_item in items:
        item = _coerce_resolution_input(raw_item)
        normalized = normalize_anchor_alias(
            item.alias_text,
            item.alias_language,
            item.term_type,
            normalization_version=normalization_version,
        )
        approved_rows = _fetch_mapping_rows(
            connection,
            APPROVED_ACTIVE_MAPPING_SQL,
            (
                mapping_version,
                normalization_version,
                normalized.alias_language,
                normalized.normalized_alias,
            ),
        )
        pending_candidates: list[dict[str, Any]] = []
        if len(approved_rows) == 1:
            anchor_payload = _mapped_anchor_payload(
                normalized,
                item.source_field,
                approved_rows[0],
                resolution_status="mapped",
                used_for_scoring=True,
            )
        elif len(approved_rows) > 1:
            if include_pending:
                pending_candidates = _load_pending_candidates(
                    connection,
                    mapping_version,
                    normalization_version,
                    normalized,
                )
            anchor_payload = _unresolved_anchor_payload(
                normalized,
                item.source_field,
                resolution_status="ambiguous",
                candidate_count=len(approved_rows),
                pending_candidates=pending_candidates,
            )
        else:
            self_rows = _resolve_self_fallback_rows(
                connection,
                normalized,
                fallback_term_candidates or (),
            )
            if len(self_rows) == 1:
                anchor_payload = _self_fallback_anchor_payload(
                    normalized,
                    item.source_field,
                    self_rows[0],
                )
            elif len(self_rows) > 1:
                anchor_payload = _unresolved_anchor_payload(
                    normalized,
                    item.source_field,
                    resolution_status="ambiguous_self_fallback",
                    candidate_count=len(self_rows),
                    pending_candidates=[],
                )
            else:
                if include_pending:
                    pending_candidates = _load_pending_candidates(
                        connection,
                        mapping_version,
                        normalization_version,
                        normalized,
                    )
                anchor_payload = _unresolved_anchor_payload(
                    normalized,
                    item.source_field,
                    resolution_status="miss",
                    candidate_count=0,
                    pending_candidates=pending_candidates,
                )

        anchors.append(anchor_payload)
        if anchor_payload.get("used_for_scoring"):
            _append_unique(canonical_terms, anchor_payload.get("canonical_form"))
            _append_unique(canonical_term_ids, anchor_payload.get("canonical_term_id"))
        else:
            unresolved_aliases.append(normalized.display_alias)

    return {
        "schema_version": CANONICAL_ANCHOR_RUNTIME_SCHEMA_VERSION,
        "mapping_version": mapping_version,
        "normalization_version": normalization_version,
        "source_context": source_context_payload,
        "anchors": anchors,
        "canonical_terms": canonical_terms,
        "canonical_term_ids": canonical_term_ids,
        "unresolved_aliases": unresolved_aliases,
    }


def _display_alias(alias_text: str) -> str:
    value = unicodedata.normalize("NFKC", alias_text)
    value = _WHITESPACE_RE.sub(" ", value).strip()
    value = value.strip(_WRAPPER_PUNCTUATION)
    return _WHITESPACE_RE.sub(" ", value).strip()


def _normalized_alias(display_alias: str, alias_language: str, term_type: str) -> str:
    value = display_alias
    if term_type == "annotation" and _JAVA_IDENTIFIER_RE.fullmatch(value):
        value = f"@{value}"
    if alias_language == "en" and term_type in _PHRASE_FOLDING_TERM_TYPES:
        value = re.sub(r"[-_]+", " ", value)
        value = _WHITESPACE_RE.sub(" ", value).strip()
    if alias_language == "ko":
        value = _HANGUL_BETWEEN_SPACE_RE.sub("", value)
    return _WHITESPACE_RE.sub(" ", value).strip().casefold()


def _validate_normalization_version(normalization_version: str) -> None:
    if normalization_version != DEFAULT_NORMALIZATION_VERSION:
        raise ValueError(f"Unsupported anchor normalization version: {normalization_version!r}")


def _validate_mapping_version(mapping_version: str) -> None:
    if not str(mapping_version or "").strip():
        raise ValueError("mapping_version must be provided.")


def _normalize_alias_language(alias_language: str) -> str:
    language = str(alias_language or "").strip().lower()
    if language not in SUPPORTED_ALIAS_LANGUAGES:
        raise ValueError(f"Unsupported or missing alias_language: {alias_language!r}")
    return language


def _normalize_term_type(term_type: str) -> str:
    normalized = str(term_type or "").strip().lower()
    if normalized not in SUPPORTED_TERM_TYPES:
        raise ValueError(f"Unsupported or missing term_type: {term_type!r}")
    return normalized


def _coerce_resolution_input(raw_item: AnchorResolutionInput | Mapping[str, Any]) -> AnchorResolutionInput:
    if isinstance(raw_item, AnchorResolutionInput):
        return raw_item
    if not isinstance(raw_item, Mapping):
        raise TypeError("Canonical anchor resolver items must include explicit alias_language and term_type.")
    alias_text = raw_item.get("alias_text", raw_item.get("input_alias", raw_item.get("text", "")))
    return AnchorResolutionInput(
        alias_text="" if alias_text is None else str(alias_text),
        alias_language=str(raw_item.get("alias_language", "")),
        term_type=str(raw_item.get("term_type", "")),
        source_field=(
            None
            if raw_item.get("source_field") is None
            else str(raw_item.get("source_field"))
        ),
    )


def _fetch_mapping_rows(
    connection: Any | None,
    query: str,
    parameters: tuple[Any, ...],
) -> list[dict[str, Any]]:
    if connection is None:
        return []
    with connection.cursor() as cursor:
        cursor.execute(query, parameters)
        return [_row_to_dict(row, _MAPPING_COLUMNS) for row in cursor.fetchall()]


def _load_pending_candidates(
    connection: Any | None,
    mapping_version: str,
    normalization_version: str,
    normalized: AnchorAliasNormalization,
) -> list[dict[str, Any]]:
    rows = _fetch_mapping_rows(
        connection,
        PENDING_MAPPING_SQL,
        (
            mapping_version,
            normalization_version,
            normalized.alias_language,
            normalized.normalized_alias,
        ),
    )
    return [_candidate_payload(row) for row in rows]


def _resolve_self_fallback_rows(
    connection: Any | None,
    normalized: AnchorAliasNormalization,
    fallback_term_candidates: Sequence[Mapping[str, Any]],
) -> list[dict[str, Any]]:
    if fallback_term_candidates:
        return _match_fallback_candidates(normalized, fallback_term_candidates)
    if connection is None:
        return []
    with connection.cursor() as cursor:
        cursor.execute(
            SELF_FALLBACK_SQL,
            (normalized.term_type, normalized.normalized_alias),
        )
        return [_row_to_dict(row, _SELF_FALLBACK_COLUMNS) for row in cursor.fetchall()]


def _match_fallback_candidates(
    normalized: AnchorAliasNormalization,
    fallback_term_candidates: Sequence[Mapping[str, Any]],
) -> list[dict[str, Any]]:
    matches: list[dict[str, Any]] = []
    for candidate in fallback_term_candidates:
        if candidate.get("is_active", True) is False:
            continue
        term_type = _normalize_term_type(str(candidate.get("term_type", "")))
        if term_type != normalized.term_type:
            continue
        canonical_form = str(candidate.get("canonical_form", ""))
        candidate_normalized = str(candidate.get("normalized_form") or "").strip()
        if not candidate_normalized:
            candidate_normalized = normalize_anchor_alias(
                canonical_form,
                normalized.alias_language,
                term_type,
                normalization_version=normalized.normalization_version,
            ).normalized_alias
        if candidate_normalized != normalized.normalized_alias:
            continue
        matches.append(
            {
                "term_id": candidate.get("term_id"),
                "canonical_form": canonical_form,
                "canonical_normalized_form": candidate_normalized,
                "term_type": term_type,
            }
        )
    return matches


def _mapped_anchor_payload(
    normalized: AnchorAliasNormalization,
    source_field: str | None,
    row: Mapping[str, Any],
    *,
    resolution_status: str,
    used_for_scoring: bool,
) -> dict[str, Any]:
    return {
        "input_alias": normalized.input_alias,
        "display_alias": normalized.display_alias,
        "normalized_alias": normalized.normalized_alias,
        "alias_language": normalized.alias_language,
        "resolution_status": resolution_status,
        "mapping_id": _string_or_none(row.get("mapping_id")),
        "canonical_term_id": _string_or_none(row.get("canonical_term_id")),
        "canonical_form": row.get("canonical_form"),
        "canonical_normalized_form": row.get("canonical_normalized_form"),
        "term_type": row.get("term_type"),
        "confidence": row.get("confidence"),
        "review_status": row.get("review_status"),
        "used_for_scoring": used_for_scoring,
        "source_field": source_field,
    }


def _self_fallback_anchor_payload(
    normalized: AnchorAliasNormalization,
    source_field: str | None,
    row: Mapping[str, Any],
) -> dict[str, Any]:
    return {
        "input_alias": normalized.input_alias,
        "display_alias": normalized.display_alias,
        "normalized_alias": normalized.normalized_alias,
        "alias_language": normalized.alias_language,
        "resolution_status": "self_fallback",
        "mapping_id": None,
        "canonical_term_id": _string_or_none(row.get("term_id")),
        "canonical_form": row.get("canonical_form"),
        "canonical_normalized_form": row.get("canonical_normalized_form"),
        "term_type": row.get("term_type"),
        "confidence": 1.0,
        "review_status": None,
        "used_for_scoring": True,
        "source_field": source_field,
    }


def _unresolved_anchor_payload(
    normalized: AnchorAliasNormalization,
    source_field: str | None,
    *,
    resolution_status: str,
    candidate_count: int,
    pending_candidates: Sequence[Mapping[str, Any]],
) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "input_alias": normalized.input_alias,
        "display_alias": normalized.display_alias,
        "normalized_alias": normalized.normalized_alias,
        "alias_language": normalized.alias_language,
        "resolution_status": resolution_status,
        "mapping_id": None,
        "canonical_term_id": None,
        "canonical_form": None,
        "canonical_normalized_form": None,
        "term_type": normalized.term_type,
        "confidence": None,
        "review_status": None,
        "used_for_scoring": False,
        "source_field": source_field,
        "candidate_count": candidate_count,
    }
    if pending_candidates:
        payload["pending_candidates"] = list(pending_candidates)
    return payload


def _candidate_payload(row: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "mapping_id": _string_or_none(row.get("mapping_id")),
        "canonical_term_id": _string_or_none(row.get("canonical_term_id")),
        "canonical_form": row.get("canonical_form"),
        "canonical_normalized_form": row.get("canonical_normalized_form"),
        "term_type": row.get("term_type"),
        "confidence": row.get("confidence"),
        "review_status": row.get("review_status"),
        "mapping_status": row.get("mapping_status"),
        "source": row.get("source"),
    }


def _row_to_dict(row: Any, columns: Sequence[str]) -> dict[str, Any]:
    if isinstance(row, Mapping):
        return dict(row)
    return {column: row[index] for index, column in enumerate(columns)}


def _append_unique(target: list[str], value: Any) -> None:
    if value is None:
        return
    normalized = str(value)
    if normalized not in target:
        target.append(normalized)


def _string_or_none(value: Any) -> str | None:
    if value is None:
        return None
    return str(value)


__all__ = [
    "APPROVED_ACTIVE_MAPPING_SQL",
    "CANONICAL_ANCHOR_RUNTIME_SCHEMA_VERSION",
    "DEFAULT_MAPPING_VERSION",
    "DEFAULT_NORMALIZATION_VERSION",
    "PENDING_MAPPING_SQL",
    "SELF_FALLBACK_SQL",
    "AnchorAliasNormalization",
    "AnchorResolutionInput",
    "normalize_anchor_alias",
    "resolve_canonical_anchors",
]
