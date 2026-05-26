# index.md

## Directory Overview
`data/`는 수집, 전처리, 합성 질의, 평가 입력, 평가 결과, 리포트 산출물을 보관하는 작업 데이터 디렉터리입니다.

## Structure
- `README.md`: 데이터 디렉터리 운영 원칙
- `raw/`: 수집 원본 데이터
- `processed/`: 전처리된 section/chunk/glossary 산출물
- `synthetic/`: 합성 질의와 memory 관련 산출물
- `eval/`: retrieval-aware 평가 데이터셋 JSONL, including Spring method-compressed stress datasets
- `reports/`: 평가 요약, audit, retrieval/answer 리포트
- `artifacts/`: 실행 중 생성되는 artifact
- `logs/`: 실행 로그
- `tmp/`: 임시 실행 산출물

## Responsibilities
- pipeline 단계별 데이터 저장 위치를 분리합니다.
- 평가 데이터셋과 실험 리포트를 재현 가능한 artifact로 보존합니다.
- 대용량 실행 산출물과 기준 데이터셋 산출물을 구분해 관리합니다.

## Key Flows
- `collect-docs` 결과는 `raw/`에 저장합니다.
- `preprocess`, `chunk-docs`, `glossary-docs` 결과는 `processed/`에 저장합니다.
- generation/gating/memory 관련 산출물은 `synthetic/`에 저장합니다.
- retrieval/answer 평가 입력은 `eval/`, 요약 리포트와 audit는 `reports/`에 저장합니다.
- `eval/`에는 기존 Spring 도메인 KO/EN short-user 데이터셋, Python KR 도메인 KO/EN paired short-user 데이터셋, A/B/C/D/E method-compressed Spring stress 데이터셋이 포함됩니다.
- The runtime DB now also contains the PostgreSQL English technical-document corpus plus completed A/C synthetic generation and BM25-only full-gating batches; `data/` remains the artifact/report directory rather than the DB source of truth.

## Notes
- 구조나 책임이 바뀌면 이 파일을 함께 갱신합니다.
