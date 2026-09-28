# 샌드박스 이미지 사용법

## 빌드

```bash
./gradlew bootJar
docker build -f docker/Dockerfile -t ai-review-engine:local .
```

`docker/Dockerfile`은 이미지 안에 시크릿을 넣지 않는다. 대상 레포는 이미지에 포함되지 않고,
실행 시점에 `-v <clone경로>:/workspace`로 마운트한다.

## 2단계 실행 모델 (설계서 1.3)

`ReviewCommand`의 `--phase`가 build/report를 가르는 경계다. **반드시 서로 다른 `docker run`
호출로 나눠서 실행한다** — 그래야 시크릿이 대상 레포의 신뢰할 수 없는 코드(build.gradle 등)에
노출되지 않는다.

### 1) build 단계 — 시크릿 없음, 네트워크 차단

```bash
# 의존성 다운로드 (네트워크 허용, 캐시를 볼륨으로 재사용)
docker run --rm \
  -v "$PWD/target-repo:/workspace" \
  -v "$HOME/.gradle:/root/.gradle" \
  ai-review-engine:local \
  --phase=build --repo=/workspace --findings=/workspace/build/review-findings.json \
  # (첫 실행은 네트워크 허용 상태로 두어 의존성을 받는다)

# 이후 재실행/실제 CI에서는 네트워크를 차단한 채로 빌드/린트만 돈다
docker run --rm --network none \
  -v "$PWD/target-repo:/workspace" \
  -v "$HOME/.gradle:/root/.gradle" \
  ai-review-engine:local \
  --phase=build --repo=/workspace --findings=/workspace/build/review-findings.json
```

- 이 컨테이너에는 `ANTHROPIC_API_KEY`, `GITHUB_TOKEN` 등 어떤 시크릿도 전달하지 않는다.
- `StepResult.success()`가 false면(대상 레포 빌드 실패) 0이 아닌 코드로 종료하고, report 단계는
  아예 실행하지 않는다.
- 산출물: `--findings` 경로의 Findings JSON (Checkstyle/PMD/SpotBugs 결과만, LLM 지적 없음).

### 2) report 단계 — 시크릿/네트워크 필요, 대상 레포 코드는 "읽기"만

```bash
docker run --rm \
  -v "$PWD/target-repo:/workspace" \
  -v "$PWD/../review-rulepack:/rulepack:ro" \
  -e ANTHROPIC_API_KEY \
  -e GITHUB_TOKEN \
  -e GITHUB_REPOSITORY \
  -e GITHUB_EVENT_PATH \
  -v "$GITHUB_EVENT_PATH:$GITHUB_EVENT_PATH:ro" \
  ai-review-engine:local \
  --phase=report --repo=/workspace --rulepack=/rulepack \
  --config=/workspace/.review.yml --findings=/workspace/build/review-findings.json
```

- 이 단계는 `claude -p "/review"`를 호출한다 (`agents/reviewer.md`의 `tools: Read, Grep, Glob`
  제한이 `--allowedTools`로 그대로 전달된다) — 대상 레포 코드를 실행하지 않고 파일만 읽는다.
- `GITHUB_*` 환경변수가 없으면(로컬 실행 등) PR 코멘트를 건너뛰고 로그만 남긴다.

### 로컬 개발 (샌드박스 분리 없이 한 번에)

```bash
docker run --rm \
  -v "$PWD/target-repo:/workspace" \
  -v "$PWD/../review-rulepack:/rulepack:ro" \
  -e ANTHROPIC_API_KEY \
  ai-review-engine:local \
  --phase=all --repo=/workspace --rulepack=/rulepack --config=/workspace/.review.yml
```

`--phase`를 생략하면 기본값이 `all`이다. 로컬에서 빠르게 확인할 때만 쓰고, 실제 CI(특히 외부/fork
PR)에서는 반드시 build/report를 나눠서 실행한다.

## 알려진 한계

- 의존성 다운로드와 "네트워크 차단 빌드"를 완전히 자동으로 나누는 로직은 아직 없다 — 위 예시처럼
  최초 1회는 네트워크를 허용한 채로 Gradle 캐시를 데운 뒤, 이후 실행부터 `--network none`을 쓰는
  방식을 CI 워크플로에서 직접 구성해야 한다 (`templates/github-workflows/review.yml` 참고).
- Checkstyle/PMD/SpotBugs는 대상 레포의 `build.gradle`에 이미 플러그인이 설정되어 있어야 결과가
  나온다 (`GradleSpringStackAdapter.lint()`가 `gradlew check` 실행 후 표준 리포트 경로가 있을
  때만 파싱). 이미지 자체는 아무 정적분석 도구도 강제로 설치하지 않는다.
