from __future__ import annotations

import argparse
import hashlib
import json
import logging
import re
import time
from collections import deque
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable
from urllib.parse import urljoin, urlparse, urlunparse

import requests
import yaml
from bs4 import BeautifulSoup
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry


LOGGER = logging.getLogger(__name__)
HTML_SUFFIX_RE = re.compile(r"\.html?$", re.IGNORECASE)
SKIP_SUFFIXES = (
    ".pdf",
    ".zip",
    ".jar",
    ".jpg",
    ".jpeg",
    ".png",
    ".gif",
    ".svg",
    ".ico",
    ".css",
    ".js",
    ".map",
    ".txt",
    ".xml",
)
VERSION_SEGMENT_RE = re.compile(r"^\d+(?:\.\d+)*(?:[-.][A-Za-z0-9]+)?$")


@dataclass(slots=True)
class SourceConfig:
    source_id: str
    product: str
    start_urls: list[str]
    allow_prefixes: list[str]
    deny_url_patterns: list[str]
    request_delay_seconds: float = 0.2
    max_depth: int = 8
    enabled: bool = True
    metadata: dict[str, Any] | None = None


def configure_logging(level: str) -> None:
    logging.basicConfig(
        level=getattr(logging, level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s - %(message)s",
    )


def build_session() -> requests.Session:
    retry = Retry(
        total=3,
        backoff_factor=0.5,
        status_forcelist=(429, 500, 502, 503, 504),
        allowed_methods=("GET", "HEAD"),
        raise_on_status=False,
    )
    adapter = HTTPAdapter(max_retries=retry)

    session = requests.Session()
    session.headers.update(
        {
            "User-Agent": (
                "QueryForgeCollector/0.1 "
                "(research indexing for official Spring docs)"
            )
        }
    )
    session.mount("http://", adapter)
    session.mount("https://", adapter)
    return session


def normalize_url(url: str, base_url: str | None = None) -> str:
    resolved = urljoin(base_url, url) if base_url else url
    parsed = urlparse(resolved)
    path = re.sub(r"/{2,}", "/", parsed.path or "/")
    if path.endswith("/"):
        path = f"{path}index.html"
    normalized = parsed._replace(path=path, params="", query="", fragment="")
    return urlunparse(normalized)


def normalize_prefix(url: str) -> str:
    parsed = urlparse(url)
    path = re.sub(r"/{2,}", "/", parsed.path or "/")
    if not path.endswith("/"):
        path = f"{path}/"
    normalized = parsed._replace(path=path, params="", query="", fragment="")
    return urlunparse(normalized)


def stable_hash(value: str, prefix: str) -> str:
    digest = hashlib.sha1(value.encode("utf-8")).hexdigest()
    return f"{prefix}_{digest[:16]}"


def load_source_configs(config_dir: Path) -> list[SourceConfig]:
    configs: list[SourceConfig] = []

    for path in sorted(config_dir.glob("*.yml")) + sorted(config_dir.glob("*.yaml")):
        payload = yaml.safe_load(path.read_text(encoding="utf-8"))
        if not payload:
            continue

        items: Iterable[dict[str, Any]]
        if isinstance(payload, list):
            items = payload
        elif isinstance(payload, dict) and "sources" in payload:
            items = payload["sources"]
        else:
            items = [payload]

        for item in items:
            config = SourceConfig(
                source_id=item["source_id"],
                product=item["product"],
                start_urls=[normalize_url(url) for url in item["start_urls"]],
                allow_prefixes=[normalize_prefix(url) for url in item["allow_prefixes"]],
                deny_url_patterns=item.get("deny_url_patterns", []),
                request_delay_seconds=float(item.get("request_delay_seconds", 0.2)),
                max_depth=int(item.get("max_depth", 8)),
                enabled=bool(item.get("enabled", True)),
                metadata=item.get("metadata", {}),
            )
            if config.enabled:
                configs.append(config)

    if not configs:
        raise FileNotFoundError(f"No source configs found under {config_dir}")

    return configs


def is_allowed_url(url: str, config: SourceConfig) -> bool:
    lower_url = url.lower()
    if any(pattern.lower() in lower_url for pattern in config.deny_url_patterns):
        return False

    if not any(url.startswith(prefix) for prefix in config.allow_prefixes):
        return False

    prefix = next(prefix for prefix in config.allow_prefixes if url.startswith(prefix))
    relative_path = url.removeprefix(prefix)
    first_segment = relative_path.split("/", 1)[0]
    if first_segment and VERSION_SEGMENT_RE.match(first_segment):
        return False

    parsed = urlparse(url)
    if parsed.scheme not in {"http", "https"}:
        return False

    if any(parsed.path.lower().endswith(suffix) for suffix in SKIP_SUFFIXES):
        return False

    if not HTML_SUFFIX_RE.search(parsed.path) and not parsed.path.endswith("/index.html"):
        return False

    return True


def extract_links(html: str, page_url: str, config: SourceConfig) -> list[str]:
    soup = BeautifulSoup(html, "html.parser")
    links: set[str] = set()

    for anchor in soup.select("a[href]"):
        href = anchor.get("href", "").strip()
        if not href or href.startswith(("#", "mailto:", "javascript:")):
            continue

        normalized = normalize_url(href, page_url)
        if is_allowed_url(normalized, config):
            links.add(normalized)

    return sorted(links)


def extract_page_metadata(
    html: str,
    requested_url: str,
    response_url: str,
    config: SourceConfig,
    depth: int,
    parent_url: str | None,
) -> dict[str, Any]:
    soup = BeautifulSoup(html, "html.parser")
    canonical_link = soup.select_one("link[rel=canonical]")
    versioned_meta = soup.select_one('meta[name="versioned-url"]')
    version_meta = soup.select_one('meta[name="version"]')
    component_meta = soup.select_one('meta[name="component"]')
    generator_meta = soup.select_one('meta[name="generator"]')
    title_node = soup.select_one("h1.page") or soup.select_one("title")

    canonical_url = normalize_url(
        canonical_link["href"], response_url
    ) if canonical_link and canonical_link.get("href") else normalize_url(response_url)
    versioned_url = normalize_url(
        versioned_meta["content"], response_url
    ) if versioned_meta and versioned_meta.get("content") else canonical_url
    title = title_node.get_text(" ", strip=True) if title_node else canonical_url
    version = version_meta["content"].strip() if version_meta and version_meta.get("content") else None
    component = component_meta["content"].strip() if component_meta and component_meta.get("content") else None
    language_code = soup.html.get("lang", "en") if soup.html else "en"
    content_hash = hashlib.sha1(html.encode("utf-8")).hexdigest()
    document_id = stable_hash(f"{config.product}:{versioned_url}", "doc")

    return {
        "document_id": document_id,
        "source_id": config.source_id,
        "source_url": requested_url,
        "canonical_url": canonical_url,
        "versioned_url": versioned_url,
        "product": config.product,
        "version_if_available": version,
        "title": title,
        "language_code": language_code,
        "content_hash": content_hash,
        "fetched_at": datetime.now(timezone.utc).isoformat(),
        "html": html,
        "metadata": {
            "component": component,
            "generator": generator_meta["content"] if generator_meta and generator_meta.get("content") else None,
            "crawl_depth": depth,
            "discovered_from": parent_url,
            "response_url": response_url,
            **(config.metadata or {}),
        },
    }


def collect_documents(
    config_dir: Path,
    output_path: Path,
    limit: int | None = None,
    source_ids: set[str] | None = None,
    show_examples: bool = False,
) -> dict[str, Any]:
    configs = load_source_configs(config_dir)
    if source_ids:
        configs = [config for config in configs if config.source_id in source_ids]
        if not configs:
            raise ValueError(f"No matching source_ids found: {sorted(source_ids)}")

    output_path.parent.mkdir(parents=True, exist_ok=True)
    session = build_session()

    queue: deque[tuple[SourceConfig, str, int, str | None]] = deque()
    for config in configs:
        for start_url in config.start_urls:
            queue.append((config, start_url, 0, None))

    visited_urls: set[str] = set()
    seen_content_hashes: set[str] = set()
    seen_document_ids: set[str] = set()
    records_written = 0
    example_record: dict[str, Any] | None = None

    with output_path.open("w", encoding="utf-8") as sink:
        while queue and (limit is None or records_written < limit):
            config, url, depth, parent_url = queue.popleft()
            if url in visited_urls:
                continue
            visited_urls.add(url)

            LOGGER.info("Fetching %s", url)
            response = session.get(url, timeout=(10, 30))
            response.raise_for_status()

            content_type = response.headers.get("content-type", "")
            if "html" not in content_type:
                LOGGER.debug("Skipping non-HTML response %s (%s)", url, content_type)
                continue

            response.encoding = response.apparent_encoding or response.encoding or "utf-8"
            html = response.text
            record = extract_page_metadata(
                html=html,
                requested_url=url,
                response_url=response.url,
                config=config,
                depth=depth,
                parent_url=parent_url,
            )

            links = extract_links(html, response.url, config)
            if depth < config.max_depth:
                for link in links:
                    if link not in visited_urls:
                        queue.append((config, link, depth + 1, response.url))

            if record["content_hash"] in seen_content_hashes:
                LOGGER.debug("Skipping duplicate content for %s", url)
                time.sleep(config.request_delay_seconds)
                continue

            if record["document_id"] in seen_document_ids:
                LOGGER.debug("Skipping duplicate document id for %s", url)
                time.sleep(config.request_delay_seconds)
                continue

            seen_content_hashes.add(record["content_hash"])
            seen_document_ids.add(record["document_id"])
            sink.write(json.dumps(record, ensure_ascii=False) + "\n")
            records_written += 1
            example_record = example_record or record

            time.sleep(config.request_delay_seconds)

    summary = {
        "output_path": str(output_path),
        "records_written": records_written,
        "urls_seen": len(visited_urls),
        "source_count": len(configs),
    }

    if show_examples and example_record:
        preview = {
            key: value
            for key, value in example_record.items()
            if key != "html"
        }
        preview["html_preview"] = example_record["html"][:400]
        print(json.dumps(preview, ensure_ascii=False, indent=2))

    return summary


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Collect official Spring reference HTML pages.")
    parser.add_argument(
        "--config-dir",
        default="configs/app/sources",
        help="Directory containing source YAML files.",
    )
    parser.add_argument(
        "--output",
        default="data/raw/spring_docs_raw.jsonl",
        help="Destination JSONL file for collected raw HTML documents.",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=None,
        help="Maximum number of pages to write.",
    )
    parser.add_argument(
        "--source-id",
        action="append",
        default=None,
        help="Optional source_id filter. Can be passed multiple times.",
    )
    parser.add_argument(
        "--show-examples",
        action="store_true",
        help="Print a sample raw record preview after collection.",
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

    summary = collect_documents(
        config_dir=Path(args.config_dir),
        output_path=Path(args.output),
        limit=args.limit,
        source_ids=set(args.source_id) if args.source_id else None,
        show_examples=args.show_examples,
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
