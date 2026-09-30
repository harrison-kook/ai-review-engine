# 대상 레포 온보딩 체크리스트

> 이 시스템(리뷰 엔진 + 룰팩)을 새 레포에 붙일 때 확인해야 할 사전 요구사항과 실전에서
> 발견된 주의사항을 정리한다. `sample-target`(파일럿 검증) / `freepoint` / `tororang-admin`을
> 실제로 점검하며 나온 케이스를 근거로 작성했다.

## 1. 필수 요구사항 (지금 지원하는 스택)

- **Gradle + Spring Boot 프로젝트**여야 한다 — 현재 스택 어댑터가 `gradle-spring` 하나뿐이다
  (`StackAdapter.detect()`가 `build.gradle` 존재로 판별). Maven/Node 등은 아직 미지원
  (설계서 4단계, 필요 시 착수).
- **`build.gradle`이 레포 루트에 있어야 한다.** 서브폴더에 있으면(예: `freepoint/` 케이스처럼
  레포 루트 밑에 프로젝트 폴더가 한 번 더 있는 구조) 스택 감지가 실패한다. 이 경우
  워크플로에서 레포 전체가 아니라 **실제 프로젝트 루트만 `/workspace`로 마운트**하고,
  `.review.yml`도 그 안에 둬야 한다.
  ```yaml
  -v "${{ github.workspace }}/<프로젝트폴더>:/workspace"
  ```
  이 구조에서는 GitHub PR files API가 주는 경로(`<프로젝트폴더>/src/...`)와 컨테이너 내부
  경로(`src/...`)가 어긋날 수 있어 diff 스코프 필터가 의도대로 동작하는지 **실제로 붙여서
  검증**해야 한다 (아직 이 케이스는 라이브로 검증된 적 없음).
- **`gradlew`가 저장소에 있어야 한다.** 실행 권한이 없어도(`git ls-files -s gradlew`가
  `100644`인 Windows 커밋 레포에서 흔함) 엔진이 실행 전에 자동으로 `chmod +x` 하므로
  대상 레포 쪽에서 따로 고칠 필요 없다.

## 2. CI 연동에 필요한 파일 (레포마다 추가)

| 파일 | 용도 |
|---|---|
| `.review.yml` | 룰팩 버전, `profiles`, `mode`(diff/full), `gate` 등 |
| `.github/workflows/review-build.yml` | PR마다 트리거, 시크릿 없이 네트워크 차단 빌드/린트 |
| `.github/workflows/review-report.yml` | build 성공 후 `workflow_run`으로 트리거, LLM 리뷰 + PR 코멘트 |

- **GitHub Secrets는 레포별로 개별 등록해야 한다** — 다른 레포에 등록해둔 걸 재사용할 수 없다.
  - `ANTHROPIC_API_KEY` (LLM 호출)
  - `GH_PAT` — `ai-review-engine`/`review-rulepack`이 **private**일 때만 필요
    (checkout 시 기본 `GITHUB_TOKEN`은 다른 레포에 접근 못 함). 둘 다 public이면 불필요.

## 3. 선택 기능을 쓰려면 대상 레포에 추가로 필요한 것

| 기능 | 대상 레포에 필요한 설정 |
|---|---|
| 결정적 린트(Checkstyle/PMD/SpotBugs) | 해당 Gradle 플러그인이 `build.gradle`에 있어야 결과가 나온다. 없으면 에러 없이 그냥 0건. |
| 커버리지 게이트(`gate.min_coverage_delta`) | `jacoco` 플러그인 + **XML 리포트 명시적으로 켜야 함** (기본은 HTML만): `tasks.named('jacocoTestReport') { reports { xml.required = true } }`. 안 켜면 항상 0%로 조용히 폴백(에러 없음 — 설정 빠뜨렸는지 직접 확인해야 함). |
| 뮤테이션 게이트(`gate.min_mutation_score`) | PIT Gradle 플러그인(`info.solidsoft.gradle.pitest`) 필요. 느려서 게이트 설정했을 때만 실행됨. |

## 4. 실전에서 발견한 주의사항 / 함정

