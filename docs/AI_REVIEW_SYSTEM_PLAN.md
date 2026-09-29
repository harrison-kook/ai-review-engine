# AI 기반 자동 코드리뷰 · 테스트 시스템 설계서

> 목적: 팀 PR 자동 리뷰로 먼저 운영하고, 트리거·대상만 바꾸면 임의의 GitHub 레포도 분석할 수 있는 범용 도구로 확장한다.
> 구현 언어: Java (IDE: IntelliJ IDEA + Claude Code JetBrains 플러그인)

---

## 1. 핵심 설계 원칙

### 1.1 룰팩은 대상 레포 밖에서 관리하고, 실행 시 주입한다
- 리뷰 규칙 · 테스트케이스 md는 별도 **룰팩 저장소**에서 버전 관리한다.
- 실행 시 clone한 레포 안에 `.claude/` 형태로 복사해 넣고 실행한다.
- 레포가 달라져도 같은 기준으로 리뷰할 수 있고, 규칙 변경 이력이 남는다.

### 1.2 결정적 도구와 LLM의 역할을 분리한다
| 담당 | 역할 |
|---|---|
| 결정적 도구 (Gradle/Maven, Checkstyle, PMD, SpotBugs, JaCoCo, PIT) | 컴파일, 린트, 정적분석, 테스트 실행, 커버리지, 뮤테이션 |
| LLM (Claude) | 설계 판단, 규칙 해석, 테스트 코드 생성, 결과 요약 |

→ 결과 재현성 확보 + 비용 절감

### 1.3 외부 코드는 반드시 샌드박스에서 실행한다
- 모든 빌드/테스트는 Docker 컨테이너 안에서 실행 (팀 레포라도 처음부터 기본값으로)
- 컨테이너에 시크릿 주입 금지
- 의존성 다운로드 이후 네트워크 차단
- fork PR에는 시크릿이 노출되지 않도록 워크플로 트리거 분리

---

## 2. 전체 아키텍처 (3계층)

```
┌──────────────────────────────────────────────────────────────┐
│ 트리거 어댑터   GitHub Actions(PR) │ CLI │ (향후) Webhook/API      │
├──────────────────────────────────────────────────────────────┤
│ 코어 엔진      입력 정규화 → 스택 감지 → 분석 → LLM 리뷰 → 테스트 → Findings │
├──────────────────────────────────────────────────────────────┤
│ 룰팩           공통 → 스택 → 도메인 → 팀 → 레포 로컬 (계층 오버라이드)     │
└──────────────────────────────────────────────────────────────┘
        ↓ 출력 렌더러: PR 인라인 코멘트 │ Markdown 리포트 │ SARIF
```

- 1단계 구현 범위: **GitHub Actions 어댑터 + PR 코멘트 렌더러**
- 범용화 시 추가: **CLI 어댑터 + Markdown 리포트 렌더러** (코어는 수정하지 않음)

---

## 3. 파이프라인

```
[입력: GitHub URL / PR]
   ↓
① Clone & 프로젝트 감지   build.gradle / pom.xml / package.json → 스택·버전 판별
   ↓
② 룰팩 선택·주입          .review.yml의 profiles 기준으로 .claude/ 에 복사
   ↓
③ 결정적 분석            빌드 → Checkstyle/PMD/SpotBugs → 기존 테스트 + JaCoCo
   ↓
④ LLM 리뷰              reviewer 에이전트: 룰 md + ③ 결과 + diff/소스 → 규칙 ID별 지적
   ↓
⑤ 테스트 생성·실행        tester 에이전트: 테스트케이스 md → 테스트 작성 → 실행 → 실패 분석
   ↓
⑥ 리포트                Findings JSON → 렌더러 (PR 코멘트 / MD / SARIF)
```

### 3.1 실행 모드
| 모드 | 용도 | 리뷰 범위 |
|---|---|---|
| `diff` | 팀 PR 리뷰 | 변경 파일·라인만 리뷰, 주변 코드는 컨텍스트로만 사용 |
| `full` | 외부 레포 분석 | 전체 소스 |

