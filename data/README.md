# Data

`data/`는 수집 결과, 정제 산출물, 향후 생성 데이터셋과 리포트를 저장하는 작업 디렉터리다. 대부분의 산출물은 Git에 직접 포함하지 않고 `.gitkeep` 또는 최소 샘플만 유지한다.

## 디렉터리 구성

```text
raw/        수집 직후의 HTML JSONL
processed/  section, chunk, glossary 등 전처리 산출물
synthetic/  합성 질의와 메모리 산출물 예정 위치
eval/       평가용 샘플과 결과 예정 위치
reports/    자동 생성 리포트 예정 위치
logs/       실행 로그가 생성되는 위치
```

## 사용 흐름

1. `raw/`에 수집 결과 저장
2. `processed/`에 정제·chunking·glossary 결과 저장
3. import 후에는 PostgreSQL이 주 조회 원본이 되지만, 재현성과 diff 검토를 위해 파일 산출물도 유지

## 주의 사항

- 대용량 산출물은 기본적으로 Git에 포함하지 않는다.
- 현재 저장소에는 빠른 검증용 dry-run 샘플이 일부 포함되어 있다.
- `scripts/bootstrap-local.ps1`는 실제 산출물이 없을 때 dry-run 샘플을 자동으로 사용한다.
