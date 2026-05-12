# Eval Data

`data/eval/`은 RAG 평가에 사용하는 retrieval-aware JSONL 입력 데이터를 저장하는 디렉터리입니다. 각 항목은 사용자 질의뿐 아니라 `expected_doc_ids`, `expected_chunk_ids`, `expected_answer_key_points`를 함께 가져야 합니다.

현재 주요 파일은 다음과 같습니다.

- `human_eval_short_user_test_40.jsonl`: 기존 Spring 영어 문서 도메인의 한글 short-user 40문항
- `human_eval_short_user_test_80.jsonl`: 기존 Spring 영어 문서 도메인의 한글 short-user 80문항
- `human_eval_short_user_test_80_en.jsonl`: 기존 Spring 영어 문서 도메인의 영어 short-user 80문항
- `python_kr_short_user_test_80_ko.jsonl`: 한글 Python 문서 도메인의 한글 short-user 80문항
- `python_kr_short_user_test_80_en.jsonl`: 한글 Python 문서 도메인의 영어 short-user 80문항

Python KR 평가셋은 같은 근거 chunk를 한글/영어 질의로 짝지어 F/G 전략 비교에 사용하도록 구성되어 있습니다.
