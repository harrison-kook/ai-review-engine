# CLAUDE.md — ai-review-engine

- 설계 기준은 `docs/AI_REVIEW_SYSTEM_PLAN.md`를 따른다.
- 패키지 의존 방향: `core` ← (`llm`, `stack`, `adapter`, `renderer`, 최상위 부트스트랩 클래스). `core`는 구현 패키지나 `ReviewApplication`/`ReviewRunner`를 참조하지 않는다. 인터페이스(`StackAdapter`, `LlmClient`, `FindingsRenderer` 등)는 `core` 하위에 둔다.
- 룰팩은 `rulepack/` 경로에서 파일로 읽는다 (`--rulepack` 옵션). `src/main/resources`에 넣지 않는다. 룰팩 자체는 `review-rulepack` 별도 저장소에서 버전 관리하며, 이 폴더는 로컬 개발용 체크아웃 자리일 뿐이다.
- 새 기능은 `src/test/java/com/tororang/review/arch/ArchitectureTest.java`를 통과해야 한다.
- Findings 출력은 `ruleId`와 `evidence`가 없으면 렌더링 전에 자동 폐기한다 (설계서 7.3).
- 외부 코드(대상 레포) 빌드/테스트는 항상 샌드박스(Docker)에서 실행한다는 원칙을 전제로 설계한다 (설계서 1.3). 로컬 개발 단계에서는 아직 샌드박스를 강제하지 않지만, CI 연동 시점(체크리스트 12장)에 반드시 적용한다.
- 1단계 범위: GitHub Actions 어댑터 + PR 코멘트 렌더러, diff 모드. CLI 어댑터/Markdown 렌더러/full 모드는 이후 단계.
