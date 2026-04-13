# index.md

## Directory Overview
로컬/운영 보조 인프라 지침을 정리한 디렉토리입니다.

---

## Structure
- `README.md`: 인프라 디렉토리 개요
- `docker/README.md`: Docker 기반 실행 참고 문서
- `docker/postgres/README.md`: PostgreSQL 컨테이너 운영 메모
- `sql/README.md`: SQL 보조 작업/점검 메모
- `index.md`: 인프라 디렉토리 역할과 구조 문서
- `progress.md`: 인프라 관련 변경 이력 요약

---

## Responsibilities
- 로컬 개발 인프라 실행 가이드 제공
- DB 컨테이너 및 점검 SQL 문서 관리
- 운영 보조 절차의 기준 문서 제공

---

## Key Flows
- 루트 `docker-compose.yml`로 서비스 기동
- PostgreSQL 관련 상세 운영은 `docker/postgres/README.md` 참조
- 수동 점검/보조 쿼리는 `sql/` 문서를 기준으로 수행

---

## Notes
- Update this file when structure or responsibilities change