- **Java 툴체인 버전이 샌드박스 이미지의 JDK보다 높음** — 샌드박스는 JDK 21까지만 있다.
  `build.gradle`이 `JavaLanguageVersion.of(26)` 등 더 높은 버전을 요구하면 Gradle이 해당
  JDK를 자동 다운로드해야 하는데, **`settings.gradle`에 `foojay-resolver-convention` 플러그인이
  없으면** "Toolchain download repositories have not been configured"로 네트워크 상태와
  무관하게 즉시 실패한다 (`tororang-admin`에서 실제로 발생, JDK 26 요구).
  → 대상 레포의 `settings.gradle`에 추가:
  ```gradle
  plugins {
      id 'org.gradle.toolchains.foojay-resolver-convention' version '1.0.0'
  }
  ```
  **버전 호환성 주의**: 이 플러그인의 오래된 버전(`0.8.0`, `0.9.0` 등)은 최신 Gradle(9.5.1
  기준 확인)에서 `Class org.gradle.jvm.toolchain.JvmVendorSpec does not have member field
  'IBM_SEMERU'`로 플러그인 자체가 안 걸린다 — `1.0.0`에서 해결됨(`tororang-admin`에서
  실제 재현·확인). 이 다운로드는 네트워크가 열려 있는 "Warm Gradle cache" 단계에서 이뤄지고
  `~/.gradle/jdks/`에 캐시되어 이후 네트워크 차단 단계에서 재사용된다.
- **외부 인프라(DB/MQ/Redis 등) 의존 테스트** — 샌드박스는 `docker-compose`를 띄우지 않고
  `--network none`으로 돈다. 테스트가 DB는 H2로 격리해도 **MQ(RabbitMQ 등)까지 격리 안
  돼 있으면** `@SpringBootTest`류가 전체 컨텍스트를 띄우다 연결 실패로 죽을 위험이 있다.
  `tororang-admin`(DB는 H2, RabbitMQ는 `application.yml`에 `localhost:5672`로 남아있는
  구성)으로 실제 BUILD 단계를 라이브 검증한 결과 **이번 케이스에서는 실패하지 않았다** —
  다만 이건 이 레포의 특정 테스트 구성에 한정된 결과이니, 다른 레포에 적용할 때 AMQP
  auto-configuration을 실제로 예열/사용하는 테스트가 있다면 별도로 확인해야 한다.
  → 온보딩 전에 테스트가 슬라이스 테스트로 분리돼 있는지, 임베디드/모킹으로 외부 인프라를
  대체하는지 확인한다.
- **Gradle 데몬/VFS 락 경합** — 같은 `~/.gradle`을 공유하는 연속된 컨테이너 실행(캐시 워밍
  → 네트워크 차단 빌드) 사이에서 데몬이 완전히 안 죽고 `journal-1.lock`을 물고 있어 실패하는
  사례가 있었다. 엔진이 대상 레포의 gradlew를 돌릴 때는 이미 `--no-daemon --no-watch-fs`를
  적용해 해결했지만, **엔진 자체를 CI에서 빌드하는 스텝**(`./gradlew bootJar`)에는 워크플로
  작성자가 직접 같은 플래그를 챙겨야 한다.
- **Code Scanning(SARIF) 비활성** — private 레포는 GitHub Advanced Security 없이는 Code
  Scanning 자체가 막혀 있다 (플랜 제약, 워크플로 버그 아님). SARIF 업로드 스텝은
  `continue-on-error: true`로 처리해 전체 파이프라인이 실패하지 않게 해뒀다.
- **`.claude/` 폴더 충돌** — 대상 레포에 이미 `.claude/settings.local.json` 등이 있어도
  룰팩 주입은 `.claude/CLAUDE.md`, `.claude/agents/`, `.claude/commands/`, `.claude/rulepack/`
  만 쓰고 `REPLACE_EXISTING`으로 덮어쓴다. 대상 레포가 자체 `.claude/CLAUDE.md`를 이미 쓰고
  있다면 **리뷰 실행 시 덮어써진다** — 충돌 여부를 사전에 확인한다.
- **Windows에서 만들어진 레포** — `gradlew`뿐 아니라 다른 셸 스크립트도 실행 비트가 없을 수
  있다. CRLF 개행도 종종 딸려온다 (기능엔 영향 없지만 `git diff` 노이즈가 커진다).

## 5. 온보딩 순서 (권장)

1. 대상 레포를 로컬에 clone해서 위 1~4번 항목을 눈으로 확인한다 (build.gradle 위치, 플러그인
   목록, 테스트가 외부 인프라에 의존하는지).
2. `.review.yml` + 워크플로 2개를 추가한다 (`docker/README.md`, `templates/github-workflows/`
   참고).
3. `ANTHROPIC_API_KEY`(+ 필요하면 `GH_PAT`)를 대상 레포 Secrets에 등록한다.
4. 실제 코드 변경이 있는 테스트 PR을 올려 BUILD → REPORT 워크플로가 끝까지 성공하는지
   확인한다. 여기서 3~4번 항목의 함정이 실제로 걸리는지 드러난다.
5. 문제 없이 통과하면 그제서야 `gate.fail_on`을 켜는 등 강제력을 올린다 (13장 미결 사항
   결정: 파일럿 기간엔 경고만 → 데이터 쌓은 뒤 전환).
