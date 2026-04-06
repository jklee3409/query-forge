# Pipeline

`pipeline/`은 문서 수집부터 정제, chunking, glossary 추출, PostgreSQL 적재까지 담당하는 Python 오프라인 파이프라인 영역이다. 이후 단계에서는 synthetic query generation, gating, memory build, 평가 코드가 같은 디렉터리 아래로 확장된다.

## 디렉터리 구성

- `collectors/`
  - Spring 공식 문서 수집기
- `preprocess/`
  - 정제, chunking, glossary 추출
- `loaders/`
  - PostgreSQL import
- `generation/`
  - 합성 질의 생성 예정
- `gating/`
  - quality gating 예정
- `embeddings/`
  - 임베딩 생성 예정
- `datasets/`
  - 학습·평가 데이터셋 구성 예정
- `eval/`
  - retrieval, answer 평가 예정
- `common/`
  - 공통 유틸 모음
- `tests/`
  - import와 fixture 테스트

## 현재 동작하는 CLI 명령

- `collect-docs`
  - source YAML을 읽어 Spring 공식 문서를 raw HTML JSONL로 수집
- `preprocess`
  - raw HTML JSONL을 section 단위 JSONL로 정제
- `chunk-docs`
  - section JSONL을 retrieval용 chunk와 glossary로 변환
- `glossary-docs`
  - section JSONL에서 glossary만 다시 추출
- `import-corpus`
  - raw/section/chunk/glossary 산출물을 PostgreSQL로 upsert

## 실행 예시

```powershell
python .\pipeline\cli.py collect-docs --limit 10
python .\pipeline\cli.py preprocess --input data/raw/spring_docs_raw_dry_run.jsonl --output data/processed/spring_docs_sections.jsonl
python .\pipeline\cli.py chunk-docs --input data/processed/spring_docs_sections.jsonl
python .\pipeline\cli.py glossary-docs --input data/processed/spring_docs_sections.jsonl
python .\pipeline\cli.py import-corpus --raw-input data/raw/spring_docs_raw_dry_run.jsonl --sections-input data/processed/spring_docs_sections_dry_run.jsonl
```

## 의존성

- Python 3.12 이상
- `pip install -e .\pipeline`

## 참고

- 현재 수집, 정제, chunking 기본 설정은 `configs/app/`에 있다.
- 실험 설정과 프롬프트는 코드에 하드코딩하지 않고 `configs/` 아래에서 관리한다.
