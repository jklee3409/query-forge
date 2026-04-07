from __future__ import annotations

import argparse
import hashlib
import json
import logging
import re
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml


LOGGER = logging.getLogger(__name__)
TOKEN_RE = re.compile(r"[A-Za-z0-9_./:@$-]+|[^\w\s]", re.UNICODE)
SENTENCE_SPLIT_RE = re.compile(r"(?<=[.!?])\s+")
WHITESPACE_RE = re.compile(r"\s+")
ANNOTATION_RE = re.compile(r"@\w+(?:\.\w+)*")
CONFIG_KEY_RE = re.compile(
    r"\b(?:spring|management|server|logging|security|data|jpa|hibernate|jdbc|r2dbc|flyway|liquibase|web|webflux|mvc|main|test|actuator|application)"
    r"\.[a-z0-9][a-z0-9-]*(?:\.[a-z0-9][a-z0-9-]*)+\b"
)
MAVEN_COORD_RE = re.compile(
    r"\b(?:[a-z][a-z0-9_-]*\.)+[a-z][a-z0-9_-]*:[A-Za-z0-9_.-]+(?::[A-Za-z0-9_.-]+)?\b"
)
STARTER_RE = re.compile(
    r"\bspring-(?:boot|cloud|data|security|session|ai|batch|integration)-[a-z0-9-]+\b"
)
FULLY_QUALIFIED_TYPE_RE = re.compile(r"\b(?:[a-z_][\w$]*\.)+[A-Z][A-Za-z0-9_$]*\b")
TYPE_DECLARATION_RE = re.compile(
    r"\b(?:class|interface|enum|record|extends|implements|new)\s+([A-Z][A-Za-z0-9_$]*)\b"
)
INLINE_TYPE_RE = re.compile(
    r"\b[A-Z][A-Za-z0-9_$]*(?:Builder|Factory|Configurer|Template|Repository|Controller|Service|Configuration|Properties|Client|Manager|Resolver|Filter|Context|Bean|Application|Exception|Handler|Provider|Converter|Strategy|Endpoint|Details|Authentication|DataSource)\b"
)


@dataclass(slots=True)
class VisualizationSettings:
    max_documents: int
    max_chunks_per_document: int
    max_glossary_terms: int


@dataclass(slots=True)
class ChunkingSettings:
    target_min_tokens: int
    target_max_tokens: int
    overlap_min_tokens: int
    overlap_target_tokens: int
    overlap_max_tokens: int
    base_min_tokens_after_first: int
    base_max_tokens: int
    preferred_chunk_tokens: int
    max_segment_tokens: int
    short_chunk_merge_threshold: int
    overlap_label: str
    visualization: VisualizationSettings


@dataclass(slots=True)
class ProductTerm:
    canonical_form: str
    aliases: list[str]


@dataclass(slots=True)
class GlossarySettings:
    keep_in_english_default: bool
    cli_command_prefixes: list[str]
    spring_products: list[ProductTerm]


@dataclass(slots=True)
class Settings:
    chunking: ChunkingSettings
    glossary: GlossarySettings


@dataclass(slots=True)
class Segment:
    document_id: str
    section_id: str
    section_path: str
    heading_hierarchy: list[str]
    heading_level: int | None
    source_url: str | None
    product: str
    version_if_available: str | None
    block_type: str
    text: str
    token_len: int
    code_presence: bool


@dataclass(slots=True)
class ChunkDraft:
    document_id: str
    product: str
    version_if_available: str | None
    segments: list[Segment]

    @property
    def base_content(self) -> str:
        return "\n\n".join(segment.text for segment in self.segments).strip()

    @property
    def base_token_len(self) -> int:
        return estimate_token_length(self.base_content)

    @property
    def code_presence(self) -> bool:
        return any(segment.code_presence for segment in self.segments)


