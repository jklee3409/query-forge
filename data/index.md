# index.md

## Directory Overview
수집/전처리/합성/평가 과정의 데이터 산출물과 보고서를 저장하는 작업 데이터 디렉토리입니다.

---

## Structure
- `README.md`: 데이터 디렉토리 운영 원칙
- `raw/`: 수집 원본 데이터(README, `.gitkeep`)
- `processed/`: 전처리/청킹/용어 추출 결과(README, `.gitkeep`)
- `synthetic/`: 합성 질의 및 메모리 관련 산출물(README, `.gitkeep`)
- `eval/`: 평가 입력/결과 산출물(README, `.gitkeep`)
- `reports/`: 리포트 출력 경로(README, `.gitkeep`)
- `artifacts/`: 실행 결과 아티팩트(런타임 생성)
- `logs/`: 실행 로그(런타임 생성)
- `tmp/`: 임시 실행 산출물(런타임 생성)

---

## Responsibilities
- 파이프라인 단계별 데이터 저장 경로를 분리 관리
- 재실행/비교를 위한 중간 산출물 보관 위치 제공
- 보고서/로그/임시 파일의 런타임 저장소 역할 수행

---

## Key Flows
- `collect-docs` 결과를 `raw/`에 저장
- `preprocess`, `chunk-docs`, `glossary-docs` 결과를 `processed/`에 저장
- generation/gating/memory 관련 산출물을 `synthetic/`에 저장
- retrieval/answer 평가 산출물을 `eval/`, 요약 리포트를 `reports/`에 저장
- 실행 로그와 중간 실행 파일은 `logs/`, `tmp/`로 분리

---

## Notes
- Update this file when structure or responsibilities change
