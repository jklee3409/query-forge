# Canonical Anchor Backfill Dry-Run Policy

이 문서는 Canonical Anchor Mapping 도입 이후 실제 backfill이나 mapping row 생성을 하지 않고, alias 후보와 canonical anchor 연결 가능성을 재현 가능하게 검토하기 위한 dry-run 기준을 정의한다.

## 목적

Canonical anchor dry-run의 목적은 기존 synthetic raw payload, memory metadata, glossary term, mapping table 상태를 읽어서 어떤 alias가 어떤 canonical anchor로 해석될 수 있는지 검토 가능한 report로 남기는 것이다. 이 단계는 승인 전 검토 절차이며, 데이터베이스 migration 적용, mapping row insert, 기존 raw text overwrite, memory 재생성, 공식 evaluation 실행을 포함하지 않는다.

## 고정 Version

모든 dry-run report는 다음 version pin을 그대로 기록해야 한다.

- `anchor_mapping_version`: `anchor-map-v1`
- `anchor_normalization_version`: `anchor-normalize-v1`
- `canonical_anchor_runtime_schema_version`: `canonical-anchor-runtime-v1`

Report에는 위 flat key와 함께 grouped payload인 `canonical_anchor_versions`도 기록한다. version 값은 실행자가 임의로 바꾸지 않고, 변경이 필요하면 별도 설계 세션에서 먼저 승인한다.

## 금지 사항

Dry-run 중에는 다음 작업을 하지 않는다.

- Flyway `V31__create_canonical_anchor_mapping.sql` 적용
- `canonical_anchor_mapping` row insert/update/delete
- synthetic raw A/B/C/D/E/F/G table merge
- 기존 `query_text`, `glossary_terms`, raw synthetic payload, memory query, dense query overwrite
- `memory_entries` 전체 재생성 또는 대량 metadata update
- LLM 기반 alias merge, synonym merge, 또는 자동 승인
- official retrieval/answer evaluation 및 metric 비교
- pipeline stage 순서 변경

## 입력 범위

Dry-run 입력은 실행자가 명시적으로 pin한 범위만 사용한다.

- repository commit SHA
- dry-run config file 또는 inline config hash
- source table set 또는 snapshot identity
- `generation_batch_id`, `gating_batch_id`, `memory_experiment_key` 등 사용한 source identity
- mapping table 존재 여부와 schema/migration 상태
- report 생성 시각

입력 범위가 비어 있거나 latest fallback에 의존하면 report를 공식 검토 자료로 쓰지 않는다.

## 후보 생성 원칙

후보 생성은 deterministic rule/query 기반이어야 한다.

- alias normalization은 `anchor-normalize-v1`만 사용한다.
- 기존 `pipeline/common/anchor_quality.py`의 `normalize_anchor_text` 의미는 바꾸지 않는다.
- approved + active mapping은 `mapped` 후보로 기록한다.
- canonical glossary term과 동일 normalized alias는 runtime self fallback 후보로만 기록하고 mapping self row를 만들지 않는다.
- pending mapping이 여러 개이면 `ambiguous` 후보로 기록하고 scoring 대상에서 제외한다.
- mapping이 없거나 table이 없으면 `miss` 또는 `unresolved`로 기록한다.
- alias language는 명시적으로 존재하는 값만 사용하고 runtime 추론으로 채우지 않는다.
- dry-run report 자체는 scoring에 사용하지 않지만, runtime 적용 시 scoring 가능 여부는 `would_be_used_for_scoring`로만 기록한다.

## Report 저장 위치

Dry-run report는 code나 DB 상태를 바꾸지 않는 파일 artifact로만 남긴다.

- JSON: `data/reports/canonical_anchor_backfill_dry_run/<run_id>.json`
- Markdown summary: `data/reports/canonical_anchor_backfill_dry_run/<run_id>.md`

Report file 생성은 허용되지만, report 생성 자체가 mapping 승인이나 backfill 승인을 의미하지 않는다.

## JSON Report Schema

예상 schema는 additive contract다. 구현 도구가 생기더라도 아래 key를 제거하지 않는다.

