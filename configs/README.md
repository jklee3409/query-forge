# Configs

`configs/`는 Query Forge 실행값을 코드 외부에서 관리하는 핵심 설정 디렉터리다. 소스 수집 범위, 청킹 규칙, 실험 프리셋, 프롬프트 버전이 이 디렉터리에 모여 있다.

## 디렉터리 구성

```text
app/          애플리케이션 기본 설정과 source/chunking 설정
experiments/  실험 preset
prompts/      LLM 프롬프트 파일
```

## 하위 구조 설명

### `app/`

- `application.yml`
  - 데이터/프롬프트/실험 루트 경로 정의
- `application-docker.yml`
  - Docker 프로필용 datasource 설정
- `chunking.yml`
  - target token 길이, overlap, relation 범위 등 chunking 규칙
- `sources/*.yaml`
  - 수집 source 정의(Spring + 추가 도메인 preset 포함)

### `experiments/`

고정 프리셋(`ungated`, `rule_only`, `full_gating`, `rewrite_always` 등)이 저장된다. 관리자 실행 배치(`admin_gen_*`, `admin_gate_*`, `admin_eval_*`)는 런타임 산출물이므로 Git 추적 대상에서 제외하고, 재현이 필요한 공식 실험 조건만 별도 프리셋으로 정리한다.

### `prompts/`

- `summary_extraction/`
- `query_generation/` (`gen_a_v1`~`gen_g_v1`)
- `self_eval/`
- `rewrite/` (`selective_rewrite_v1`, `selective_rewrite_v2`)

프롬프트는 파이프라인 런타임에서 직접 로드되며, 리라이트는 `selective_rewrite_v2` 우선/`v1` fallback 규칙으로 동작한다.

## 운영 원칙

- 프롬프트와 실험 설정은 코드에 하드코딩하지 않는다.
- 로컬 개발용 비밀 설정은 `*-local.yml`, `*.local.yml` 규칙으로 분리하고 Git에 올리지 않는다.
- source 정의를 바꾸면 수집 결과와 import 결과가 달라질 수 있으므로 run history와 함께 검토한다.
