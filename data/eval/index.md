# index.md

## Directory Overview
`data/eval/`은 평가 데이터셋 JSONL 파일을 보관합니다. 이 프로젝트의 평가 데이터는 단순 QA가 아니라 문서와 chunk grounding을 포함하는 retrieval-aware 데이터셋입니다.

## Structure
- `README.md`: 평가 데이터셋 파일 개요
- `human_eval_short_user_test_40.jsonl`: Spring 도메인 한글 short-user 40문항
- `human_eval_short_user_test_80.jsonl`: Spring 도메인 한글 short-user 80문항
- `human_eval_short_user_test_80_en.jsonl`: Spring 도메인 영어 short-user 80문항
- `spring_kr_anchor_translated_short_user_test_80.jsonl`: Spring 도메인 한글 anchor-translated short-user 80문항
- `python_kr_short_user_test_80_ko.jsonl`: Python KR 도메인 한글 short-user 80문항
- `python_kr_short_user_test_80_en.jsonl`: Python KR 도메인 영어 short-user 80문항
- `postgresql_kr_short_user_test_80.jsonl`: PostgreSQL 도메인 영어 기술문서 청크 기반 한국어 short-user 80문항
- `postgresql_kr_anchor_translated_short_user_test_80.jsonl`: PostgreSQL 도메인 한글 anchor-translated short-user 80문항
- `postgresql_en_short_user_test_80.jsonl`: PostgreSQL English companion short-user 80 items paired to `postgresql_kr_short_user_test_80.jsonl`
- `kubernetes_kr_short_user_test_80.jsonl`: Kubernetes 도메인 영어 기술문서 청크 기반 한국어 anchor-translated short-user 80문항
- `kubernetes_en_short_user_test_80.jsonl`: Kubernetes English companion short-user 80 items paired to `kubernetes_kr_short_user_test_80.jsonl`
- `spring_method_a_compressed_eval_80_ko.jsonl`: Method A compressed Spring stress eval 80문항
- `spring_method_b_compressed_eval_80_ko.jsonl`: Method B compressed Spring stress eval 80문항
- `spring_method_c_compressed_eval_80_ko.jsonl`: Method C compressed Spring stress eval 80문항
- `spring_method_d_compressed_eval_80_ko.jsonl`: Method D compressed Spring stress eval 80문항
- `spring_method_e_compressed_eval_80_en.jsonl`: Method E compressed Spring stress eval 80문항

## Responsibilities
- RAG retrieval/answer 평가 입력을 JSONL artifact로 보존합니다.
- 각 sample의 `expected_doc_ids`와 `expected_chunk_ids`를 유지해 retrieval 평가를 재현 가능하게 합니다.
- 언어별 paired dataset을 분리 저장해 같은 근거 문서에서 한글/영어 사용자 질의 성능을 비교할 수 있게 합니다.

## Notes
- 새 평가 데이터셋을 추가할 때는 DB `eval_dataset`, `eval_samples`, `eval_dataset_item` 등록 방식과 JSONL 구조가 일치해야 합니다.
