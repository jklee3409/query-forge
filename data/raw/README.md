# Raw Data

`data/raw/`는 collector가 내려받은 원본 HTML JSONL을 저장하는 디렉터리입니다. 이 파일들은 `preprocess` 단계의 입력이며, corpus 재현성과 source 변경 diff를 확인하기 위한 가장 앞단의 artifact입니다.

현재 저장소에는 Spring, Python Korean docs, Kubernetes 계열 raw artifact가 존재합니다. 파일 크기가 큰 corpus도 포함될 수 있으므로, 로컬 확인 시에는 전체 파일을 열기보다 `Select-Object -First`, source filter, line count처럼 범위를 좁혀 확인하는 것이 안전합니다.

## 대표 파일

| 파일 | 역할 |
| --- | --- |
| `spring_docs_raw.jsonl` | Spring reference 계열 수집 결과의 기본 raw artifact입니다. |
| `python_ko_docs_raw.jsonl` | `docs-python-org-ko-3-14` 수집 결과입니다. F/G 한국어 원문 실험의 source artifact로 쓰입니다. |
| `kubernetes_docs_raw.jsonl` | Kubernetes docs 수집 결과입니다. Kubernetes eval dataset과 domain 실험의 근거 corpus입니다. |
| `kubernetes_docs_probe.jsonl` | Kubernetes 수집/정제 흐름을 좁혀 확인하기 위한 probe artifact입니다. |

## 레코드 구조

JSONL 한 줄은 문서 1개를 의미합니다. 대표 필드는 `document_id`, `source_id`, `source_url`, `canonical_url`, `versioned_url`, `product`, `version_if_available`, `title`, `language_code`, `content_hash`, `fetched_at`, `html`, `metadata`입니다. Collector는 원문 HTML을 가능한 한 보존하고, 중복 제거와 변경 추적은 stable id와 content hash를 함께 사용합니다.

## 생성 예시

```powershell
python .\pipeline\cli.py collect-docs --source-id spring-framework-reference --limit 10 --show-examples
python .\pipeline\cli.py collect-docs --source-id docs-python-org-ko-3-14 --limit 10
```

Raw artifact를 갱신하면 downstream section, chunk, glossary, import 결과도 달라질 수 있습니다. 공식 비교를 위해서는 갱신 전후 source preset, fetch 시점, target domain, import run을 함께 기록해야 합니다.
