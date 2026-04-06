# Infra SQL

`infra/sql/`은 Flyway migration 외에 운영 중 참고할 inspection query, bulk import 메모, 임시 분석 SQL을 정리하는 공간이다.

## 용도

- ad hoc inspection query 보관
- bulk import 또는 운영 점검용 SQL 초안 보관
- migration 문제 분석 메모 정리

## 현재 기준

- 실제 스키마 변경의 source of truth는 `backend/src/main/resources/db/migration/`이다.
- 현재는 대부분의 스키마 생성과 변경을 Flyway가 담당하며, 이 디렉터리는 운영 보조 메모 성격이 더 크다.
