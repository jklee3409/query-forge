# Eval Data

`data/eval/`은 RAG 평가에 사용하는 retrieval-aware JSONL 입력 데이터셋을 보관하는 디렉터리입니다. 각 항목은 단순한 사용자 질의가 아니라 `expected_doc_ids`, `expected_chunk_ids`, `expected_answer_key_points`를 함께 가져야 합니다.

현재 주요 파일은 다음과 같습니다.

- `human_eval_short_user_test_40.jsonl`: 기존 Spring 영어 문서 도메인의 한국어 short-user 40문항
- `human_eval_short_user_test_80.jsonl`: 기존 Spring 영어 문서 도메인의 한국어 short-user 80문항
- `human_eval_short_user_test_80_en.jsonl`: 기존 Spring 영어 문서 도메인의 영어 short-user 80문항
- `spring_kr_anchor_translated_short_user_test_80.jsonl`: 기존 Spring KR short-user 80문항에서 영어 anchor를 한국어로 의도 번역한 별도 80문항
- `spring_kr_rewrite_challenge_80.jsonl`: Spring grounded short-user 80을 기반으로 영어/API anchor를 제거한 별도 KO rewrite challenge 80문항
- `postgresql_kr_rewrite_challenge_80.jsonl`: PostgreSQL grounded short-user 80을 기반으로 영어/SQL anchor를 제거한 별도 KO rewrite challenge 80문항
- `kubernetes_kr_rewrite_challenge_80.jsonl`: Kubernetes grounded short-user 80을 기반으로 영어/API anchor를 제거한 별도 KO rewrite challenge 80문항
- `python_kr_short_user_test_80_ko.jsonl`: 한국어 Python 문서 도메인의 한국어 short-user 80문항
- `python_kr_short_user_test_80_en.jsonl`: 한국어 Python 문서 도메인의 영어 short-user 80문항
- `postgresql_kr_short_user_test_80.jsonl`: PostgreSQL 도메인의 현재 영어 기술문서 청크에 grounded 된 한국어 short-user 80문항
- `postgresql_kr_anchor_translated_short_user_test_80.jsonl`: PostgreSQL KR short-user 80문항에서 영어 anchor를 한국어로 의도 번역한 별도 80문항
- `postgresql_en_short_user_test_80.jsonl`: PostgreSQL EN short-user 80 companion paired to `postgresql_kr_short_user_test_80.jsonl`
- `kubernetes_kr_short_user_test_80.jsonl`: Kubernetes 도메인의 현재 영어 기술문서 청크에 grounded 된 한국어 short-user 80문항
- `kubernetes_en_short_user_test_80.jsonl`: Kubernetes EN short-user 80 companion paired to `kubernetes_kr_short_user_test_80.jsonl`
- `spring_method_a_compressed_eval_80_ko.jsonl`: Method A compressed Spring stress eval 80문항
- `spring_method_b_compressed_eval_80_ko.jsonl`: Method B compressed Spring stress eval 80문항
- `spring_method_c_compressed_eval_80_ko.jsonl`: Method C compressed Spring stress eval 80문항
- `spring_method_d_compressed_eval_80_ko.jsonl`: Method D compressed Spring stress eval 80문항
- `spring_method_e_compressed_eval_80_en.jsonl`: Method E compressed Spring stress eval 80문항

Python KR 평가쌍은 같은 근거 chunk를 한국어/영어 질의로 짝지어 F/G 전략 비교에 사용하도록 구성되어 있습니다. PostgreSQL KR 데이터셋은 PostgreSQL 도메인의 active `corpus_chunks`만 expected chunk로 사용하며, DB의 `eval_dataset`, `eval_samples`, `eval_dataset_item`에도 같은 데이터셋으로 등록되어야 합니다.
