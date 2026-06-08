# Reports

`data/reports/`는 실험 해석에 필요한 평가 산출물을 저장합니다. 이 디렉터리는 RAG retrieval/answer 실행 결과, latency summary, dataset audit, rewrite case report, calibration/probe 결과가 누적되는 곳입니다. 파일 수가 많고 일부 CSV는 매우 크므로, 전체 파일을 열기보다 run id, prefix, 날짜, dataset key로 좁혀 확인하는 방식이 안전합니다.

## 주요 파일 패턴

| 패턴 | 의미 |
| --- | --- |
| `retrieval_summary_*`, `retrieval_by_category_*`, `retrieval_detail_*` | retrieval metric과 sample-level retrieval detail입니다. Recall@5, Hit@5, MRR@10, nDCG@10 해석에 사용합니다. |
| `answer_summary_*`, `answer_detail_*` | answer-level correctness, grounding, hallucination, faithfulness, context precision/recall 및 detail row입니다. |
| `latency_*` | retrieval, rewrite, answer evaluation latency를 비교하기 위한 성능 산출물입니다. |
| `rewrite_*`, `best_rewrite_cases`, `bad_rewrite_cases` 관련 산출물 | selective rewrite의 개선/악화 사례와 candidate 분석에 사용합니다. |
| `*_audit_*`, `*_report_*` | eval dataset 생성, grounding 검증, calibration, probe 결과를 기록합니다. |
| `python_kr_eval_dataset_80_audit_*.json`, `spring_method_compressed_eval_80_audit_*.json` | 특정 dataset family의 생성/검증 근거입니다. |

## 운영 원칙

공식 비교를 해석할 때는 quality metric만 보지 않습니다. 같은 dataset, 같은 explicit snapshot, 같은 retriever config, 같은 rewrite profile에서 latency와 answer grounding을 함께 봐야 합니다. AGENTS 규칙상 품질 개선을 주장하려면 latency/cost context가 함께 있어야 하며, dataset이나 snapshot이 다른 run을 동일 조건처럼 비교하면 안 됩니다.

`docs/experiments/latest_report.md`, `latest_answer_report.md`, `best_rewrite_cases.md`, `bad_rewrite_cases.md`는 이 디렉터리의 실행 산출물을 사람이 읽는 문서 형태로 요약하는 위치입니다. 이 Markdown들은 pipeline 또는 분석 스크립트 실행에 의해 자동 갱신될 수 있으므로, 수동 편집 여부를 구분해 관리해야 합니다.