엔진 입장에서는 **scope 입력값 하나만 다르게** 처리한다.

---

## 4. 저장소 구성

### 4.1 룰팩 저장소 (`review-rulepack`)

```
review-rulepack/
├── CLAUDE.md                     # 공통 행동 지침 (리뷰 톤, 출력 형식, 금지사항)
├── rules/
│   ├── common/
│   │   ├── security.md           # SEC-001 SQL Injection, SEC-002 시크릿 하드코딩 ...
│   │   └── error-handling.md
│   ├── java-spring/
│   │   ├── layering.md           # Controller-Service-Repository 책임 분리
│   │   └── jpa.md                # N+1, 트랜잭션 경계, 지연로딩
│   ├── domain/
│   │   └── payment.md            # 멱등성, 금액 타입(BigDecimal), 재시도 정책
│   └── team/
│       └── our-team.md           # 팀 고유 컨벤션
├── testcases/
│   ├── common/api-contract.md
│   ├── java-spring/service-layer.md
│   └── domain/payment-flow.md    # 승인/취소/부분취소/중복요청 시나리오
├── agents/
│   ├── reviewer.md               # 리뷰 전담 서브에이전트
│   └── tester.md                 # 테스트 작성·실행 전담 서브에이전트
├── commands/
│   ├── review.md                 # /review
│   └── gen-test.md               # /gen-test
├── schema/
│   ├── review-config.schema.json # .review.yml 스키마
│   └── findings.schema.json      # Findings 출력 스키마
└── report-template.md
```

버전은 Git 태그로 관리한다 (`v1.0.0`, `v1.1.0` ...). 각 레포는 태그로 고정해서 참조한다.

### 4.2 엔진 저장소 — 최종 형태 (`ai-review-engine`, Gradle 멀티모듈)

> 이 구성은 **안정화 이후의 목표 형태**다. 1단계는 4.3의 단일 Spring Boot 프로젝트로 시작한다.

```
ai-review-engine/
├── settings.gradle
├── core/                     # 파이프라인, 설정 로딩, 룰 병합, Findings 모델
├── llm/                      # LlmClient 인터페이스 + 구현체
├── stack-gradle-spring/      # 첫 번째 스택 어댑터
├── adapter-github/           # PR diff 수집, 코멘트 작성
├── adapter-cli/              # (3단계) 로컬/외부 레포 실행
├── renderer-pr-comment/
├── renderer-markdown/
├── renderer-sarif/           # (3단계)
└── docker/                   # 샌드박스 실행 이미지
```

### 4.3 초기 구성 — 단일 Spring Boot 프로젝트로 시작

1단계는 일반 Java Spring Boot 프로젝트 하나에서 작업한다. 익숙한 구조에서 빠르게 시작하되, 아래 원칙을 지켜서 나중에 4.2 멀티모듈로 **패키지를 그대로 옮기기만 하면** 분리되도록 한다.

#### (1) 웹 서버 없이 CLI로 실행
GitHub Actions에서는 "실행하고 끝나는" 프로그램이어야 한다.

```yaml
# application.yml
spring:
  main:
    web-application-type: none
```

```java
@Component
@RequiredArgsConstructor
public class ReviewRunner implements ApplicationRunner {
    private final ReviewPipeline pipeline;

    @Override
    public void run(ApplicationArguments args) {
        int exitCode = pipeline.run(ReviewCommand.from(args));
        System.exit(exitCode);   // gate 결과를 CI 체크 성공/실패로 전달
    }
}
```

- Spring Boot 기동 시간(수 초)은 LLM 호출 시간에 비해 무시할 수준
- 향후 Webhook/대시보드가 필요하면 프로파일(`web`)로 웹 모드를 켠다

#### (2) 패키지를 모듈 단위로 분리

```
com.example.review
├── core          # 파이프라인, 설정 로딩, 룰 병합, Finding 모델
├── llm           # LlmClient, ClaudeCodeCliClient
├── stack         # StackAdapter, gradlespring
├── adapter       # github, cli
└── renderer      # prcomment, markdown, sarif
```

