# Processed Data

`data/processed/`는 raw HTML을 정제한 section 결과와, 그 이후 단계에서 파생되는 chunk·glossary 산출물을 저장하는 위치다.

## 주요 파일

- `spring_docs_sections.jsonl`
  - 실제 정제 결과의 기본 경로
- `spring_docs_sections_dry_run.jsonl`
  - 저장소에 포함된 dry-run section 샘플
- `chunks.jsonl`
  - retrieval과 synthetic generation의 기반이 되는 chunk 산출물
- `glossary_terms.jsonl`
  - 기술 용어 glossary 추출 결과
- `chunk_neighbors.sql`
  - chunk relation insert용 SQL 스크립트
- `chunking_visualization.md`
  - chunk 경계를 빠르게 검수하기 위한 Markdown 샘플

## section 레코드 구조

JSONL 한 줄이 section 1개를 의미하며, 대표 필드는 다음과 같다.

- `document_id`
- `section_id`
- `source_url`
- `product`
- `version_if_available`
- `title`
- `document_title`
- `section_title`
- `section_anchor`
- `section_path`
- `heading_hierarchy`
- `heading_level`
- `raw_text`
- `cleaned_text`
- `structural_blocks`
- `section_hash`
- `metadata`

## 생성 예시

```powershell
python .\pipeline\cli.py preprocess --input data/raw/spring_docs_raw_dry_run.jsonl --output data/processed/spring_docs_sections.jsonl
python .\pipeline\cli.py chunk-docs --input data/processed/spring_docs_sections.jsonl
python .\pipeline\cli.py glossary-docs --input data/processed/spring_docs_sections.jsonl
```

## 운영 메모

- boilerplate, breadcrumb, pagination 같은 비본문 요소를 제거한다.
- paragraph, list, table, code block, admonition 구조는 최대한 보존한다.
- `chunks.jsonl`에는 overlap, previous/next linkage, near/far relation 생성 결과가 포함된다.
- `glossary_terms.jsonl`은 이후 한국어 합성 질의 생성에서 영어 원형 유지 기준으로 재사용된다.