def configure_logging(level: str) -> None:
    logging.basicConfig(
        level=getattr(logging, level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s - %(message)s",
    )


def stable_hash(value: str, prefix: str) -> str:
    digest = hashlib.sha1(value.encode("utf-8")).hexdigest()
    return f"{prefix}_{digest[:16]}"


def normalize_whitespace(text: str) -> str:
    return WHITESPACE_RE.sub(" ", text).strip()


def estimate_token_length(text: str) -> int:
    if not text:
        return 0
    return len(TOKEN_RE.findall(text))


def trim_tail_tokens(text: str, target_tokens: int) -> str:
    matches = list(TOKEN_RE.finditer(text))
    if not matches or target_tokens <= 0 or len(matches) <= target_tokens:
        return text.strip()

    start_index = matches[-target_tokens].start()
    return text[start_index:].strip()


def ordered_unique(values: Any) -> list[Any]:
    seen: dict[Any, None] = {}
    for value in values:
        if value is None:
            continue
        seen.setdefault(value, None)
    return list(seen.keys())


def load_settings(config_path: Path) -> Settings:
    payload = yaml.safe_load(config_path.read_text(encoding="utf-8"))
    chunking_payload = payload["chunking"]
    glossary_payload = payload["glossary"]

    return Settings(
        chunking=ChunkingSettings(
            target_min_tokens=int(chunking_payload["target_min_tokens"]),
            target_max_tokens=int(chunking_payload["target_max_tokens"]),
            overlap_min_tokens=int(chunking_payload["overlap_min_tokens"]),
            overlap_target_tokens=int(chunking_payload["overlap_target_tokens"]),
            overlap_max_tokens=int(chunking_payload["overlap_max_tokens"]),
            base_min_tokens_after_first=int(chunking_payload["base_min_tokens_after_first"]),
            base_max_tokens=int(chunking_payload["base_max_tokens"]),
            preferred_chunk_tokens=int(chunking_payload["preferred_chunk_tokens"]),
            max_segment_tokens=int(chunking_payload["max_segment_tokens"]),
            short_chunk_merge_threshold=int(chunking_payload["short_chunk_merge_threshold"]),
            overlap_label=str(chunking_payload["overlap_label"]),
            visualization=VisualizationSettings(
                max_documents=int(chunking_payload["visualization"]["max_documents"]),
                max_chunks_per_document=int(chunking_payload["visualization"]["max_chunks_per_document"]),
                max_glossary_terms=int(chunking_payload["visualization"]["max_glossary_terms"]),
            ),
        ),
        glossary=GlossarySettings(
            keep_in_english_default=bool(glossary_payload["keep_in_english_default"]),
            cli_command_prefixes=[str(value) for value in glossary_payload["cli_command_prefixes"]],
            spring_products=[
                ProductTerm(
                    canonical_form=str(item["canonical_form"]),
                    aliases=[str(alias) for alias in item.get("aliases", [])],
                )
                for item in glossary_payload["spring_products"]
            ],
        ),
    )


def render_heading(section: dict[str, Any]) -> str:
    return f"Section Path: {section['section_path']}"


def render_block(block: dict[str, Any]) -> str:
    block_type = block.get("type", "paragraph")
    text = str(block.get("text", "")).strip()

    if block_type == "list":
        items = [normalize_whitespace(str(item)) for item in block.get("items", [])]
        return "\n".join(f"- {item}" for item in items if item).strip()

    if block_type == "table":
        headers = [normalize_whitespace(str(value)) for value in block.get("headers", [])]
        rows = [
            [normalize_whitespace(str(value)) for value in row]
            for row in block.get("rows", [])
        ]
        lines: list[str] = []
        if headers:
            lines.append(" | ".join(headers))
        lines.extend(" | ".join(row) for row in rows if any(row))
        return "\n".join(lines).strip()

    if block_type == "admonition":
        admonition_type = str(block.get("admonition_type", "note")).upper()
        return f"{admonition_type}: {text}".strip()

    if block_type == "code":
        language = str(block.get("language") or "").strip()
        fence = f"```{language}" if language else "```"
        return f"{fence}\n{text}\n```".strip()

    return text


def sentence_chunks(text: str, max_segment_tokens: int) -> list[str]:
    if estimate_token_length(text) <= max_segment_tokens:
        return [text]

    sentences = [part.strip() for part in SENTENCE_SPLIT_RE.split(text) if part.strip()]
    if len(sentences) <= 1:
        return word_chunks(text, max_segment_tokens)

    return pack_fragments(sentences, max_segment_tokens)


def word_chunks(text: str, max_segment_tokens: int) -> list[str]:
    words = text.split()
    if not words:
        return []

    chunks: list[str] = []
    current: list[str] = []
    current_tokens = 0

    for word in words:
        word_token_len = estimate_token_length(word)
        projected = current_tokens + word_token_len
        if current and projected > max_segment_tokens:
            chunks.append(" ".join(current).strip())
            current = [word]
            current_tokens = word_token_len
            continue

        current.append(word)
        current_tokens = projected

    if current:
        chunks.append(" ".join(current).strip())
    return chunks


def pack_fragments(fragments: list[str], max_segment_tokens: int) -> list[str]:
    chunks: list[str] = []
    current: list[str] = []
    current_tokens = 0

    for fragment in fragments:
        fragment_text = fragment.strip()
        if not fragment_text:
            continue

        fragment_token_len = estimate_token_length(fragment_text)
        if fragment_token_len > max_segment_tokens:
            oversized = word_chunks(fragment_text, max_segment_tokens)
            if current:
                chunks.append("\n".join(current).strip())
                current = []
                current_tokens = 0
            chunks.extend(chunk for chunk in oversized if chunk)
            continue

        if current and current_tokens + fragment_token_len > max_segment_tokens:
            chunks.append("\n".join(current).strip())
            current = [fragment_text]
            current_tokens = fragment_token_len
            continue

        current.append(fragment_text)
        current_tokens += fragment_token_len

    if current:
        chunks.append("\n".join(current).strip())

    return chunks


def split_list_block(block: dict[str, Any], max_segment_tokens: int) -> list[str]:
    items = [normalize_whitespace(str(item)) for item in block.get("items", []) if str(item).strip()]
    return pack_fragments([f"- {item}" for item in items], max_segment_tokens)


def split_table_block(block: dict[str, Any], max_segment_tokens: int) -> list[str]:
    headers = [normalize_whitespace(str(value)) for value in block.get("headers", [])]
    rows = [
        " | ".join(normalize_whitespace(str(value)) for value in row)
        for row in block.get("rows", [])
        if any(str(value).strip() for value in row)
    ]
    fragments = []
    if headers:
        fragments.append(" | ".join(headers))
    fragments.extend(rows)
    return pack_fragments(fragments, max_segment_tokens)


def render_code_segment(lines: list[str], language: str) -> str:
    code_body = "\n".join(lines).rstrip()
    fence = f"```{language}" if language else "```"
    return f"{fence}\n{code_body}\n```".strip()


def split_code_block(block: dict[str, Any], max_segment_tokens: int) -> list[str]:
    lines = [line.rstrip() for line in str(block.get("text", "")).splitlines()]
    if not lines:
        return []

    language = str(block.get("language") or "").strip()
    chunks: list[str] = []
    current_lines: list[str] = []
    current_tokens = 0

    for line in lines:
        line_text = line or ""
        line_tokens = estimate_token_length(line_text)
        if current_lines and current_tokens + line_tokens > max_segment_tokens:
            chunks.append(render_code_segment(current_lines, language))
            current_lines = [line_text]
            current_tokens = line_tokens
            continue

        current_lines.append(line_text)
        current_tokens += line_tokens

    if current_lines:
        chunks.append(render_code_segment(current_lines, language))

    return chunks


def block_to_segment_texts(block: dict[str, Any], max_segment_tokens: int) -> list[str]:
    block_type = block.get("type", "paragraph")

    if block_type == "code":
        return split_code_block(block, max_segment_tokens)
    if block_type == "list":
        return split_list_block(block, max_segment_tokens)
    if block_type == "table":
        return split_table_block(block, max_segment_tokens)

    text = render_block(block)
    if not text:
        return []
    return sentence_chunks(text, max_segment_tokens)


def build_segments_for_section(
    section: dict[str, Any],
    settings: ChunkingSettings,
) -> list[Segment]:
    heading_level = int(section["heading_level"]) if section.get("heading_level") is not None else None
    base_kwargs = {
        "document_id": str(section["document_id"]),
        "section_id": str(section["section_id"]),
        "section_path": str(section["section_path"]),
        "heading_hierarchy": [str(value) for value in section.get("heading_hierarchy", [])],
        "heading_level": heading_level,
        "source_url": section.get("source_url"),
        "product": str(section["product"]),
        "version_if_available": section.get("version_if_available"),
    }

    segments = [
        Segment(
            **base_kwargs,
            block_type="heading",
            text=render_heading(section),
            token_len=estimate_token_length(render_heading(section)),
            code_presence=False,
        )
    ]

    for block in section.get("structural_blocks", []):
        block_type = str(block.get("type", "paragraph"))
        for rendered in block_to_segment_texts(block, settings.max_segment_tokens):
            if not rendered:
                continue
            segments.append(
                Segment(
                    **base_kwargs,
                    block_type=block_type,
                    text=rendered,
                    token_len=estimate_token_length(rendered),
                    code_presence=block_type == "code",
                )
            )

    return [segment for segment in segments if segment.text]


def read_sections_by_document(
    input_path: Path,
    limit_documents: int | None = None,
) -> tuple[dict[str, list[dict[str, Any]]], int]:
    documents: dict[str, list[dict[str, Any]]] = {}
    sections_read = 0

    with input_path.open("r", encoding="utf-8") as source:
        for line in source:
            if not line.strip():
                continue

            record = json.loads(line)
            document_id = str(record["document_id"])
            if document_id not in documents:
                if limit_documents is not None and len(documents) >= limit_documents:
                    break
                documents[document_id] = []
            documents[document_id].append(record)
            sections_read += 1

    return documents, sections_read


def minimum_base_tokens(chunk_index: int, settings: ChunkingSettings) -> int:
    if chunk_index == 0:
        return settings.target_min_tokens
    return settings.base_min_tokens_after_first


def initial_chunk_boundaries(
    segments: list[Segment],
    settings: ChunkingSettings,
) -> list[ChunkDraft]:
    chunks: list[ChunkDraft] = []
    current_segments: list[Segment] = []
    current_tokens = 0
    chunk_index = 0

    for segment in segments:
        current_min_tokens = minimum_base_tokens(chunk_index, settings)
        projected_tokens = current_tokens + segment.token_len
        should_break = False

        if current_segments:
            if (
                segment.block_type == "heading"
                and current_tokens >= current_min_tokens
                and current_tokens >= settings.preferred_chunk_tokens
            ):
                should_break = True
            elif (
                projected_tokens > settings.base_max_tokens
                and current_tokens >= current_min_tokens
            ):
                should_break = True

        if should_break:
            chunks.append(
                ChunkDraft(
                    document_id=current_segments[0].document_id,
                    product=current_segments[0].product,
                    version_if_available=current_segments[0].version_if_available,
                    segments=current_segments,
                )
            )
            current_segments = []
            current_tokens = 0
            chunk_index += 1

        current_segments.append(segment)
        current_tokens += segment.token_len

    if current_segments:
        chunks.append(
            ChunkDraft(
                document_id=current_segments[0].document_id,
                product=current_segments[0].product,
                version_if_available=current_segments[0].version_if_available,
                segments=current_segments,
            )
        )

    return chunks


def merge_short_chunks(
    chunks: list[ChunkDraft],
    settings: ChunkingSettings,
) -> list[ChunkDraft]:
    if len(chunks) <= 1:
        return chunks

    index = 0
    while index < len(chunks):
        minimum_tokens = minimum_base_tokens(index, settings)
        current_tokens = chunks[index].base_token_len
        if current_tokens >= minimum_tokens:
            index += 1
            continue

        if index == len(chunks) - 1:
            chunks[index - 1].segments.extend(chunks[index].segments)
            chunks.pop(index)
            index = max(index - 1, 0)
            continue

        if index == 0:
            chunks[index + 1].segments = chunks[index].segments + chunks[index + 1].segments
            chunks.pop(index)
            continue

        previous_size = chunks[index - 1].base_token_len + current_tokens
        next_size = chunks[index + 1].base_token_len + current_tokens
        merge_with_previous = abs(settings.preferred_chunk_tokens - previous_size) <= abs(
            settings.preferred_chunk_tokens - next_size
        )

        if merge_with_previous:
            chunks[index - 1].segments.extend(chunks[index].segments)
        else:
            chunks[index + 1].segments = chunks[index].segments + chunks[index + 1].segments
        chunks.pop(index)
        index = max(index - 1, 0)

    return chunks


def choose_split_index(
    segments: list[Segment],
    settings: ChunkingSettings,
) -> int | None:
    total_tokens = estimate_token_length("\n\n".join(segment.text for segment in segments))
    running_tokens = 0
    candidates: list[tuple[int, int, int]] = []

    for index in range(1, len(segments)):
        running_tokens += segments[index - 1].token_len
        remaining_tokens = total_tokens - running_tokens
        if running_tokens < settings.base_min_tokens_after_first:
            continue
        if remaining_tokens < settings.base_min_tokens_after_first:
            continue

        heading_bonus = 0 if segments[index].block_type == "heading" else 15
        score = abs(settings.preferred_chunk_tokens - running_tokens) + heading_bonus
        candidates.append((score, index, running_tokens))

    if not candidates:
        return None

    candidates.sort()
    return candidates[0][1]


def split_oversized_chunks(
    chunks: list[ChunkDraft],
    settings: ChunkingSettings,
) -> list[ChunkDraft]:
    adjusted: list[ChunkDraft] = []

    for chunk in chunks:
        pending = [chunk]
        while pending:
            current = pending.pop(0)
            if current.base_token_len <= settings.base_max_tokens or len(current.segments) < 2:
                adjusted.append(current)
                continue

            split_index = choose_split_index(current.segments, settings)
            if split_index is None:
                adjusted.append(current)
                continue

            left = ChunkDraft(
                document_id=current.document_id,
                product=current.product,
                version_if_available=current.version_if_available,
                segments=current.segments[:split_index],
            )
            right = ChunkDraft(
                document_id=current.document_id,
                product=current.product,
                version_if_available=current.version_if_available,
                segments=current.segments[split_index:],
            )
            pending = [left, right, *pending]

    return adjusted


def rebalance_short_chunks(
    chunks: list[ChunkDraft],
    settings: ChunkingSettings,
) -> list[ChunkDraft]:
    if len(chunks) <= 1:
        return chunks

    for index in range(1, len(chunks)):
        minimum_tokens = minimum_base_tokens(index, settings)
        while chunks[index].base_token_len < minimum_tokens:
            donor = chunks[index - 1]
            donor_minimum = minimum_base_tokens(index - 1, settings)
            if len(donor.segments) <= 1:
                break

            candidate = donor.segments[-1]
            donor_after = donor.base_token_len - candidate.token_len
            if donor_after < donor_minimum:
                break

            chunks[index].segments = [candidate, *chunks[index].segments]
            donor.segments.pop()

    return chunks


def build_chunk_records(
    document_sections: list[dict[str, Any]],
    settings: ChunkingSettings,
) -> tuple[list[dict[str, Any]], int]:
    document_id = str(document_sections[0]["document_id"])
    segments = [
        segment
        for section in document_sections
        for segment in build_segments_for_section(section, settings)
    ]
    base_chunks = initial_chunk_boundaries(segments, settings)
    base_chunks = split_oversized_chunks(base_chunks, settings)
    base_chunks = rebalance_short_chunks(base_chunks, settings)
    base_chunks = merge_short_chunks(base_chunks, settings)
    chunk_records: list[dict[str, Any]] = []
    overlap_shrunk = 0

    for chunk_index, chunk in enumerate(base_chunks):
        base_content = chunk.base_content
        overlap_text = ""
        overlap_token_len = 0

        if chunk_index > 0:
            label_token_len = estimate_token_length(settings.overlap_label) + 1
            max_affordable_overlap = max(
                0,
                settings.target_max_tokens - chunk.base_token_len - label_token_len,
            )
            overlap_budget = min(settings.overlap_target_tokens, max_affordable_overlap)
            overlap_text = trim_tail_tokens(base_chunks[chunk_index - 1].base_content, overlap_budget)
            overlap_token_len = estimate_token_length(overlap_text)

            if overlap_token_len < settings.overlap_min_tokens:
                fallback_budget = min(settings.overlap_max_tokens, max_affordable_overlap)
                overlap_text = trim_tail_tokens(base_chunks[chunk_index - 1].base_content, fallback_budget)
                overlap_token_len = estimate_token_length(overlap_text)

            if 0 < overlap_budget < settings.overlap_target_tokens:
                overlap_shrunk += 1

        if overlap_text:
            content = (
                f"{settings.overlap_label}\n"
                f"{overlap_text}\n\n"
                f"{base_content}"
            ).strip()
        else:
            content = base_content

        token_len = estimate_token_length(content)
        below_target_reason = None
        if token_len < settings.target_min_tokens:
            if len(base_chunks) == 1:
                below_target_reason = "document_too_short"
            elif overlap_token_len < settings.overlap_min_tokens:
                below_target_reason = "overlap_constrained"

        section_ids = ordered_unique(segment.section_id for segment in chunk.segments)
        section_paths = ordered_unique(segment.section_path for segment in chunk.segments)
        heading_hierarchies = ordered_unique(
            " > ".join(segment.heading_hierarchy)
            for segment in chunk.segments
            if segment.heading_hierarchy
        )
        source_urls = ordered_unique(segment.source_url for segment in chunk.segments if segment.source_url)

        chunk_records.append(
            {
                "chunk_id": stable_hash(f"{document_id}:{chunk_index}", "chk"),
                "document_id": document_id,
                "section_id": section_ids[0],
                "chunk_index_in_doc": chunk_index,
                "section_path": section_paths[0],
                "content": content,
                "char_len": len(content),
                "token_len": token_len,
                "previous_chunk_id": None,
                "next_chunk_id": None,
                "code_presence": chunk.code_presence,
                "product": chunk.product,
                "version_if_available": chunk.version_if_available,
                "metadata": {
                    "section_ids": section_ids,
                    "section_paths": section_paths,
                    "heading_hierarchies": heading_hierarchies,
                    "source_urls": source_urls,
                    "base_token_len": chunk.base_token_len,
                    "overlap_token_len": overlap_token_len,
                    "below_target_reason": below_target_reason,
                    "target_token_range": [
                        settings.target_min_tokens,
                        settings.target_max_tokens,
                    ],
                },
            }
        )

    for index, record in enumerate(chunk_records):
        if index > 0:
            record["previous_chunk_id"] = chunk_records[index - 1]["chunk_id"]
        if index < len(chunk_records) - 1:
            record["next_chunk_id"] = chunk_records[index + 1]["chunk_id"]

    return chunk_records, overlap_shrunk


def build_chunk_neighbors(chunks: list[dict[str, Any]]) -> list[dict[str, Any]]:
    relations: list[dict[str, Any]] = []

    for source_index, source in enumerate(chunks):
        for neighbor_index, neighbor in enumerate(chunks):
            distance = abs(source_index - neighbor_index)
            if distance == 0 or distance > 6:
                continue

            relations.append(
                {
                    "source_chunk_id": source["chunk_id"],
                    "neighbor_chunk_id": neighbor["chunk_id"],
                    "neighbor_type": "near" if distance <= 2 else "far",
                    "distance": distance,
                    "metadata": {
                        "document_id": source["document_id"],
                        "source_chunk_index": source["chunk_index_in_doc"],
                        "neighbor_chunk_index": neighbor["chunk_index_in_doc"],
                    },
                }
            )

    return relations


def escape_sql_literal(value: str) -> str:
    return value.replace("'", "''")


def write_chunk_relations_sql(
    output_path: Path,
    relations: list[dict[str, Any]],
) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "-- Generated by pipeline/preprocess/chunk_docs.py",
        "BEGIN;",
    ]

    if relations:
        lines.append(
            "INSERT INTO chunk_neighbors "
            "(source_chunk_id, neighbor_chunk_id, neighbor_type, distance, metadata) VALUES"
        )
        values: list[str] = []
        for relation in relations:
            metadata_json = json.dumps(relation["metadata"], ensure_ascii=False)
            values.append(
                "    ('{source}', '{neighbor}', '{neighbor_type}', {distance}, '{metadata}'::jsonb)".format(
                    source=escape_sql_literal(str(relation["source_chunk_id"])),
                    neighbor=escape_sql_literal(str(relation["neighbor_chunk_id"])),
                    neighbor_type=escape_sql_literal(str(relation["neighbor_type"])),
                    distance=int(relation["distance"]),
                    metadata=escape_sql_literal(metadata_json),
                )
            )
        lines.append(",\n".join(values))
        lines.append(
            "ON CONFLICT (source_chunk_id, neighbor_chunk_id, neighbor_type) DO UPDATE SET "
            "distance = EXCLUDED.distance, metadata = EXCLUDED.metadata;"
        )

    lines.append("COMMIT;")
    output_path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def add_glossary_candidate(
    bucket: dict[tuple[str, str], dict[str, Any]],
    *,
    term_type: str,
    canonical_form: str,
    alias_candidates: list[str] | None,
    source_product: str | None,
    document_id: str,
    section_id: str,
    keep_in_english: bool,
) -> None:
    normalized = canonical_form.strip()
    if not normalized:
        return

    key = (term_type, normalized.casefold())
    record = bucket.setdefault(
        key,
        {
            "glossary_term_id": stable_hash(f"{term_type}:{normalized.casefold()}", "gls"),
            "term_type": term_type,
            "canonical_form": normalized,
            "aliases": set(),
            "keep_in_english": keep_in_english,
            "source_products": set(),
            "document_ids": set(),
            "section_ids": set(),
            "evidence_count": 0,
        },
    )

    if alias_candidates:
        record["aliases"].update(
            alias.strip()
            for alias in alias_candidates
            if alias and alias.strip() and alias.strip() != normalized
        )
    if source_product:
        record["source_products"].add(source_product)
    record["document_ids"].add(document_id)
    record["section_ids"].add(section_id)
    record["evidence_count"] += 1

    existing = record["canonical_form"]
    if len(normalized) > len(existing):
        record["aliases"].add(existing)
        record["canonical_form"] = normalized


