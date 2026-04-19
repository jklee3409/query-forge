# index.md

## Directory Overview
프로젝트 실행/실험/프롬프트 설정을 코드 외부에서 관리하는 설정 디렉토리입니다.

---

## Structure
- `README.md`: 설정 디렉토리 사용 가이드
- `app/application.yml`, `app/application-docker.yml`: 애플리케이션 공통 및 Docker 프로파일 설정
- `app/chunking.yml`: 문서 청킹 파라미터
- `app/sources/*.yaml`: 수집 대상 소스 정의(Spring Boot/Data/Framework/Security)
- `experiments/*.yaml`: generation/gating/eval 실험 프리셋(`gen_*`, `e2e_*`, `rule_*`, `rewrite_*` 등)
- `prompts/query_generation/gen_[a-d]_v1.md`: 전략별 합성 질의 프롬프트
- `prompts/summary_extraction/*.md`: 요약/한국어 요약 프롬프트
- `prompts/self_eval/quality_gate_v1.md`: 게이팅 자기평가 프롬프트
- `prompts/rewrite/selective_rewrite_v1.md`, `prompts/rewrite/selective_rewrite_v2.md`: 선택적 리라이트 프롬프트 버전
- `prompts/translation/translate_chunk_en_to_ko_v1.md`: 번역 프롬프트

---

## Responsibilities
- 하드코딩 없이 파이프라인 실행값과 실험 조건을 주입
- 전략(A/B/C/D) 및 평가 모드별 재현 가능한 실험 정의 제공
- LLM 프롬프트 버전 관리와 교체 지점 제공

---

## Key Flows
- `pipeline/cli.py`가 `app/` 설정과 `experiments/` 프리셋을 로드
- generation/gating/eval 단계에서 필요한 `prompts/` 자산을 매핑
- 실험명 기준으로 동일 설정 재실행 및 결과 비교 수행

---

## Notes
- Update this file when structure or responsibilities change
