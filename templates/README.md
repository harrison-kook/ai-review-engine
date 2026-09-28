# 파일럿 레포용 템플릿

이 폴더는 엔진 레포(`ai-review-engine`) 자체가 아니라, **파일럿 대상 레포**에 복사해서 쓰는
시작점이다. 실제 조직명/레포 경로(`our-org/...`, `ghcr.io/our-org/...`)는 예시이므로 반드시
바꿔서 써야 한다.

## 구성

- `github-workflows/review-build.yml` → 대상 레포의 `.github/workflows/review-build.yml`
- `github-workflows/review-report.yml` → 대상 레포의 `.github/workflows/review-report.yml`
- `review.yml.example` → 대상 레포 루트의 `.review.yml`

## 왜 워크플로가 2개인가

설계서 1.3 "fork PR에는 시크릿이 노출되지 않도록 워크플로 트리거 분리"를 그대로 구현한 것이다.

- `review-build.yml`: `pull_request` 트리거 (fork PR 포함). 시크릿 없음. 대상 레포의
  `gradlew build/check`가 실제로 도는 유일한 단계라서, 여기엔 시크릿을 절대 주면 안 된다.
- `review-report.yml`: `workflow_run` 트리거. build가 끝난 뒤 base 레포 컨텍스트(시크릿 접근
  가능)에서 돈다. PR 코드를 checkout 하긴 하지만 **실행하지 않는다** — LLM이 Read/Grep/Glob으로
  읽기만 한다.

하나의 `pull_request_target` 워크플로로 합치고 싶을 수도 있지만, 그러면 "시크릿을 가진 잡이
untrusted 코드를 checkout"하는 조합이 한 잡 안에 생겨서 사고 여지가 더 크다. 2-워크플로
패턴이 더 안전하다.

## 적용 전 체크리스트

- [ ] `ghcr.io/our-org/ai-review-engine:latest` 를 실제 이미지 경로로 교체 (엔진 레포에서 빌드해
      registry에 올려둬야 한다 — `docker/README.md` 참고)
- [ ] `our-org/review-rulepack@v0.1.0` 을 실제 룰팩 저장소/태그로 교체
- [ ] `ANTHROPIC_API_KEY`를 대상 레포의 Actions secret으로 등록
- [ ] `.review.yml`의 `profiles`/`gate.fail_on`을 팀 상황에 맞게 조정 (설계서 13장 미결 사항)
