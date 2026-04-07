from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import psycopg
from psycopg.types.json import Jsonb


FRONT_MATTER_PATTERN = re.compile(r"^---\s*(.*?)\s*---\s*(.*)$", re.DOTALL)
META_LINE_PATTERN = re.compile(r"^([a-zA-Z0-9_]+)\s*:\s*(.*)$")


@dataclass(slots=True)
class PromptAsset:
    prompt_family: str
    prompt_name: str
    version: str
    content_path: str
    content_hash: str
    metadata: dict[str, Any]
    prompt_asset_id: str | None = None


def parse_prompt_asset(path: Path) -> PromptAsset:
    raw = path.read_text(encoding="utf-8")
    metadata: dict[str, Any] = {}
    body = raw
    matched = FRONT_MATTER_PATTERN.match(raw.strip())
    if matched:
        for line in matched.group(1).splitlines():
            stripped = line.strip()
            if not stripped:
                continue
            meta_matched = META_LINE_PATTERN.match(stripped)
            if not meta_matched:
                continue
            metadata[meta_matched.group(1)] = meta_matched.group(2)
        body = matched.group(2)

    family = str(metadata.get("family") or path.parent.name)
    prompt_name = str(metadata.get("id") or path.stem)
    version = str(metadata.get("version") or "v1")
    prompt_hash = hashlib.sha256(raw.encode("utf-8")).hexdigest()

    return PromptAsset(
        prompt_family=family,
        prompt_name=prompt_name,
        version=version,
        content_path=str(path.as_posix()),
        content_hash=prompt_hash,
        metadata={**metadata, "body_preview": body.strip()[:240]},
    )


def register_prompt_asset(
    connection: psycopg.Connection[Any],
    asset: PromptAsset,
) -> PromptAsset:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            INSERT INTO prompt_assets (
                prompt_family,
                prompt_name,
                version,
                content_path,
                content_hash,
                metadata
            ) VALUES (%s, %s, %s, %s, %s, %s)
            ON CONFLICT (prompt_family, prompt_name, version) DO UPDATE
            SET content_path = EXCLUDED.content_path,
                content_hash = EXCLUDED.content_hash,
                metadata = EXCLUDED.metadata,
                is_active = TRUE
            RETURNING prompt_asset_id
            """,
            (
                asset.prompt_family,
                asset.prompt_name,
                asset.version,
                asset.content_path,
                asset.content_hash,
                Jsonb(asset.metadata),
            ),
        )
        row = cursor.fetchone()
    prompt_asset_id = None
    if row:
        if isinstance(row, dict):
            prompt_asset_id = str(row["prompt_asset_id"])
        else:
            prompt_asset_id = str(row[0])
    return PromptAsset(
        prompt_family=asset.prompt_family,
        prompt_name=asset.prompt_name,
        version=asset.version,
        content_path=asset.content_path,
        content_hash=asset.content_hash,
        metadata=asset.metadata,
        prompt_asset_id=prompt_asset_id,
    )


def load_and_register_prompt(
    connection: psycopg.Connection[Any],
    prompt_path: Path,
) -> PromptAsset:
    return register_prompt_asset(connection, parse_prompt_asset(prompt_path))
