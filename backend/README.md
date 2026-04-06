# Backend

`backend/`는 Spring Boot 기반의 관리자 API와 백오피스 UI를 담는 애플리케이션 모듈이다. 현재 단계에서는 corpus 조회·검수 API, pipeline orchestration API, Thymeleaf 기반 관리자 화면, Flyway migration이 이 디렉터리에 모여 있다.

## 주요 역할

- `corpus_*` 테이블을 조회·수정하는 관리자 API 제공
- Python 파이프라인을 비동기로 실행하는 orchestration 계층 제공
- `/admin` 백오피스 UI 렌더링
- Flyway로 PostgreSQL 스키마 관리
- Testcontainers 기반 통합 테스트 수행

## 디렉터리 구성

```text
src/main/java/io/queryforge/backend/admin/
  corpus/    corpus 관리 API, 서비스, 저장소
  pipeline/  파이프라인 실행 API, subprocess runner
  ui/        관리자 화면 controller

src/main/resources/
  db/migration/   Flyway migration
  templates/      Thymeleaf 템플릿
  static/admin/   관리자 전용 CSS, JS

src/test/
  java/       통합 테스트
  resources/  fixture SQL, 테스트 설정
```

## 실행

```powershell
.\gradlew.bat -p . bootRun
```

루트에서 실행한다면 다음 명령도 가능하다.

```powershell
.\backend\gradlew.bat -p .\backend bootRun
```

## 테스트

```powershell
.\gradlew.bat -p . test
```

Testcontainers 기반 테스트가 포함되어 있으므로 Docker가 켜져 있어야 한다.

## 참고 사항

- 관리자 화면의 파이프라인 실행은 내부 queue가 아니라 `ProcessBuilder` 기반 subprocess 호출로 구현되어 있다.
- Flyway migration이 애플리케이션 시작 시 자동 실행된다.
- 현재 단계는 백오피스와 corpus 관리에 집중되어 있으며, 채팅/RAG API는 아직 구현되지 않았다.
