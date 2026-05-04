# Pipeline

`pipeline/`은 Query Forge의 연구 실행 본체다. 문서 수집/정제부터 synthetic generation, quality gating, memory build, eval dataset 생성, retrieval/answer 평가까지의 단계가 이 디렉터리에서 실행된다.

## 디렉터리 구성

- `collectors/`
  - source preset 기반 문서 수집기
- `preprocess/`
  - 정제, chunking, glossary/anchor 후보 추출
- `loaders/`
  - PostgreSQL import
- `generation/`
  - 전략별 synthetic query 생성(A/B/C/D/E)
- `gating/`
  - rule/llm/utility/diversity quality gating
- `embeddings/`
  - 임베딩 관련 모듈 확장 영역
- `datasets/`
  - retrieval-aware eval dataset 생성/등록
- `eval/`
  - retrieval/answer 평가 + 리포트 생성
- `common/`
  - 실험 설정/프롬프트/LLM/리트리버 공통 유틸
- `tests/`
  - 런타임/LLM/import 관련 테스트

## 현재 동작하는 CLI 명령

- `collect-docs`
  - source YAML을 읽어 문서를 raw HTML JSONL로 수집
- `preprocess`
  - raw HTML JSONL을 section 단위 JSONL로 정제
- `chunk-docs`
  - section JSONL을 retrieval용 chunk와 glossary로 변환
- `glossary-docs`
  - section JSONL에서 glossary만 다시 추출
- `extract-anchor-candidates`
  - chunk JSONL에서 anchor 후보 JSONL을 추출(backend 앵커 재추출 API와 공용 로직)
- `import-corpus`
  - raw/section/chunk/glossary 산출물을 PostgreSQL로 upsert
- `generate-queries`
  - synthetic query 생성
- `gate-queries`
  - quality gating 실행
- `build-memory`
  - gating 결과 기반 memory 구축
- `build-eval-dataset`
  - retrieval-aware 평가셋 구축
- `import-eval-jsonl`
  - 외부 eval JSONL을 DB에 적재
- `eval-retrieval`, `eval-answer`
  - retrieval/answer 성능 평가 및 리포트 생성

## 실행 예시

```powershell
python .\pipeline\cli.py collect-docs --source-id spring-framework-reference
python .\pipeline\cli.py preprocess --input data/raw/spring_docs_raw.jsonl --output data/processed/spring_docs_sections.jsonl
python .\pipeline\cli.py chunk-docs --input data/processed/spring_docs_sections.jsonl
python .\pipeline\cli.py import-corpus --raw-input data/raw/spring_docs_raw.jsonl --sections-input data/processed/spring_docs_sections.jsonl
python .\pipeline\cli.py generate-queries --experiment gen_c
python .\pipeline\cli.py gate-queries --experiment full_gating
python .\pipeline\cli.py build-memory --experiment admin_eval_xxxxx
python .\pipeline\cli.py eval-retrieval --experiment admin_eval_xxxxx
```

## 의존성

- Python 3.12 이상
- `pip install -e .\pipeline`

## 참고

- 현재 수집, 정제, chunking 기본 설정은 `configs/app/`에 있다.
- 실험 설정과 프롬프트는 코드에 하드코딩하지 않고 `configs/` 아래에서 관리한다.
- 로컬 리트리버는 BM25/Dense/Hybrid 모드를 지원하며, 실험 설정으로 선택된다.
- selective rewrite 프롬프트는 `selective_rewrite_v2` 우선, `v1` fallback 순서로 로드된다.