```json
{
  "report_type": "canonical_anchor_backfill_dry_run",
  "report_schema_version": "canonical-anchor-backfill-dry-run-v1",
  "run_id": "anchor-backfill-dry-run-YYYYMMDD-HHMMSS",
  "generated_at": "2026-05-19T00:00:00+09:00",
  "repository_commit": "<git-sha>",
  "config_hash": "<sha256>",
  "input_scope": {
    "source_tables": ["synthetic_queries_raw_all", "memory_entries"],
    "generation_batch_ids": [],
    "gating_batch_ids": [],
    "memory_experiment_keys": [],
    "snapshot_ids": []
  },
  "canonical_anchor_versions": {
    "anchor_mapping_version": "anchor-map-v1",
    "anchor_normalization_version": "anchor-normalize-v1",
    "canonical_anchor_runtime_schema_version": "canonical-anchor-runtime-v1"
  },
  "anchor_mapping_version": "anchor-map-v1",
  "anchor_normalization_version": "anchor-normalize-v1",
  "canonical_anchor_runtime_schema_version": "canonical-anchor-runtime-v1",
  "migration_state": {
    "v31_applied": false,
    "canonical_anchor_mapping_present": false
  },
  "summary": {
    "aliases_seen": 0,
    "mapped": 0,
    "self_fallback": 0,
    "ambiguous": 0,
    "miss": 0,
    "unresolved": 0,
    "invalid": 0
  },
  "candidates": [
    {
      "source_kind": "synthetic_raw",
      "source_identity": {
        "table": "synthetic_queries_raw_b",
        "query_id": "<id>",
        "generation_batch_id": "<id>"
      },
      "display_alias": "@Transactional(readOnly = true)",
      "normalized_alias": "@transactional(readonly = true)",
      "alias_language": "en",
      "resolution_status": "mapped",
      "would_be_used_for_scoring": true,
      "dry_run_only": true,
      "canonical_term_id": "<term-id>",
      "canonical_term": "@Transactional",
      "term_type": "annotation",
      "confidence": 1.0,
      "review_required": true,
      "review_reason": "dry_run_only"
    }
  ]
}
```

## Markdown Summary 구성

Markdown summary는 사람이 빠르게 검토할 수 있도록 다음 순서를 따른다.

- report metadata와 version pin
- input scope와 source snapshot identity
- migration state
- status별 summary count
- `ambiguous`, `miss`, `unresolved` 후보 table
- `mapped`, `self_fallback` 후보 중 manual review가 필요한 항목
- reviewer decision placeholder
- 금지 사항 체크 결과

## Manual Review 절차

Manual review는 report를 사람이 검토해 별도 승인 단계를 거치는 절차다.

1. `mapped`, `self_fallback`, `ambiguous`, `miss`, `unresolved` count를 먼저 확인한다.
2. `ambiguous` 후보는 canonical term 후보와 source evidence를 사람이 비교한다.
3. `miss`와 `unresolved` 후보는 새 alias mapping이 필요한지, glossary term 자체가 부족한지 분리한다.
4. 승인 후보는 canonical term이 실제 기술 개념인지 확인하고 `corpus_glossary_terms.term_id` 존재 여부를 확인한다.
5. 동일 normalized alias에 대해 approved + active mapping이 이미 있는지 확인한다.
6. self row는 만들지 않는다.
7. 승인된 mapping row insert는 dry-run 문서와 별도 승인 세션에서만 수행한다.

Review 결과는 최소한 다음 값을 남긴다.

- reviewer
- review timestamp
- accepted/rejected/deferred count
- rejected reason
- approved mapping insert 대상 여부
- follow-up issue 또는 세션 계획

## Reproducibility Discipline

Dry-run report는 재실행 시 비교 가능한 형태여야 한다.

- 입력 row 정렬은 deterministic order를 사용한다.
- report에는 source snapshot identity와 config hash를 기록한다.
- latest batch fallback을 사용하지 않는다.
- report 생성 후 source data가 바뀌면 같은 report로 metric comparison을 하지 않는다.
- 공식 evaluation은 dry-run 검토와 mapping 승인 이후 별도 승인된 세션에서만 실행한다.

## Backfill 승인 기준

Dry-run 이후에도 backfill은 자동으로 진행하지 않는다. 향후 backfill이 필요하면 다음 조건을 모두 만족해야 한다.

- `V31` 적용 승인과 적용 대상 DB가 명시됨
- mapping row insert 대상이 manual review로 확정됨
- write 대상이 metadata 또는 additive table로만 제한됨
- 기존 raw text와 memory query overwrite가 없음
- snapshot/source identity가 고정됨
- official evaluation 실행 여부와 범위가 별도 승인됨

## 현재 세션 적용 범위

세션 12에서는 이 정책 문서만 추가한다. dry-run 실행 도구, DB catalog inspection, migration apply, mapping insert, data report 생성, official evaluation은 수행하지 않는다.
