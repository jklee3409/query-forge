# Dataset Design

본 프로젝트는 3종 데이터셋을 사용한다.

1. Corpus dataset
2. Synthetic training dataset
3. Human evaluation dataset (KO, Dev/Test)

## 1) 왜 Spring 공식 문서인가

- 실제 개발 현장에서 사용되는 고신뢰 기술 문서다.
- 제품/버전/설정 키/애노테이션/클래스 등 구조적 엔티티가 뚜렷해 retrieval/rewrite 연구에 적합하다.
- 도메인 지식이 풍부해서 단순 FAQ보다 난도가 높고, 실패 사례 분석 가치가 크다.

## 2) 왜 한국어 질문 부족 상황을 재현하는가

- 영어 문서 대비 한국어 질의 로그는 희소하거나 편향될 가능성이 높다.
- 실제 서비스에서는 한국어 사용자가 영어 기술 문서를 탐색할 때 질의-문서 mismatch가 자주 발생한다.
- 따라서 합성 질의 생성 + 품질 게이팅 + rewrite의 결합 효과를 검증하기 적합하다.

## 3) Human Eval 70/70 설계 근거

- Dev/Test 각각 70개는 수작업 검토 가능성과 반복 실험 속도의 균형점이다.
- category 분포:
  - 일반 한국어 질의 25
  - 설정/문제해결 질의 15
  - 짧은 사용자형 10
  - code-mixed 10
  - 문맥 의존형 후속 질의 10
- 위 분포는 실제 검색 실패 빈도가 높은 케이스(짧은 질의, code-mixed, follow-up)를 충분히 포함한다.

## 4) split 정책

- 문서 family 기준으로 Dev/Test를 분리해 leakage를 줄인다.
- 동일 family 내 표현 변형이 양 split에 동시에 들어가는 상황을 피한다.

## 5) Synthetic + Human 병행 이유

- Synthetic training dataset:
  - 대규모 ablation/파라미터 튜닝에 적합
  - A/B/C/D/E 생성 전략, ungated/gated 비교를 빠르게 반복 가능
- Human eval dataset:
  - 실제 사용자 질의형 문장 품질을 기준으로 안정적인 비교 척도 제공
  - 모델/전략 변경 시 회귀(regression) 감지에 유리

## 6) 언어 축 확장(ko/en)

- eval 샘플은 retrieval-aware 구조를 유지하면서 `query_language` 축을 함께 기록한다.
- 한국어 평가는 `user_query_ko`, 영어 평가는 `user_query_en`을 사용한다.
- 같은 도메인 코퍼스에서 KO/EN 비교를 위해 short-user 영어 companion 셋(`human_eval_short_user_test_80_en`)을 별도로 운용한다.