**의존 방향 규칙**: `core`는 `adapter`, `renderer`, 특정 스택 구현을 직접 참조하지 않는다 (인터페이스만 `core`에 둔다).

#### (3) ArchUnit으로 의존 방향 강제

```java
@AnalyzeClasses(packages = "com.example.review")
class ArchitectureTest {

    @ArchTest
    static final ArchRule core_is_independent =
        noClasses().that().resideInAPackage("..core..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..adapter..", "..renderer..", "..stack.gradlespring..");
}
```

리뷰 시스템 자체가 아키텍처 규칙을 지키는지 테스트로 보장한다.

#### (4) 룰팩은 `src` 밖에 둔다
- 당장 별도 저장소가 번거로우면 프로젝트 루트의 `rulepack/` 폴더로 시작
- 클래스패스 리소스로 묶지 말고 **파일 경로로 읽는다** (`--rulepack=./rulepack` 옵션)
- 나중에 별도 저장소 + 태그 버전 참조로 그대로 전환 가능

#### (5) 설계서와 CLAUDE.md 연결

```
ai-review-engine/
├── CLAUDE.md                        # 프로젝트 규칙 (아래 예시)
├── docs/
│   └── AI_REVIEW_SYSTEM_PLAN.md     # 이 설계서
├── rulepack/                        # 초기 룰팩 (추후 별도 저장소로 분리)
├── src/main/java/com/example/review/...
└── src/test/java/com/example/review/ArchitectureTest.java
```

```markdown
# CLAUDE.md
- 설계 기준은 docs/AI_REVIEW_SYSTEM_PLAN.md 를 따른다.
- 패키지 의존 방향: core ← (llm, stack, adapter, renderer). core는 구현 패키지를 참조하지 않는다.
- 룰팩은 rulepack/ 경로에서 파일로 읽는다. src/main/resources 에 넣지 않는다.
- 새 기능은 ArchitectureTest 를 통과해야 한다.
```

#### (6) 멀티모듈 분리 시점
1단계를 파일럿 레포에 적용해 구조가 안정되고, CLI 어댑터(3단계)를 추가하는 시점에 4.2 구성으로 분리를 검토한다.

---

## 5. 룰 · 테스트케이스 md 작성 규약

### 5.1 리뷰 규칙 형식
규칙 ID는 반드시 붙인다. 위반 집계와 오탐이 많은 규칙 추적이 가능해진다.

```markdown
## JPA-003: 반복문 내 지연로딩 접근 금지 (N+1)
- 심각도: HIGH
- 검사 방식: LLM (정적분석으로 탐지 불가)
- 판단 기준: 컬렉션 순회 중 연관 엔티티 getter 호출 + fetch join/EntityGraph 부재
- Bad 예시:
  ```java
  orders.forEach(o -> o.getItems().size());
  ```
- Good 예시:
  ```java
  @Query("select o from Order o join fetch o.items")
  ```
- 예외: @BatchSize 설정이 명시된 경우
```

**규칙 ID 접두어**: `SEC` 보안, `ERR` 예외처리, `LAY` 계층, `JPA` 영속성, `PAY` 결제, `STYLE` 스타일, `TEAM` 팀 규칙

**심각도**: `HIGH`(머지 차단 후보) / `MEDIUM` / `LOW` / `INFO`

### 5.2 테스트케이스 형식

```markdown
## TC-PAY-001: 동일 주문번호 중복 승인 요청 시 멱등 처리
- 대상 계층: Service
- 우선순위: P1
- 탐색 힌트: 결제 승인 메서드 (approve, confirm, pay 등)
- Given: 주문번호 A로 승인 완료된 상태
- When: 동일 주문번호 A로 승인 재요청
- Then: 기존 승인 결과 반환, PG 승인 API 재호출 없음, 결제 레코드 1건 유지
```

tester 에이전트는 **탐색 힌트로 대상 레포에서 해당 클래스를 찾은 뒤** 테스트를 작성한다.

---

