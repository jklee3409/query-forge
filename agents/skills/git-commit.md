# Skill: git-commit

## Purpose
현재 작업 브랜치의 변경 사항을 **논리적 작업 단위별로 분리하여** 커밋하고,
일관된 규칙에 따라 커밋 메시지를 생성한다.

---

## Input
- 현재 Git 변경 상태 (staged + unstaged)
- 변경된 파일 목록 및 diff
- 기존 커밋 히스토리 (스타일 참고)

---

## Output
- 여러 개의 Git 커밋 명령 (작업 단위별)
- 각 커밋 메시지 (AngularJS Convention 준수)

---

## Rules

### 1. Commit Strategy (핵심)

변경 사항을 아래 기준으로 **작업 단위별로 분리하여 커밋한다:**

- 기능 추가 (feat)
- 버그 수정 (fix)
- 리팩토링 (refactor)
- 설정/기타 (chore)
- 테스트 (test)
- 문서 (docs)

👉 서로 다른 성격의 변경을 **절대 하나의 커밋으로 합치지 않는다**

---

### 2. File Grouping 기준

파일을 다음 기준으로 묶는다:

- 같은 기능/도메인
- 같은 수정 목적
- 같은 변경 이유

예:

- llm 관련 로직 변경 → 하나의 커밋
- gating 버그 수정 → 별도 커밋
- 테스트 코드 추가 → 별도 커밋

---

### 3. 제외 파일

다음은 커밋에서 제외:

- 임시 파일 (`.log`, `.tmp`, `.cache`)
- IDE 설정 (`.idea`, `.vscode`)
- 빌드 결과물 (`build/`, `dist/`, `out/`)

---

## Commit Message Convention

### 형식

<type>(<scope>): <subject>

---

### Type 정의

- feat: 기능 추가
- fix: 버그 수정
- refactor: 리팩토링 (동작 변화 없음)
- chore: 설정, 빌드, 기타 작업
- docs: 문서 수정
- test: 테스트 코드 관련

---

### Scope 규칙

작업 영역 기준:

- llm
- gating
- pipeline
- api
- db
- ui

---

### Message 스타일

- 한글 기반 + 영어 기술 용어 혼합
- 간결하게 핵심만 작성
- 구현 상세 나열 금지

예:

- feat(llm): synthetic query 생성 로직 추가
- fix(gating): rule filter threshold 적용 오류 수정
- refactor(pipeline): query generation flow 구조 개선

---

## Constraints

- 하나의 커밋은 **하나의 목적만 가져야 한다**
- 커밋 간 의존성이 최소화되어야 한다
- 의미 없는 메시지 금지 (e.g. "update", "fix bug")
- 기존 커밋 스타일 반드시 유지

---

## Process

1. 변경 파일 분석
2. 변경 목적 기준으로 그룹 분리
3. 각 그룹별로:
    - git add (선택적 파일)
    - git commit 생성
4. 전체 변경이 모두 커밋될 때까지 반복

---

## Checklist

각 커밋마다:

- [ ] 하나의 목적만 포함하는가
- [ ] 관련 파일만 포함했는가
- [ ] type이 적절한가
- [ ] scope가 명확한가
- [ ] 메시지가 한글 + 영어 혼합인가

---

## Example Output

git add llm/generator.py llm/prompt_builder.py
git commit -m "feat(llm): synthetic query 생성 로직 추가"

git add gating/rule_filter.py
git commit -m "fix(gating): threshold 비교 로직 오류 수정"

git add tests/test_llm_generator.py
git commit -m "test(llm): query generation 테스트 코드 추가"