# Docs

`docs/`는 Query-Forge의 구현과 실험을 설명하는 장기 문서 디렉터리입니다. Root README가 프로젝트 전체 소개라면, 이 디렉터리는 API, architecture, UI, experiment, report를 더 세부적으로 해석하기 위한 기준점입니다.

## 구조

```text
api/            backend HTTP API와 Admin API 문서
architecture/   corpus storage, pipeline orchestration, domain integration 구조 문서
ui/             React Admin Console 사용 흐름과 UX 문서
experiments/    dataset 설계, monitoring, latest reports, rewrite 사례 분석
report/         특정 시점 실험 비교 보고서
```

## 읽는 순서

처음 구조를 파악할 때는 `architecture/overview.md`를 먼저 읽는 것이 좋습니다. Corpus 저장 구조가 필요하면 `architecture/corpus_storage.md`, backend가 Python pipeline을 실행하는 방식을 보려면 `architecture/pipeline_orchestration.md`, domain workspace 정책을 보려면 `architecture/domain_pipeline_integration_design.md`를 읽습니다. API 소비자는 `api/README.md`에서 controller별 문서로 들어가고, 화면 운영자는 `ui/admin_backoffice.md`와 `experiments/latest_report.md`를 함께 보면 됩니다.

## 문서 운영 원칙

Docs는 코드보다 느리게 변할 수 있으므로, endpoint나 실험 정책을 판단할 때는 실제 controller/service와 함께 확인해야 합니다. 다만 연구 방법론, dataset grounding 규칙, snapshot 비교 원칙, rewrite/anchor 해석 기준은 문서에 남겨 재현 가능한 실험 기록으로 사용합니다. 자동 생성 report 문서는 실행 산출물에 의해 갱신될 수 있으므로, 사람이 작성한 설계 문서와 구분해 관리합니다.