## 6. 레포별 설정 파일 (`.review.yml`)

```yaml
# 각 레포 루트
rulepack: our-org/review-rulepack@v1.2.0   # 태그로 버전 고정
profiles: [common, java-spring, payment, team]
mode: diff
include: ["src/main/**"]
exclude: ["**/generated/**", "**/*Dto.java"]
overrides:
  JPA-003: { severity: MEDIUM }
  disable: [STYLE-010]
limits:
  max_diff_lines: 1500      # 초과 시 요약 리뷰로 전환
gate:
  fail_on: HIGH             # HIGH 위반 시 체크 실패 (선택)
```

**룰 병합 순서**: common → 스택 → 도메인 → team → 레포 `overrides` (뒤가 앞을 덮어씀)

---

## 7. 핵심 인터페이스 (Java 초안)

### 7.1 스택 어댑터
```java
public interface StackAdapter {
    String id();                               // "gradle-spring"
    boolean detect(Path repoRoot);
    StepResult build(Workspace ws);
    List<Finding> lint(Workspace ws);          // Checkstyle/PMD/SpotBugs → Finding 변환
    TestResult test(Workspace ws);
    CoverageReport coverage(Workspace ws);     // JaCoCo
}
```

### 7.2 LLM 클라이언트
```java
public interface LlmClient {
    ReviewResult review(ReviewRequest request);
    TestGenResult generateTests(TestGenRequest request);
}

// 1~2단계: ClaudeCodeCliClient
//   ProcessBuilder로 `claude -p "/review" --output-format json` 호출
//   --allowedTools 로 허용 도구 제한, agents/commands 구조 그대로 활용
// 3단계 이후: AnthropicApiClient
//   Anthropic Java SDK로 직접 호출 (세밀한 제어, 재시도, 병렬 처리)
```
> 참고: Claude Agent SDK는 Python/TypeScript만 지원하므로, Java 엔진에서는 위 두 방식을 사용한다.

### 7.3 Findings 모델 (모든 결과의 표준 형식)
```java
public record Finding(
    String ruleId,        // "JPA-003"
    Severity severity,
    Source source,        // LLM, CHECKSTYLE, PMD, SPOTBUGS, TEST
    String file,
    int line,
    String message,
    String evidence,      // 근거 코드 조각 (필수)
    String suggestion,
    String fingerprint    // 중복 제거용 해시
) {}
```

```json
{
  "ruleId": "JPA-003",
  "severity": "HIGH",
  "source": "LLM",
  "file": "src/main/java/com/example/order/OrderService.java",
  "line": 87,
  "message": "주문 목록 순회 중 items 지연로딩으로 N+1 발생",
  "evidence": "orders.forEach(o -> o.getItems().size());",
  "suggestion": "fetch join 또는 @EntityGraph 사용",
  "fingerprint": "sha1(ruleId+file+normalizedEvidence)"
}
```

**규칙: `ruleId`와 `evidence`가 없는 LLM 지적은 렌더링 전에 자동 폐기한다.**

### 7.4 렌더러
```java
public interface FindingsRenderer {
    void render(ReviewReport report, RenderContext ctx);
}
// PrCommentRenderer / MarkdownRenderer / SarifRenderer
```
SARIF 출력은 GitHub Code Scanning 탭에 업로드할 수 있어 범용성이 높다.

---

## 8. 팀 PR 운영 시 소음 관리

- **중복 방지**: fingerprint로 이미 단 코멘트는 재작성하지 않음
- **요약 코멘트 1개 유지**: 새로 달지 않고 기존 코멘트를 갱신
- **대규모 diff 제한**: `max_diff_lines` 초과 시 요약 리뷰로 전환
- **오탐 피드백 루프**: 코멘트에 👎 반응 → 오탐으로 수집 → 오탐률 높은 규칙 ID 주기적 튜닝
- **인라인 코멘트는 변경된 라인에만** 작성

---

## 9. 테스트 품질 검증

