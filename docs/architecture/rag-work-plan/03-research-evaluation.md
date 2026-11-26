# 03. 공식 연구 평가 교정과 검색 결과 재사용

공식 비교의 실행 조건과 결과 라벨을 일치시킨다. Java가 검색·재작성을 실행하고, Python은 동일 실행 결과를 사용해 정답 기반 검색 지표와 추출형 답변 지표를 계산한다. 이 문서는 승인된 연구 정책 변경의 범위를 정의한다.

## 핵심 코드

| 구간 | 진입점 |
| --- | --- |
| 관리자 실험 등록과 작업 결과 | [RagPage.jsx](../../../frontend/src/pages/RagPage.jsx), [RagTestService.java](../../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/run/service/RagTestService.java), [LlmJobResultService.java](../../../backend/src/main/java/io/queryforge/backend/admin/console/jobs/result/service/LlmJobResultService.java) |
| Java 무기록 검색과 설정 | [RagRetrievalEvalService.java](../../../backend/src/main/java/io/queryforge/backend/rag/eval/retrieval/service/RagRetrievalEvalService.java), [EvalRuntimeConfigService.java](../../../backend/src/main/java/io/queryforge/backend/rag/eval/retrieval/service/EvalRuntimeConfigService.java) |
| Python runner와 지표 | [java_retrieval_client.py](../../../pipeline/eval/java_retrieval_client.py), [retrieval_eval.py](../../../pipeline/eval/retrieval_eval.py), [answer_eval.py](../../../pipeline/eval/answer_eval.py), [runtime.py](../../../pipeline/eval/runtime.py), [retrieval_eval_compare.py](../../../pipeline/eval/retrieval_eval_compare.py) |
| Anchor 진단과 후처리 | [RagRewriteAnchorEvalService.java](../../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/anchor/service/RagRewriteAnchorEvalService.java), [Anchor 실험 규칙](../../../.codex/AGENTS.md#366-anchor-methodology) |

## A. 공식 비교의 행별 모드 전달

1. `RagTestService`가 만든 공식 `rewrite_effect` 설정을 Python 테스트에 넣는다. 현재 `_java_forced_mode_for_eval_mode`가 전체 실험 모드를 우선해 `raw_only` 행도 `selective_rewrite`로 보내는 케이스를 재현한다.
2. 공식 비교의 각 행이 자신의 모드를 전달하도록 수정한다. 단일 모드 실행과 비교 runner의 명시적 override는 별도 경로와 테스트로 보존한다. 요청 모드, 실제 실행 모드, 비교 행의 표시명을 결과에 기록한다.
3. 응답 모드에서 오류가 발견되면 잘못된 라벨로 집계하지 않는다. 원래 모드와 평가 정책을 확인할 수 없는 과거 이력은 원본 상태로 보존한다.

## B. 공식 정책, 스냅샷과 Anchor

1. [02의 공통 실행](02-java-retrieval-execution.md)을 사용한다. 공식 평가의 새 정책은 명시적 버전 또는 선택지로 지정하고, 기존 Java eval 요청과 예전 결과의 의미를 보존한다. 정상 후보는 온라인과 같은 raw 대비 threshold로 판정한다. 빈 후보와 LLM 실패 또는 휴리스틱 fallback은 원 질의·검색 결과로 돌리고 사유를 기록한다. 공급자가 실제로 호출되지 않은 경우와 호출 후 빈 응답을 구별한다.
2. `gating_effect`의 세 행에 각각 검증된 게이팅 batch·run·preset을 지정한다. 데이터셋·생성 전략·모델·retriever·재작성 정책을 고정한다. 준비 상태 검사와 실제 조회에 같은 스냅샷을 사용한다. `memory_only_*` 기존 ablation은 명시적 legacy 정책과 연결한다. 기존 모드 문자열을 Java allowlist에 추가하는 것만으로 완료 처리하지 않는다.
3. Java 공식 runner의 대조군 순위·MRR·악화율은 같은 Java raw 실행의 청크 순서에서 구한다. raw 대조군이 필요한 모드에는 Java raw 결과를 확보하고, 대조군을 실행하지 않은 단일 run은 누락을 표현한다. Python raw 선계산은 이를 소비하는 legacy 경로에 둔다.
4. 새 평가 정책에서 Anchor 주입 여부를 별도 입력으로 적용한다. 비교할 때는 동일 실행 모드와 스냅샷을 사용한다. 메모리 후보, 후보별 질의·순위·confidence, 실제 주입한 힌트와 출처, 최종 채택과 거절 사유를 반환한다. 실제 사용 가능한 힌트만 기록하며 후처리는 기존 `RagRewriteAnchorEvalService`가 담당한다. 다중 소스 확장 같이 Java 지원이 확인되지 않은 정책은 지원 여부를 UI·결과에 구분하고 적용된 것으로 집계하지 않는다.
5. 정책 버전, 실제 설정, 스냅샷 식별자, 구현에서 사용한 모델·프롬프트, 진단 수집 여부를 기록한다. 평가 정답과 기대 청크 ID는 Python 채점 및 Anchor 후처리에서 사용한다. 실제 검색의 도메인·스냅샷 범위는 연구 run 설정으로 고정한다.

## C. 답변 평가에 Java 검색 결과 재사용

1. `eval-retrieval`이 만든 순서 있는 최종 청크 ID와 최종 질의, 실행 모드 및 설정을 실험 산출물로 전달한다. run·sample·mode·정책 버전·dataset·snapshot을 검증해 다른 실행의 결과를 섞지 않는다. 같은 실험 키로 재시도해도 이전 실행 결과를 잘못 읽지 않아야 한다.
2. `eval-answer`는 지정된 모드의 청크 본문을 ID로 조회해 기존 순서대로 조립한다. Java HTTP 응답의 짧은 `contentPreview`는 근거 본문으로 사용하지 않는다. 같은 검색을 다시 실행하거나 Python에서 재순위화하지 않는다.
3. 현재 추출형 답변과 점수 계산을 사용한다. 기존 legacy run이 요구하는 Python 독립 실행 경로는 해당 모드에 둔다. Java run 산출물이 없거나 계약이 맞지 않으면 설정 오류로 기록하고 복구 가능한 단계에서 재시도한다.

## 완료 기준

- 실제 요청에서 `raw_only`는 원 질의 검색만 실행하고, gating 세 조건은 서로 다른 선택 스냅샷으로 해석된다. 같은 mock provider 응답에서 채팅과 공식 selective의 정상 후보 선택 및 최종 순위가 일치한다.
- 각 fixture에서 Answer가 읽은 최종 청크·순위가 Retrieval 지표 입력과 일치한다. no-write Java 엔드포인트는 평가 실패 시에도 온라인 행·답변을 생성하지 않는다. 기존 관리자 기록 로딩 및 작업 최종화 트랜잭션은 계속 통과한다.
- 고정 데이터셋·스냅샷에서 Anchor on/off 외의 변수를 고정한다. Recall@5, Hit@5, MRR@10, nDCG@10, 채택률·악화율, 모드별 평균·p95와 rewrite 지연, 모델 호출 수를 남긴다. 서버 실행 시간, Python 왕복 시간, 전체 작업 시간을 구별한다.
- `test_java_retrieval_client`, `test_retrieval_eval_compare`, `test_answer_eval_csv`, `test_answer_eval_latency`와 Java 평가·관리자 RAG 통합 테스트를 변경 단계에 맞춰 실행한다.

## 공식 평가 v2 산출물

- `gating_effect`는 `raw_only`와 `selective_rewrite_ungated`, `selective_rewrite_rule_only`, `selective_rewrite_full_gating`을 기록한다. 원 질의 행에는 메모리 스냅샷을 적용하지 않는다. 재작성 실패 시 `skip_to_raw`를 적용하고 실패 원인을 trace에 남긴다.
- `eval-retrieval`은 `data/reports/retrieval_context/<retrieval-run-id>.json`에 답변용 행을 저장한다. 관리자 작업은 해당 ID를 `eval-answer --retrieval-run-id`로 전달한다. 답변은 행의 스냅샷과 실행 키, 설정 해시, 데이터셋, 샘플 질의를 검증하고 청크 본문을 읽는다. 기본 답변 행은 `gating_effect`의 `selective_rewrite_full_gating`과 `rewrite_effect`의 `selective_rewrite`다.
- Anchor 주입 비교는 같은 데이터셋, 스냅샷, retrieval과 model 설정의 `rewrite_effect` 두 실행에서 `rewriteAnchorInjectionEnabled`만 바꾼다. 실제 공급자와 고정 코퍼스를 사용하는 실측 지표, 지연과 호출 수 비교는 별도 실행 기록에 남긴다.