def looks_like_cli_command(line: str, prefixes: list[str]) -> bool:
    stripped = line.strip().lstrip("$").strip()
    return any(stripped.startswith(prefix) for prefix in prefixes)


def extract_glossary_terms(
    documents: dict[str, list[dict[str, Any]]],
    settings: GlossarySettings,
) -> list[dict[str, Any]]:
    candidates: dict[tuple[str, str], dict[str, Any]] = {}

    for document_id, sections in documents.items():
        LOGGER.info("[glossary] scanning document=%s sections=%s", document_id, len(sections))
        for section in sections:
            section_document_id = str(section["document_id"])
            section_id = str(section["section_id"])
            source_product = section.get("product")
            cleaned_text = str(section.get("cleaned_text", ""))
            structural_blocks = section.get("structural_blocks", [])
            search_spaces = [cleaned_text]
            search_spaces.extend(
                str(block.get("text", ""))
                for block in structural_blocks
                if str(block.get("text", "")).strip()
            )

            for product_term in settings.spring_products:
                aliases = [product_term.canonical_form, *product_term.aliases]
                if any(re.search(rf"\b{re.escape(alias)}\b", cleaned_text, re.IGNORECASE) for alias in aliases):
                    add_glossary_candidate(
                        candidates,
                        term_type="spring_product",
                        canonical_form=product_term.canonical_form,
                        alias_candidates=product_term.aliases,
                        source_product=source_product,
                        document_id=section_document_id,
                        section_id=section_id,
                        keep_in_english=settings.keep_in_english_default,
                    )

            for text in search_spaces:
                for annotation in ANNOTATION_RE.findall(text):
                    add_glossary_candidate(
                        candidates,
                        term_type="annotation",
                        canonical_form=annotation,
                        alias_candidates=[annotation.removeprefix("@")],
                        source_product=source_product,
                        document_id=section_document_id,
                        section_id=section_id,
                        keep_in_english=True,
                    )

                for config_key in CONFIG_KEY_RE.findall(text):
                    add_glossary_candidate(
                        candidates,
                        term_type="config_key",
                        canonical_form=config_key,
                        alias_candidates=None,
                        source_product=source_product,
                        document_id=section_document_id,
                        section_id=section_id,
                        keep_in_english=True,
                    )

                for dependency in MAVEN_COORD_RE.findall(text):
                    add_glossary_candidate(
                        candidates,
                        term_type="dependency_artifact",
                        canonical_form=dependency,
                        alias_candidates=None,
                        source_product=source_product,
                        document_id=section_document_id,
                        section_id=section_id,
                        keep_in_english=True,
                    )

                for starter in STARTER_RE.findall(text):
                    add_glossary_candidate(
                        candidates,
                        term_type="dependency_artifact",
                        canonical_form=starter,
                        alias_candidates=None,
                        source_product=source_product,
                        document_id=section_document_id,
                        section_id=section_id,
                        keep_in_english=True,
                    )

                for qualified_type in FULLY_QUALIFIED_TYPE_RE.findall(text):
                    short_name = qualified_type.rsplit(".", 1)[-1]
                    add_glossary_candidate(
                        candidates,
                        term_type="class_interface",
                        canonical_form=qualified_type,
                        alias_candidates=[short_name],
                        source_product=source_product,
                        document_id=section_document_id,
                        section_id=section_id,
                        keep_in_english=True,
                    )

                for match in TYPE_DECLARATION_RE.findall(text):
                    add_glossary_candidate(
                        candidates,
                        term_type="class_interface",
                        canonical_form=match,
                        alias_candidates=None,
                        source_product=source_product,
                        document_id=section_document_id,
                        section_id=section_id,
                        keep_in_english=True,
                    )

                for inline_type in INLINE_TYPE_RE.findall(text):
                    add_glossary_candidate(
                        candidates,
                        term_type="class_interface",
                        canonical_form=inline_type,
                        alias_candidates=None,
                        source_product=source_product,
                        document_id=section_document_id,
                        section_id=section_id,
                        keep_in_english=True,
                    )

            for block in structural_blocks:
                if str(block.get("type")) != "code":
                    continue
                for line in str(block.get("text", "")).splitlines():
                    if looks_like_cli_command(line, settings.cli_command_prefixes):
                        command = line.strip().lstrip("$").strip()
                        add_glossary_candidate(
                            candidates,
                            term_type="cli_command",
                            canonical_form=command,
                            alias_candidates=None,
                            source_product=source_product,
                            document_id=section_document_id,
                            section_id=section_id,
                            keep_in_english=True,
                        )

    glossary_records = []
    for record in candidates.values():
        source_products = sorted(record["source_products"])
        glossary_records.append(
            {
                "glossary_term_id": record["glossary_term_id"],
                "term_type": record["term_type"],
                "canonical_form": record["canonical_form"],
                "aliases": sorted(record["aliases"]),
                "keep_in_english": record["keep_in_english"],
                "source_product": source_products[0] if len(source_products) == 1 else None,
                "metadata": {
                    "source_products": source_products,
                    "document_ids": sorted(record["document_ids"]),
                    "section_ids": sorted(record["section_ids"]),
                    "evidence_count": record["evidence_count"],
                },
            }
        )

    return sorted(
        glossary_records,
        key=lambda item: (item["term_type"], item["canonical_form"].casefold()),
    )


