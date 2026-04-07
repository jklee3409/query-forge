from __future__ import annotations

import argparse
import hashlib
import json
import logging
import re
import time
from pathlib import Path
from typing import Any

from bs4 import BeautifulSoup, NavigableString, Tag


LOGGER = logging.getLogger(__name__)
SECTION_CLASS_RE = re.compile(r"^sect[1-6]$")
WHITESPACE_RE = re.compile(r"\s+")


def configure_logging(level: str) -> None:
    logging.basicConfig(
        level=getattr(logging, level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s - %(message)s",
    )


def normalize_whitespace(text: str) -> str:
    return WHITESPACE_RE.sub(" ", text).strip()


def stable_hash(value: str, prefix: str) -> str:
    digest = hashlib.sha1(value.encode("utf-8")).hexdigest()
    return f"{prefix}_{digest[:16]}"


def is_section_container(tag: Tag) -> bool:
    classes = tag.get("class", [])
    return any(SECTION_CLASS_RE.match(name) for name in classes) or "section-summary" in classes


def list_items_from_tag(tag: Tag) -> list[str]:
    if tag.name in {"ul", "ol"}:
        items = [
            normalize_whitespace(li.get_text(" ", strip=True))
            for li in tag.find_all("li", recursive=False)
        ]
        return [item for item in items if item]

    if tag.name == "dl":
        items: list[str] = []
        current_term: str | None = None
        for child in tag.find_all(["dt", "dd"], recursive=False):
            text = normalize_whitespace(child.get_text(" ", strip=True))
            if not text:
                continue
            if child.name == "dt":
                current_term = text
            elif current_term:
                items.append(f"{current_term}: {text}")
                current_term = None
            else:
                items.append(text)
        return items

    return []


def table_payload(table: Tag) -> dict[str, Any]:
    rows: list[list[str]] = []
    headers: list[str] = []

    thead = table.find("thead")
    if thead:
        for row in thead.find_all("tr", recursive=False):
            header_row = [
                normalize_whitespace(cell.get_text(" ", strip=True))
                for cell in row.find_all(["th", "td"], recursive=False)
            ]
            if any(header_row):
                headers = header_row

    body = table.find("tbody") or table
    for row in body.find_all("tr", recursive=False):
        cells = [
            normalize_whitespace(cell.get_text(" ", strip=True))
            for cell in row.find_all(["th", "td"], recursive=False)
        ]
        if any(cells):
            rows.append(cells)

    text_lines = []
    if headers:
        text_lines.append(" | ".join(headers))
    for row in rows:
        text_lines.append(" | ".join(row))

    return {
        "type": "table",
        "headers": headers,
        "rows": rows,
        "text": "\n".join(text_lines).strip(),
    }


def code_block_payload(pre_tag: Tag) -> dict[str, Any]:
    code = pre_tag.find("code")
    language = None
    if code:
        for class_name in code.get("class", []):
            if class_name.startswith("language-"):
                language = class_name.removeprefix("language-")
                break

    text = pre_tag.get_text("\n", strip=False).strip("\n")
    return {
        "type": "code",
        "language": language,
        "text": text,
    }


def admonition_payload(tag: Tag) -> dict[str, Any]:
    admonition_type = "admonition"
    for class_name in tag.get("class", []):
        if class_name != "admonitionblock":
            admonition_type = class_name
            break

    return {
        "type": "admonition",
        "admonition_type": admonition_type,
        "text": normalize_whitespace(tag.get_text(" ", strip=True)),
    }


def extract_blocks(container: Tag) -> list[dict[str, Any]]:
    blocks: list[dict[str, Any]] = []

    for child in container.children:
        if isinstance(child, NavigableString):
            continue
        if not isinstance(child, Tag):
            continue
        if is_section_container(child):
            continue

        classes = set(child.get("class", []))

        if child.name == "p":
            text = normalize_whitespace(child.get_text(" ", strip=True))
            if text:
                blocks.append({"type": "paragraph", "text": text})
            continue

        if child.name == "pre":
            blocks.append(code_block_payload(child))
            continue

        if child.name == "table":
            payload = table_payload(child)
            if payload["text"]:
                blocks.append(payload)
            continue

        if child.name in {"ul", "ol", "dl"}:
            items = list_items_from_tag(child)
            if items:
                blocks.append(
                    {
                        "type": "list",
                        "ordered": child.name == "ol",
                        "items": items,
                        "text": "\n".join(items),
                    }
                )
            continue

        if "paragraph" in classes:
            paragraph = child.find("p")
            text = normalize_whitespace(
                paragraph.get_text(" ", strip=True) if paragraph else child.get_text(" ", strip=True)
            )
            if text:
                blocks.append({"type": "paragraph", "text": text})
            continue

        if classes.intersection({"listingblock", "literalblock", "sourceblock"}):
            pre_tag = child.find("pre")
            if pre_tag:
                blocks.append(code_block_payload(pre_tag))
            continue

        if classes.intersection({"ulist", "olist", "dlist"}):
            nested_list = child.find(["ul", "ol", "dl"])
            if nested_list:
                items = list_items_from_tag(nested_list)
                if items:
                    blocks.append(
                        {
                            "type": "list",
                            "ordered": nested_list.name == "ol",
                            "items": items,
                            "text": "\n".join(items),
                        }
                    )
            continue

        if "tableblock" in classes:
            table = child.find("table")
            if table:
                payload = table_payload(table)
                if payload["text"]:
                    blocks.append(payload)
            continue

        if "admonitionblock" in classes:
            payload = admonition_payload(child)
            if payload["text"]:
                blocks.append(payload)
            continue

        if classes.intersection({"openblock", "exampleblock", "quoteblock", "content", "sectionbody"}):
            blocks.extend(extract_blocks(child))
            continue

        blocks.extend(extract_blocks(child))

    return blocks


def extract_root_blocks(article: Tag) -> list[dict[str, Any]]:
    root_blocks: list[dict[str, Any]] = []

    for child in article.find_all(recursive=False):
        if not isinstance(child, Tag):
            continue
        if child.name == "h1":
            continue
        if child.name == "nav":
            continue
        if child.get("id") == "preamble":
            root_blocks.extend(extract_blocks(child))
            continue
        if "breadcrumbs-container" in child.get("class", []):
            continue
        if is_section_container(child):
            continue
        root_blocks.extend(extract_blocks(child))

    return root_blocks


def blocks_to_text(blocks: list[dict[str, Any]]) -> tuple[str, str]:
    raw_parts: list[str] = []
    clean_parts: list[str] = []

    for block in blocks:
        text = str(block.get("text", "")).strip()
        if not text:
            continue
        raw_parts.append(f"[{block['type'].upper()}]\n{text}")
        clean_parts.append(text)

    return "\n\n".join(raw_parts).strip(), "\n\n".join(clean_parts).strip()


def heading_text_and_id(section_node: Tag) -> tuple[str, str | None, int]:
    heading = section_node.find(["h1", "h2", "h3", "h4", "h5", "h6"], recursive=False)
    if heading:
        return (
            normalize_whitespace(heading.get_text(" ", strip=True)),
            heading.get("id"),
            int(heading.name[1]),
        )

    fallback_title = normalize_whitespace(section_node.get_text(" ", strip=True).split("\n", 1)[0])
    return fallback_title or "Untitled Section", None, 0


def extract_article(html: str) -> Tag | None:
    soup = BeautifulSoup(html, "html.parser")
    article = soup.select_one("article.doc")
    if not article:
        return None

    for selector in (
        ".breadcrumbs-container",
        ".page-pagination",
        ".toolbar",
        "nav.pagination",
        "nav.toc",
        "aside",
    ):
        for node in article.select(selector):
            node.decompose()

    return article


def normalize_document(raw_record: dict[str, Any]) -> list[dict[str, Any]]:
    article = extract_article(raw_record["html"])
    if article is None:
        LOGGER.warning("No article.doc found for %s", raw_record["source_url"])
        return []

    document_title_node = article.select_one("h1.page")
    document_title = (
        normalize_whitespace(document_title_node.get_text(" ", strip=True))
        if document_title_node
        else raw_record["title"]
    )
    version = raw_record.get("version_if_available")

    records: list[dict[str, Any]] = []
    seen_section_keys: set[tuple[str, str]] = set()

    root_blocks = extract_root_blocks(article)
    if root_blocks:
        raw_text, cleaned_text = blocks_to_text(root_blocks)
        if cleaned_text:
            section_path = [document_title]
            section_record = {
                "document_id": raw_record["document_id"],
                "section_id": stable_hash(
                    f"{raw_record['document_id']}::{document_title}",
                    "sec",
                ),
                "source_url": raw_record["canonical_url"],
                "product": raw_record["product"],
                "version_if_available": version,
                "title": raw_record["title"],
                "document_title": document_title,
                "section_title": document_title,
                "section_anchor": document_title_node.get("id") if document_title_node else None,
                "section_path": " > ".join(section_path),
                "heading_hierarchy": section_path,
                "heading_level": 1,
                "raw_text": raw_text,
                "cleaned_text": cleaned_text,
                "structural_blocks": root_blocks,
                "section_hash": hashlib.sha1(cleaned_text.encode("utf-8")).hexdigest(),
                "metadata": raw_record.get("metadata", {}),
            }
            records.append(section_record)
            seen_section_keys.add((section_record["section_path"], cleaned_text))

    def walk_sections(section_node: Tag, parent_hierarchy: list[str]) -> None:
        section_title, anchor_id, heading_level = heading_text_and_id(section_node)
        hierarchy = [*parent_hierarchy, section_title]
        body = section_node.find("div", class_="sectionbody", recursive=False) or section_node
        blocks = extract_blocks(body)
        raw_text, cleaned_text = blocks_to_text(blocks)
        section_path = " > ".join(hierarchy)

        if cleaned_text:
            section_key = (section_path, cleaned_text)
            if section_key not in seen_section_keys:
                seen_section_keys.add(section_key)
                records.append(
                    {
                        "document_id": raw_record["document_id"],
                        "section_id": stable_hash(
                            f"{raw_record['document_id']}::{anchor_id or section_path}",
                            "sec",
                        ),
                        "source_url": raw_record["canonical_url"],
                        "product": raw_record["product"],
                        "version_if_available": version,
                        "title": raw_record["title"],
                        "document_title": document_title,
                        "section_title": section_title,
                        "section_anchor": anchor_id,
                        "section_path": section_path,
                        "heading_hierarchy": hierarchy,
                        "heading_level": heading_level,
                        "raw_text": raw_text,
                        "cleaned_text": cleaned_text,
                        "structural_blocks": blocks,
                        "section_hash": hashlib.sha1(cleaned_text.encode("utf-8")).hexdigest(),
                        "metadata": raw_record.get("metadata", {}),
                    }
                )

        for child in body.find_all(recursive=False):
            if isinstance(child, Tag) and is_section_container(child):
                walk_sections(child, hierarchy)

    for child in article.find_all(recursive=False):
        if isinstance(child, Tag) and is_section_container(child):
            walk_sections(child, [document_title])

    return records


def normalize_documents(
    input_path: Path,
    output_path: Path,
    limit: int | None = None,
    show_examples: bool = False,
) -> dict[str, Any]:
    output_path.parent.mkdir(parents=True, exist_ok=True)

    processed_sections = 0
    processed_documents = 0
    started_at = time.monotonic()
    example_raw: dict[str, Any] | None = None
    example_processed: dict[str, Any] | None = None

    with input_path.open("r", encoding="utf-8") as source, output_path.open(
        "w", encoding="utf-8"
    ) as sink:
        for line in source:
            if limit is not None and processed_documents >= limit:
                break
            if not line.strip():
                continue

            raw_record = json.loads(line)
            example_raw = example_raw or {
                key: value for key, value in raw_record.items() if key != "html"
            }
            normalized_sections = normalize_document(raw_record)
            processed_documents += 1

            for section in normalized_sections:
                sink.write(json.dumps(section, ensure_ascii=False) + "\n")
                processed_sections += 1
                example_processed = example_processed or section

            LOGGER.info(
                "[normalize] document=%s sections=%s processed=%s",
                raw_record["document_id"],
                len(normalized_sections),
                processed_documents,
            )

    if show_examples and example_raw and example_processed:
        print("=== RAW EXAMPLE ===")
        print(json.dumps(example_raw, ensure_ascii=False, indent=2))
        print("=== PROCESSED EXAMPLE ===")
        print(json.dumps(example_processed, ensure_ascii=False, indent=2))

    return {
        "input_path": str(input_path),
        "output_path": str(output_path),
        "documents_processed": processed_documents,
        "sections_written": processed_sections,
        "elapsed_seconds": round(time.monotonic() - started_at, 2),
    }


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Normalize collected Spring reference HTML into section-level JSONL."
    )
    parser.add_argument(
        "--input",
        default="data/raw/spring_docs_raw.jsonl",
        help="Raw collector JSONL input file.",
    )
    parser.add_argument(
        "--output",
        default="data/processed/spring_docs_sections.jsonl",
        help="Processed section-level JSONL output file.",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=None,
        help="Maximum number of raw documents to normalize.",
    )
    parser.add_argument(
        "--show-examples",
        action="store_true",
        help="Print one before/after example record.",
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

    summary = normalize_documents(
        input_path=Path(args.input),
        output_path=Path(args.output),
        limit=args.limit,
        show_examples=args.show_examples,
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
