# Synthetic Data

`data/synthetic/`은 synthetic query, gating 결과, memory export 같은 파일 기반 산출물을 둘 수 있는 작업 공간입니다. 현재 Query-Forge의 synthetic primary record는 PostgreSQL에 저장됩니다. Raw synthetic query는 `synthetic_queries_raw_a`부터 `synthetic_queries_raw_g`까지 전략별 table로 분리되고, 조회는 `synthetic_queries_raw_all` union view를 사용합니다.

이 디렉터리는 DB-backed 실행 결과를 외부 검수, diff, 백업, 별도 분석용 JSONL/CSV로 내보낼 때 사용하는 위치입니다. 따라서 파일이 비어 있더라도 synthetic 기능이 구현되지 않았다는 뜻은 아닙니다. 실제 generation/gating/memory 상태는 Admin Console API와 DB run record를 기준으로 확인해야 합니다.

## 저장할 때의 기준

Export 파일을 둘 때는 generation strategy, source/generation batch, gating preset, gating batch, memory experiment key, snapshot id가 파일명 또는 metadata에 드러나야 합니다. Ungated query와 gated accepted query, memory entry export는 섞지 않고 별도 파일로 둡니다. A/B/C/D/E core 전략과 F/G Korean-source 확장 전략도 같은 파일에서 무의미하게 병합하지 않습니다.

## 관련 단계

`generate-queries`는 synthetic raw table에 전략별로 쓰고, `gate-queries`는 accepted/rejected 결과와 stage score를 DB에 남깁니다. `build-memory`는 accepted query를 `memory_entries`로 materialize하고 snapshot metadata를 기록합니다. 기본 RAG rewrite 평가에서 이 memory는 최종 검색어가 아니라 few-shot example과 anchor hint로 사용됩니다.
