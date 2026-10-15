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
- 호출 모델은 기본값이 `sonnet`이다 (비용 민감 경로라 opus보다 저렴한 모델을 기본으로 쓴다).
  `-e REVIEW_LLM_MODEL=opus`처럼 컨테이너에 환경변수를 넘기면 바꿀 수 있다.

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

### 로컬 Ollama로 리뷰하기 (로컬 전용, GitHub Actions 미지원)

`REVIEW_LLM_BACKEND=ollama`를 주면 `OllamaLlmClient`가 Anthropic API 대신 로컬에서 돌고 있는
[Ollama](https://ollama.com)를 쓴다. 비용이 들지 않지만 **Claude Code CLI처럼 Read/Grep/Glob
도구 호출을 못 하므로 diff 모드만 지원**하고, 적용 규칙 본문과 diff 텍스트를 프롬프트에 직접
넣는다(레포 전체 탐색 불가). **GitHub Actions 쪽은 지원하지 않는다** — GitHub가 호스팅하는
러너는 사용자 PC의 Ollama(localhost)에 네트워크로 닿을 수 없기 때문이다. 로컬 CLI(`--phase=all`
또는 `report`)에서만 쓴다.

```bash
ollama pull qwen2.5-coder:7b   # 한 번만
ollama serve                    # 백그라운드로 켜둠

OLLAMA_MODEL=qwen2.5-coder:7b REVIEW_LLM_BACKEND=ollama \
  docker run --rm --add-host=host.docker.internal:host-gateway \
    -v "$PWD/target-repo:/workspace" \
    -v "$PWD/../review-rulepack:/rulepack:ro" \
    -e REVIEW_LLM_BACKEND -e OLLAMA_MODEL -e OLLAMA_HOST=http://host.docker.internal:11434 \
    ai-review-engine:local \
    --phase=report --repo=/workspace --rulepack=/rulepack --config=/workspace/.review.yml
```

- `OLLAMA_MODEL`은 필수(설정 안 하면 바로 에러), `OLLAMA_HOST` 기본값은 `http://localhost:11434`
  — 컨테이너 안에서 호스트의 Ollama에 접근하려면 위처럼 `host.docker.internal`로 바꿔줘야 한다.
- 매 REPORT/ALL 실행마다 요약이 `--history`(기본 `<repo>/build/review-history.json`)에 하나씩
  누적된다(규칙별 위반 건수, 심각도, LLM 응답시간/비용, 커버리지·뮤테이션 스코어). 로컬에서
  반복 실행할 때만 의미가 있다 — `docker run --rm`인 CI 컨테이너는 매번 사라지므로 누적되지 않는다.
- `--phase=stats`를 돌리면 누적된 `--history`를 읽어 `--stats-report`(기본
  `<repo>/build/review-stats.md`)에 통계 Markdown을 만든다.

## 알려진 한계

- 의존성 다운로드와 "네트워크 차단 빌드"를 완전히 자동으로 나누는 로직은 아직 없다 — 위 예시처럼
  최초 1회는 네트워크를 허용한 채로 Gradle 캐시를 데운 뒤, 이후 실행부터 `--network none`을 쓰는
  방식을 CI 워크플로에서 직접 구성해야 한다 (`templates/github-workflows/review.yml` 참고).
- Checkstyle/PMD/SpotBugs는 대상 레포의 `build.gradle`에 이미 플러그인이 설정되어 있어야 결과가
  나온다 (`GradleSpringStackAdapter.lint()`가 `gradlew check` 실행 후 표준 리포트 경로가 있을
  때만 파싱). 이미지 자체는 아무 정적분석 도구도 강제로 설치하지 않는다.
- 커버리지 게이트(`gate.min_coverage_delta`)를 쓰려면 대상 레포의 `build.gradle`에 `jacoco`
  플러그인뿐 아니라 **XML 리포트를 명시적으로 켜야 한다** — Gradle jacoco 플러그인은 기본값이
  HTML만 생성이고 XML은 꺼져 있다:
  ```gradle
  plugins { id 'jacoco' }
  tasks.named('jacocoTestReport') {
      reports { xml.required = true }
  }
  ```
  이게 없으면 `jacocoTestReport.xml`이 안 생겨서 커버리지가 항상 0%로 잡힌다(엔진은 이 경우
  `CoverageReport.EMPTY`로 조용히 폴백한다 — 에러를 내지 않으니 설정을 빼먹었는지 직접 확인해야
  한다).
- 뮤테이션 게이트(`gate.min_mutation_score`)를 쓰려면 대상 레포에 PIT gradle 플러그인
  (`info.solidsoft.gradle.pitest` 등)이 설정되어 있어야 한다. PIT은 뮤턴트마다 별도 JVM을 띄워
  느리다 — 이 게이트를 설정했을 때만 `pitest` 태스크를 실행한다.
