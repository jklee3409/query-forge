# Raw Data

`data/raw/`는 collector가 내려받은 원본 HTML JSONL을 저장하는 위치다. 이후 정제 단계는 이 디렉터리의 파일을 입력으로 사용한다.

## 주요 파일

- `spring_docs_raw.jsonl`
  - 실제 수집 결과의 기본 경로
- `spring_docs_raw_dry_run.jsonl`
  - 저장소에 포함된 dry-run 샘플 raw corpus
- `spring_docs_multi_source_dry_run.jsonl`
  - 여러 source를 섞어 둔 샘플 raw corpus
- `one_doc_check.jsonl`
  - 단일 문서 확인용 샘플

## 레코드 구조

JSONL 한 줄이 문서 1개를 의미하며, 대표 필드는 다음과 같다.

- `document_id`
- `source_id`
- `source_url`
- `canonical_url`
- `versioned_url`
- `product`
- `version_if_available`
- `title`
- `language_code`
- `content_hash`
- `fetched_at`
- `html`
- `metadata`

## 생성 예시

```powershell
python .\pipeline\collectors\spring_docs_collector.py --limit 10 --show-examples
python .\pipeline\cli.py collect-docs --limit 10 --show-examples
```

## 운영 메모

- raw HTML은 재현성과 diff 검토를 위해 원문에 가깝게 보존한다.
- 중복 제거에는 stable `document_id`와 `content_hash`를 함께 사용한다.
- bootstrap 스크립트는 실제 raw corpus가 없을 때 이 디렉터리의 dry-run 샘플을 사용한다.
