# Eval Data

`data/eval/`는 평가 입력(JSONL) 산출물을 저장하는 디렉터리입니다.

현재 주요 파일:
- `human_eval_dev.jsonl`
- `human_eval_test.jsonl`
- `human_eval_short_user_test_40.jsonl` (기준 40문항)
- `human_eval_short_user_test_80.jsonl` (코퍼스 chunk 기반 신규 생성 80문항)

short-user 평가는 실제 사용자에 가까운 짧은 질의를 대상으로, `expected_doc_ids` / `expected_chunk_ids`가 포함된 retrieval-aware 형식을 유지합니다.
