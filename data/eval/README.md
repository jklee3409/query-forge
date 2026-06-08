# Eval Data

`data/eval/`은 RAG 평가에 사용하는 retrieval-aware JSONL 데이터셋을 보관합니다. Query-Forge의 평가 데이터는 단순 question-answer 목록이 아닙니다. 각 sample은 `target_method`, `query_language`, `user_query_ko` 또는 `user_query_en`, `expected_doc_ids`, `expected_chunk_ids`, `expected_answer_key_points`를 포함해야 하며, retrieval/rewrite/anchor/answer 평가가 같은 evidence를 기준으로 비교될 수 있어야 합니다.

## 현재 데이터셋 계열

| 계열 | 파일 |
| --- | --- |
| Spring short-user | `human_eval_short_user_test_40.jsonl`, `human_eval_short_user_test_80.jsonl`, `human_eval_short_user_test_80_en.jsonl` |
| Spring anchor/rewrite challenge | `spring_kr_anchor_translated_short_user_test_80.jsonl`, `spring_kr_rewrite_challenge_30.jsonl`, `spring_kr_rewrite_challenge_80.jsonl`, `spring_kr_rewrite_probe_c_9.jsonl`, `spring_en_rewrite_challenge_80.jsonl` |
| Spring method-compressed stress | `spring_method_a_compressed_eval_80_ko.jsonl`, `spring_method_b_compressed_eval_80_ko.jsonl`, `spring_method_c_compressed_eval_80_ko.jsonl`, `spring_method_d_compressed_eval_80_ko.jsonl`, `spring_method_e_compressed_eval_80_en.jsonl` |
| Python KR source | `python_kr_short_user_test_80_ko.jsonl`, `python_kr_short_user_test_80_en.jsonl` |
| PostgreSQL | `postgresql_kr_short_user_test_80.jsonl`, `postgresql_en_short_user_test_80.jsonl`, `postgresql_kr_anchor_translated_short_user_test_80.jsonl`, `postgresql_kr_rewrite_challenge_80.jsonl`, `postgresql_en_rewrite_challenge_80.jsonl` |
| Kubernetes | `kubernetes_kr_short_user_test_80.jsonl`, `kubernetes_en_short_user_test_80.jsonl`, `kubernetes_kr_rewrite_challenge_80.jsonl`, `kubernetes_en_rewrite_challenge_80.jsonl` |

## Schema 원칙

각 row는 answerable해야 하고, `expected_doc_ids`와 `expected_chunk_ids`는 현재 또는 대상 corpus snapshot에서 실제 근거를 가리켜야 합니다. Korean query dataset은 `query_language=ko`와 `user_query_ko`를 사용하고, English query dataset은 `query_language=en`과 `user_query_en`을 사용합니다. A/B/C/D/G는 Korean 또는 code-mixed query 조건이고, E/F는 English query 조건입니다.

Method-compressed Spring stress dataset은 A/B/C/D/E별 accepted synthetic query를 압축해 만든 비교용 dataset입니다. Python KR KO/EN pair는 같은 근거 chunk에서 G/F를 비교할 수 있게 분리되어 있습니다. Rewrite challenge 계열은 영어/API anchor가 제거되거나 번역된 사용자 질의 표면을 사용해 selective rewrite와 anchor recovery를 강하게 검증합니다.

## DB 등록과 해석

JSONL artifact와 runtime DB dataset은 별개일 수 있습니다. Admin RAG에서 사용하려면 파일이 `eval_dataset`, `eval_samples`, `eval_dataset_item`에 등록되어 있어야 하고, dataset key/version과 active item 순서가 기대와 맞아야 합니다. 결과를 비교할 때는 dataset 파일명만 보지 말고 DB dataset id, source domain, query language, target method, explicit gating snapshot을 함께 확인해야 합니다.
