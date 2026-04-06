from __future__ import annotations

import argparse
import json
import logging
from pathlib import Path

from preprocess.chunk_docs import (
    extract_glossary_terms,
    load_settings,
    read_sections_by_document,
    write_jsonl,
)


LOGGER = logging.getLogger(__name__)


def configure_logging(level: str) -> None:
    logging.basicConfig(
        level=getattr(logging, level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s - %(message)s",
    )


def build_glossary_only(
    *,
    input_path: Path,
    output_glossary_path: Path,
    config_path: Path,
    limit_documents: int | None = None,
    show_examples: bool = False,
) -> dict[str, object]:
    settings = load_settings(config_path)
    documents, sections_read = read_sections_by_document(
        input_path=input_path,
        limit_documents=limit_documents,
    )
    glossary_terms = extract_glossary_terms(documents, settings.glossary)
    write_jsonl(output_glossary_path, glossary_terms)

    if show_examples and glossary_terms:
        print("=== GLOSSARY EXAMPLE ===")
        print(json.dumps(glossary_terms[0], ensure_ascii=False, indent=2))

    return {
        "input_path": str(input_path),
        "config_path": str(config_path),
        "output_glossary_path": str(output_glossary_path),
        "documents_processed": len(documents),
        "sections_read": sections_read,
        "glossary_terms_written": len(glossary_terms),
    }


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Extract glossary terms from normalized section JSONL."
    )
    parser.add_argument(
        "--input",
        default="data/processed/spring_docs_sections.jsonl",
        help="Section-level JSONL input file.",
    )
    parser.add_argument(
        "--output-glossary",
        default="data/processed/glossary_terms.jsonl",
        help="Glossary JSONL output file.",
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
        help="Print one glossary record after the run.",
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
    summary = build_glossary_only(
        input_path=Path(args.input),
        output_glossary_path=Path(args.output_glossary),
        config_path=Path(args.config),
        limit_documents=args.limit_documents,
        show_examples=args.show_examples,
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