생성된 테스트는 "통과"만으로 판단하지 않는다.
- **JaCoCo**: 변경 코드 커버리지 증가분
- **PIT (mutation testing)**: 뮤테이션 스코어로 테스트가 실제 결함을 잡는지 검증
- 생성 테스트가 컴파일 실패 / 기존 테스트를 깨뜨리면 폐기하고 실패 사유만 리포트

---

## 10. 로드맵

| 단계 | 내용 | 범용화 준비 |
|---|---|---|
| 1 | 룰팩 저장소 + Gradle/Spring 어댑터 + diff 모드 리뷰 + PR 코멘트 | Findings 스키마 확정 |
| 2 | tester 에이전트(변경 클래스 대상 테스트 생성·실행), 커버리지 게이트 | 스택 어댑터 인터페이스 정리 |
| 3 | 오탐 피드백 수집, 룰 튜닝, SARIF 출력, Java SDK 전환 검토 | CLI 어댑터 + full 모드 |
| 4 | 필요 시 다른 스택 어댑터, 외부 레포 분석, 대시보드(Spring Boot) | — |

**4단계 현황 (검토 완료):**
- **외부 레포 분석(full 모드)** — 이미 구현돼 있다. `mode: full`이면 diff 스코프 필터를
  적용하지 않고 `include`/`exclude`에 걸리는 전체 소스를 리뷰한다 (`ReviewRunner`가
  `ReviewMode.DIFF`일 때만 diff 스코프를 적용하는 구조라, `full`은 1단계부터 이미 동작함).
- **다른 스택 어댑터(Maven 등) / 대시보드** — **보류.** 둘 다 "필요 시"라는 조건부 항목이고
  지금은 실제로 필요한 대상 레포(다른 빌드 스택)나 대시보드를 볼 사용자가 없다. 구체적 수요
  없이 만들면 검증 못 하는 추측성 코드가 될 위험이 커서, 실제 필요가 생길 때(다른 스택을 쓰는
  파일럿 레포가 생기거나, PR을 넘어선 추이를 봐야 하는 시점) 다시 착수한다.

---

## 11. 개발 환경

- **IDE**: IntelliJ IDEA
  - 순수 CLI 엔진: Community로 충분
  - 향후 대시보드/Webhook 서버를 Spring Boot로 올릴 계획이면 Ultimate 권장
- **플러그인**: Claude Code (JetBrains), CheckStyle-IDEA, PMD, SpotBugs
- **Claude Code**: IntelliJ 내장 터미널에서 실행
- **Docker Desktop**: 샌드박스 이미지 빌드·실행
- **JDK**: 17 이상 권장 (record, sealed interface 활용)

---

## 12. 1단계 작업 체크리스트

### 룰팩 저장소
- [x] `review-rulepack` 저장소 생성, `v0.1.0` 태그 규칙 정의 (`v0.2.0`까지 진행)
- [x] `CLAUDE.md` 작성 (리뷰 톤, 출력은 Findings JSON만, 근거 없는 지적 금지)
- [x] `rules/common/security.md` 규칙 5개 작성
- [x] `rules/java-spring/jpa.md`, `layering.md` 규칙 각 5개 작성
- [x] `rules/domain/payment.md` 규칙 작성
- [x] `agents/reviewer.md`, `commands/review.md` 작성 (+ `agents/tester.md`, `commands/gen-test.md` 2단계에서 추가)
- [x] `schema/findings.schema.json`, `schema/review-config.schema.json` 작성

### 엔진 저장소
- [x] `ai-review-engine` 단일 Spring Boot 프로젝트 생성 (web-application-type: none, 4.3 참고)
- [x] 패키지 구조(core/llm/stack/adapter/renderer) 생성 + `ArchitectureTest` 작성
- [x] 루트에 `docs/`, `rulepack/`, `CLAUDE.md` 배치
- [x] `core`: `.review.yml` 로딩 + 룰 병합 로직 + 단위 테스트
- [x] `core`: `Finding` 모델, fingerprint 생성, 근거 없는 지적 필터
- [x] `stack-gradle-spring`: detect / build / lint 구현 (+ test/coverage/mutate 2단계에서 추가)
- [x] `llm`: `ClaudeCodeCliClient` (ProcessBuilder + JSON 파싱 + 타임아웃)
- [x] `adapter-github`: PR diff 수집, 변경 라인 매핑
- [x] `renderer-pr-comment`: 인라인 코멘트 + 요약 코멘트 갱신

