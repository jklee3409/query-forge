from __future__ import annotations

import argparse
import json
import logging
import re
import time
from pathlib import Path
from typing import Any

try:
    from preprocess.chunk_docs import extract_glossary_terms, load_settings, write_jsonl
except ModuleNotFoundError:  # pragma: no cover
    from chunk_docs import extract_glossary_terms, load_settings, write_jsonl


LOGGER = logging.getLogger(__name__)
CODE_FENCE_RE = re.compile(r"```(?:[^\n`]*)\n([\s\S]*?)```")


def configure_logging(level: str) -> None:
    logging.basicConfig(
        level=getattr(logging, level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s - %(message)s",
    )


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8-sig") as source:
        for line in source:
            if not line.strip():
                continue
            rows.append(json.loads(line))
    return rows


def _extract_code_blocks(text: str) -> list[dict[str, str]]:
    if not text:
        return []
    blocks: list[dict[str, str]] = []
    for match in CODE_FENCE_RE.finditer(text):
        code = (match.group(1) or "").strip()
        if code:
            blocks.append({"type": "code", "text": code})
    return blocks


def _build_documents(chunks: list[dict[str, Any]]) -> tuple[dict[str, list[dict[str, Any]]], dict[str, str]]:
    documents: dict[str, list[dict[str, Any]]] = {}
    chunk_to_document: dict[str, str] = {}
    for row in chunks:
        document_id = str(row["document_id"])
        chunk_id = str(row["chunk_id"])
        chunk_text = str(row.get("chunk_text") or "")
        product = row.get("product_name") or row.get("product")
        chunk_to_document[chunk_id] = document_id
        section_record = {
            "document_id": document_id,
            "section_id": chunk_id,
            "product": str(product) if product is not None else None,
            "cleaned_text": chunk_text,
            "structural_blocks": _extract_code_blocks(chunk_text),
        }
        documents.setdefault(document_id, []).append(section_record)
    return documents, chunk_to_document


def _to_candidate_rows(glossary_terms: list[dict[str, Any]], chunk_to_document: dict[str, str]) -> list[dict[str, Any]]:
    dedup: dict[tuple[str, str, str, str], dict[str, Any]] = {}
    for term in glossary_terms:
        term_type = str(term.get("term_type") or "").strip()
        canonical_form = str(term.get("canonical_form") or "").strip()
        if not term_type or not canonical_form:
            continue
        metadata = term.get("metadata") or {}
        section_ids = metadata.get("section_ids") or []
        for section_id in section_ids:
            chunk_id = str(section_id or "").strip()
            if not chunk_id:
                continue
            document_id = chunk_to_document.get(chunk_id)
            if not document_id:
                continue
            key = (document_id, chunk_id, term_type, canonical_form.casefold())
            dedup.setdefault(
                key,
                {
                    "document_id": document_id,
                    "chunk_id": chunk_id,
                    "term_type": term_type,
                    "canonical_form": canonical_form,
                    "matched_text": canonical_form,
                },
            )
    return list(dedup.values())


def extract_anchor_candidates_from_chunks(
    *,
    input_chunks_path: Path,
    output_candidates_path: Path,
    config_path: Path,
) -> dict[str, Any]:
    started_at = time.monotonic()
    chunk_rows = _read_jsonl(input_chunks_path)
    settings = load_settings(config_path)
    documents, chunk_to_document = _build_documents(chunk_rows)
    glossary_terms = extract_glossary_terms(documents, settings.glossary)
    candidates = _to_candidate_rows(glossary_terms, chunk_to_document)
    write_jsonl(output_candidates_path, candidates)
    summary = {
        "input_chunks_path": str(input_chunks_path),
        "output_candidates_path": str(output_candidates_path),
        "config_path": str(config_path),
        "input_chunk_count": len(chunk_rows),
        "document_count": len(documents),
        "glossary_term_count": len(glossary_terms),
        "candidate_count": len(candidates),
        "elapsed_seconds": round(time.monotonic() - started_at, 2),
    }
    LOGGER.info("[anchor-candidates] summary=%s", summary)
    return summary


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Extract anchor candidates from chunk JSONL using pipeline glossary logic."
    )
    parser.add_argument(
        "--input-chunks",
        required=True,
        help="Chunk-level JSONL input path (chunk_id/document_id/chunk_text required).",
    )
    parser.add_argument(
        "--output-candidates",
        required=True,
        help="Anchor candidate JSONL output path.",
    )
    parser.add_argument(
        "--config",
        default="configs/app/chunking.yml",
        help="Chunking/glossary config YAML path.",
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
    summary = extract_anchor_candidates_from_chunks(
        input_chunks_path=Path(args.input_chunks),
        output_candidates_path=Path(args.output_candidates),
        config_path=Path(args.config),
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