def write_jsonl(output_path: Path, rows: list[dict[str, Any]]) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", encoding="utf-8") as sink:
        for row in rows:
            sink.write(json.dumps(row, ensure_ascii=False) + "\n")


def render_visualization_markdown(
    output_path: Path,
    *,
    summary: dict[str, Any],
    chunks_by_document: dict[str, list[dict[str, Any]]],
    glossary_terms: list[dict[str, Any]],
    settings: VisualizationSettings,
) -> None:
    lines = [
        "# Chunking + Glossary Sample",
        "",
        "## Summary",
        f"- documents processed: {summary['documents_processed']}",
        f"- sections read: {summary['sections_read']}",
        f"- chunks written: {summary['chunks_written']}",
        f"- glossary terms written: {summary['glossary_terms_written']}",
        f"- chunk neighbor rows: {summary['chunk_neighbor_rows']}",
        "",
        "## Sample Chunks",
    ]

    for document_id in list(chunks_by_document.keys())[: settings.max_documents]:
        sample_chunks = chunks_by_document[document_id][: settings.max_chunks_per_document]
        first_chunk = sample_chunks[0]
        lines.extend(
            [
                f"### {document_id}",
                f"- product: {first_chunk['product']}",
                f"- version: {first_chunk['version_if_available'] or 'n/a'}",
                f"- chunk count: {len(chunks_by_document[document_id])}",
                "",
            ]
        )
        for chunk in sample_chunks:
            preview = chunk["content"][:360].replace("\n", " ").strip()
            lines.extend(
                [
                    f"#### {chunk['chunk_id']}",
                    f"- chunk index: {chunk['chunk_index_in_doc']}",
                    f"- token length: {chunk['token_len']}",
                    f"- section span: {chunk['metadata']['section_paths'][0]} -> {chunk['metadata']['section_paths'][-1]}",
                    f"- prev / next: {chunk['previous_chunk_id'] or 'none'} / {chunk['next_chunk_id'] or 'none'}",
                    f"- overlap tokens: {chunk['metadata']['overlap_token_len']}",
                    "",
                    "```text",
                    f"{preview}...",
                    "```",
                    "",
                ]
            )

    lines.extend(
        [
            "## Sample Glossary Terms",
            "",
            "| term_type | canonical_form | source_product | evidence_count |",
            "| --- | --- | --- | --- |",
        ]
    )
    for term in glossary_terms[: settings.max_glossary_terms]:
        lines.append(
            f"| {term['term_type']} | {term['canonical_form']} | {term['source_product'] or 'mixed'} | {term['metadata']['evidence_count']} |"
        )

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def build_chunks_and_glossary(
    *,
    input_path: Path,
    output_chunks_path: Path,
    output_glossary_path: Path,
    output_relations_sql_path: Path,
    output_visualization_path: Path,
    config_path: Path,
    limit_documents: int | None = None,
    show_examples: bool = False,
) -> dict[str, Any]:
    started_at = time.monotonic()
    settings = load_settings(config_path)
    documents, sections_read = read_sections_by_document(
        input_path=input_path,
        limit_documents=limit_documents,
    )

    chunk_records: list[dict[str, Any]] = []
    chunk_neighbors: list[dict[str, Any]] = []
    chunks_by_document: dict[str, list[dict[str, Any]]] = {}
    overlap_shrunk_chunks = 0

    for document_id, sections in documents.items():
        document_chunks, shrunk_count = build_chunk_records(sections, settings.chunking)
        chunk_records.extend(document_chunks)
        chunks_by_document[document_id] = document_chunks
        chunk_neighbors.extend(build_chunk_neighbors(document_chunks))
        overlap_shrunk_chunks += shrunk_count
        LOGGER.info(
            "Chunked document %s into %s chunks",
            document_id,
            len(document_chunks),
        )

    glossary_terms = extract_glossary_terms(documents, settings.glossary)

    write_jsonl(output_chunks_path, chunk_records)
    write_jsonl(output_glossary_path, glossary_terms)
    write_chunk_relations_sql(output_relations_sql_path, chunk_neighbors)

    summary = {
        "input_path": str(input_path),
        "config_path": str(config_path),
        "output_chunks_path": str(output_chunks_path),
        "output_glossary_path": str(output_glossary_path),
        "output_relations_sql_path": str(output_relations_sql_path),
        "output_visualization_path": str(output_visualization_path),
        "documents_processed": len(documents),
        "sections_read": sections_read,
        "chunks_written": len(chunk_records),
        "glossary_terms_written": len(glossary_terms),
        "chunk_neighbor_rows": len(chunk_neighbors),
        "overlap_shrunk_chunks": overlap_shrunk_chunks,
        "average_chunks_per_document": (
            round(len(chunk_records) / len(documents), 2) if documents else 0.0
        ),
        "elapsed_seconds": round(time.monotonic() - started_at, 2),
    }

    render_visualization_markdown(
        output_visualization_path,
        summary=summary,
        chunks_by_document=chunks_by_document,
        glossary_terms=glossary_terms,
        settings=settings.chunking.visualization,
    )

    if show_examples and chunk_records:
        print("=== CHUNK EXAMPLE ===")
        print(json.dumps(chunk_records[0], ensure_ascii=False, indent=2))
    if show_examples and glossary_terms:
        print("=== GLOSSARY EXAMPLE ===")
        print(json.dumps(glossary_terms[0], ensure_ascii=False, indent=2))

    return summary


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Build retrieval-oriented chunks, glossary terms, and chunk neighbor SQL."
    )
    parser.add_argument(
        "--input",
        default="data/processed/spring_docs_sections.jsonl",
        help="Section-level JSONL input file.",
    )
    parser.add_argument(
        "--output-chunks",
        default="data/processed/chunks.jsonl",
        help="Chunk JSONL output file.",
    )
    parser.add_argument(
        "--output-glossary",
        default="data/processed/glossary_terms.jsonl",
        help="Glossary JSONL output file.",
    )
    parser.add_argument(
        "--output-relations-sql",
        default="data/processed/chunk_neighbors.sql",
        help="SQL script for chunk_neighbors inserts.",
    )
    parser.add_argument(
        "--output-visualization",
        default="data/processed/chunking_visualization.md",
        help="Markdown visualization output.",
    )
    parser.add_argument(
        "--config",
        default="configs/app/chunking.yml",
        help="Chunking and glossary YAML config.",
    )
    parser.add_argument(
        "--limit-documents",
        type=int,
        default=None,
        help="Optional document limit for dry runs.",
    )
    parser.add_argument(
        "--show-examples",
        action="store_true",
        help="Print one chunk and one glossary record after the run.",
    )
    parser.add_argument(
        "--log-level",
        default="INFO",
        help="Python logging level.",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_arg_parser()
    args = parser.parse_args(argv)
    configure_logging(args.log_level)

    summary = build_chunks_and_glossary(
        input_path=Path(args.input),
        output_chunks_path=Path(args.output_chunks),
        output_glossary_path=Path(args.output_glossary),
        output_relations_sql_path=Path(args.output_relations_sql),
        output_visualization_path=Path(args.output_visualization),
        config_path=Path(args.config),
        limit_documents=args.limit_documents,
        show_examples=args.show_examples,
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