### CI 연동
- [x] 샌드박스 Docker 이미지 작성 — 로컬 Docker Desktop에서 빌드·실행 검증(BUILD 네트워크 차단 포함), 실제 GitHub Actions 파일럿(harrison-kook/sample-target)에서도 매 워크플로 실행마다 이미지를 새로 빌드해 검증됨
- [x] 파일럿 레포 1개에 `.review.yml` + PR 워크플로 적용 — `harrison-kook/sample-target` PR #1에서 BUILD(네트워크 차단 빌드/린트/baseline 테스트)와 REPORT(LLM 리뷰, diff 스코프 필터, 인라인 PR 코멘트, ruleId/fingerprint 마커) 워크플로 둘 다 end-to-end 성공 확인. 이 과정에서 실제로 발견·수정한 버그: gradlew 실행 비트 누락 방어, 샌드박스 컨테이너 간 gradle 데몬/VFS 락 경합(`--no-daemon --no-watch-fs`), `workflow_run` 아티팩트 다운로드에 필요한 `actions: read` 권한 누락
- [x] API 키는 Actions secret으로만 관리 — `ANTHROPIC_API_KEY`를 리포지토리 시크릿으로 등록, BUILD 워크플로(fork PR도 트리거되는 `pull_request`)에는 시크릿을 전혀 넘기지 않고 REPORT 워크플로(`workflow_run`, 시크릿 접근 가능)에서만 사용하도록 트리거 분리는 적용됨. 다만 **실제 fork PR로 이 분리가 의도대로 막아주는지는 아직 검증 안 됨** (동일 계정 브랜치 PR로만 테스트)
- [ ] 2주간 파일럿 운영 후 오탐률 측정 → 룰 튜닝 — 운영 기간이 필요한 항목이라 아직 시작 전

---

## 13. 미결 사항 (작업하며 결정)

- [x] `gate.fail_on` 을 처음부터 켤지, 파일럿 기간엔 경고만 할지
      → **파일럿 기간엔 경고만.** `fail_on`을 비워두면(현재 기본 동작) PR을 막지 않고 인라인
      코멘트만 남긴다. 오탐률을 실전 데이터로 검증하기 전에 체크를 막으면 신뢰를 잃기 쉽다.
      단, `min_coverage_delta`/`min_mutation_score`는 설정하면 그 즉시 강제된다 — 이 둘은
      LLM 판단이 아니라 결정적 도구(JaCoCo/PIT) 결과라 오탐 리스크가 없다.
- [x] 리뷰 비용 상한 (PR당 토큰/시간 제한)
      → **지금은 타임아웃만으로 충분.** `ReviewRequest`(5분)/`TestGenRequest`(10분) 타임아웃이
      이미 있고, 실비용 분포를 아직 모르는 상태에서 하드 캡을 코드로 넣는 건 근거 없는 숫자가
      된다. 파일럿 운영하며 `total_cost_usd` 데이터를 모은 뒤 재검토.
- [x] 룰팩 저장소 접근 권한 (팀 전체 수정 vs 리뷰어 승인제)
      → **지금은 팀 전체 write, 팀 규모가 커지면 브랜치 보호 + PR 승인제로 전환.** 1인
      파일럿 단계에서 승인제를 걸면 튜닝 반복 속도만 늦춘다.
- [x] 리포트 보관 위치 (Actions artifact / 별도 저장소 / DB)
      → **지금처럼 Actions artifact + PR 인라인 코멘트로 충분.** PR 코멘트는 PR과 함께 영구
      보관되고 SARIF는 Code Scanning 탭에 쌓인다. 팀 규모가 커져 PR을 넘어선 추이 분석·
      대시보드가 필요해지면(4단계) 별도 저장소/DB를 재검토한다.
