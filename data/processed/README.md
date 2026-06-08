# Processed Data

`data/processed/`는 raw HTML을 정제한 section과, retrieval 및 generation에 직접 쓰이는 chunk/glossary/relation artifact를 저장합니다. 이 디렉터리의 파일은 `import-corpus` 이전에 corpus 구조를 검수하는 중간 산출물입니다.

## 대표 artifact

| 파일 패턴 | 역할 |
| --- | --- |
| `*_docs_sections.jsonl` | 문서 HTML을 section 단위로 정제한 결과입니다. heading hierarchy, cleaned text, structural block metadata를 포함합니다. |
| `*_chunks.jsonl`, `chunks.jsonl` | retrieval unit과 synthetic generation target이 되는 chunk입니다. overlap, previous/next linkage, metadata를 포함합니다. |
| `*_glossary_terms.jsonl`, `glossary_terms.jsonl` | technical anchor/glossary 후보입니다. synthetic query generation과 rewrite anchor hint의 근거가 됩니다. |
| `*_chunk_neighbors.sql`, `chunk_neighbors.sql` | chunk near/far relation insert용 SQL artifact입니다. |
| `*_chunking_visualization.md` | chunk boundary를 사람이 빠르게 검수하기 위한 Markdown preview입니다. |

현재 Spring, Python Korean docs, Kubernetes artifact가 함께 존재합니다. `python_ko_glossary_terms.jsonl`처럼 source 특성이나 추출 조건에 따라 비어 있는 파일이 있을 수 있으며, 이 경우 downstream DB 상태와 source language 정책을 함께 확인해야 합니다.

## Section 구조

Section JSONL의 대표 필드는 `document_id`, `section_id`, `source_url`, `product`, `version_if_available`, `title`, `document_title`, `section_title`, `section_anchor`, `section_path`, `heading_hierarchy`, `heading_level`, `raw_text`, `cleaned_text`, `structural_blocks`, `section_hash`, `metadata`입니다. Boilerplate, breadcrumb, pagination 같은 비본문 요소는 제거하지만, paragraph/list/table/code/admonition 구조는 최대한 보존합니다.

## 생성 예시

```powershell
python .\pipeline\cli.py preprocess --input data/raw/spring_docs_raw.jsonl --output data/processed/spring_docs_sections.jsonl
python .\pipeline\cli.py chunk-docs --input data/processed/spring_docs_sections.jsonl --output-chunks data/processed/chunks.jsonl
python .\pipeline\cli.py glossary-docs --input data/processed/spring_docs_sections.jsonl --output-glossary data/processed/glossary_terms.jsonl
```

Processed artifact를 수동으로 해석할 때는 파일명만으로 active DB corpus를 단정하지 않아야 합니다. 실제 RAG evaluation은 import된 `corpus_documents`, `corpus_chunks`, `corpus_glossary_terms`, `chunk_embeddings` 상태에 의존합니다.
