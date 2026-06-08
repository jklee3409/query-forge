# Infra SQL

`infra/sql/`은 Flyway migration이 아닌 운영 보조 SQL을 둘 수 있는 디렉터리입니다. 이 위치의 SQL은 schema source of truth가 아니며, inspection query, one-off 분석, bulk import 검토, 장애 원인 파악 메모처럼 실행 전 사람이 확인해야 하는 자료로 취급합니다.

## 사용 범위

이 디렉터리에 둘 수 있는 SQL은 특정 domain, source, dataset, generation batch, gating batch, RAG run을 좁혀 확인하는 query입니다. 전체 DB dump나 무제한 table scan을 편의상 저장하는 용도로 쓰지 않습니다. Schema 변경이 필요하면 `backend/src/main/resources/db/migration/`에 Flyway migration을 추가해야 합니다.

## 운영 원칙

SQL 파일을 추가할 때는 목적, 입력 parameter, 예상 row scope, write 여부를 파일 상단에 명시하는 것이 좋습니다. Write query는 transaction, rollback 조건, 대상 table, 대상 id를 명확히 기록해야 하며, 실험 재현성에 영향을 줄 수 있는 데이터 수정은 root `progress.md`에도 남겨야 합니다.
