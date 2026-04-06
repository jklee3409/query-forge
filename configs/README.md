# Configs

`configs/`는 코드에 하드코딩하지 않아야 하는 설정을 모아 두는 디렉터리다. 앱 실행 설정, 문서 source 정의, 실험 설정, 프롬프트 버전을 여기서 관리한다.

## 디렉터리 구성

```text
app/          애플리케이션 기본 설정과 source/chunking 설정
experiments/  실험 preset
prompts/      LLM 프롬프트 파일
```

## 하위 구조 설명

### `app/`

- `application.yml`
  - 데이터 루트, prompt 루트 같은 공통 경로 정의
- `application-docker.yml`
  - Docker 프로필용 datasource 설정
- `chunking.yml`
  - target token 길이, overlap, relation 범위 등 chunking 규칙
- `sources/*.yaml`
  - Spring Boot, Spring Framework, Spring Security 등 수집 source 정의

### `experiments/`

향후 generation, gating, evaluation 실험을 재현하기 위한 설정 파일 자리다. 현재는 scaffold 성격의 예시 파일이 들어 있다.

### `prompts/`

- `summary_extraction/`
- `query_generation/`
- `self_eval/`
- `rewrite/`

현재 단계에서는 파일 구조와 버전 관리 기준만 준비되어 있고, 실제 활용은 이후 단계에서 확장된다.

## 운영 원칙

- 프롬프트와 실험 설정은 코드에 하드코딩하지 않는다.
- 로컬 개발용 비밀 설정은 `*-local.yml`, `*.local.yml` 규칙으로 분리하고 Git에 올리지 않는다.
- source 정의를 바꾸면 수집 결과와 import 결과가 달라질 수 있으므로 run history와 함께 검토하는 것이 좋다.
