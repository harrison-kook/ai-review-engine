# E2E(Playwright) CI 연동 설계

> 상태: **Phase A·B·C 코드는 전부 완료(2026-10-19), 실제 PR 파일럿은 아직.** 샌드박스 이미지
> 파일럿 검증(Phase A, 10장) → 엔진 코드 구현(Phase B, 11장) → marketplace 쪽 설정(Phase C,
> 12장)까지 끝냈다. `.review.yml`에 `e2e:`가 켜져 있고 필요한 스크립트/프로파일도 다
> 커밋됐다. **다만 실제 GitHub Actions PR로 전체 파이프라인을 끝까지 돌려본 적은 아직
> 없다**(12.3) — 로컬/CLI 검증과 실제 러너 사이에 남아 있는 리스크(8장)가 거기서 드러난다.

## 1. 배경

marketplace의 `frontend/e2e/`에 Playwright 테스트가 생겼다. 단위 테스트/정적분석으로는
못 잡는 버그(옵션 선택 시 가격 미반영, 상태 전환 버튼 누락 등)를 실제로 화면을 띄워 클릭해보고
잡아낸다. 지금은 로컬에서 수동으로만 돈다 — 이 문서는 이걸 `ai-review-engine`의 PR 파이프라인에
자동으로 끼워 넣는 방법을 설계한다.

## 2. 핵심 제약 재검토: 네트워크 격리

`review-build.yml`의 "Build & lint" 스텝은 `--network none`으로 돈다. `docker/README.md`에
적힌 이유는 **"시크릿이 대상 레포의 신뢰할 수 없는 코드에 노출되지 않게"** 하는 것이다 —
코드 실행 자체를 막는 게 목적이 아니다(이미 `gradlew build/check`가 PR 코드를 컴파일·실행한다).

이 문서에서 가장 중요한 사실 하나: **`--network none`은 컨테이너 "밖"과의 통신만 막는다.
같은 컨테이너 안에서 `127.0.0.1`로 통신하는 건 영향받지 않는다.** 즉:

- 지금처럼 `docker-compose`로 MySQL/Redis를 **별도 컨테이너**로 띄우는 방식은 CI 샌드박스에서
  못 쓴다(컨테이너 간 통신도 막힌다).
- 하지만 MySQL/Redis/백엔드/프런트/브라우저를 전부 **같은 컨테이너 안의 프로세스**로 띄우면
  네트워크 격리를 유지한 채로 E2E가 가능하다.
- 예외: 토스 결제위젯처럼 **진짜 외부 SaaS를 호출하는 화면**은 네트워크가 막혀 있으면 원천적으로
  테스트할 수 없다 — 1차 범위에서는 제외한다(아래 7장).

결론: E2E는 시크릿이 필요 없고 대상 레포 코드를 "실행"만 하므로, 기존 **build 단계**(시크릿
없음, 네트워크 차단)에 속한다. report 단계(LLM 리뷰)는 애초에 Read/Grep/Glob으로 코드를 읽기만
하니 여기엔 자리가 없다.

## 3. 샌드박스 이미지 확장

`docker/Dockerfile`(현재 `eclipse-temurin:21-jdk` 베이스 + git/curl/nodejs/npm)에 추가:

| 추가 항목 | 용도 |
|---|---|
| `mysql-server`(실제 MySQL, **MariaDB 아님** — 10장 참고) | 컨테이너 안에서 `mysqld`를 로컬 프로세스로 기동 |
| `redis-server` | 마찬가지로 로컬 프로세스 |
| Playwright 브라우저(Chromium) + OS 의존성 | 이미지 빌드 시점에 `npx playwright install --with-deps chromium`으로 미리 구워둔다 — 네트워크 차단 단계에서 매번 받을 수 없다 |

이미지가 눈에 띄게 커진다 — **실측: `eclipse-temurin:21-jdk` 베이스 대비 +4GB대, 최종 이미지
~4.6GB** (Chromium+X11/그래픽 라이브러리 의존성이 대부분). 엔진 자체 CI(`./gradlew bootJar` +
이미지 빌드) 시간에 영향을 주므로 레이어 캐싱을 신경 써야 한다.

## 4. `.review.yml` 스키마 확장안

lint/test는 "대상 레포에 이미 있는 설정을 그대로 실행"하는 원칙이지만, E2E는 **"무엇을 어떻게
기동해야 하는지" 자체를 엔진이 알 방법이 없다** — Gradle/Node 생태계처럼 보편적인 관례가 없기
때문이다. 그래서 E2E만은 명시적 opt-in 설정이 필요하다:

```yaml
e2e:
  enabled: true
  working_directory: frontend        # test_command를 실행할 디렉터리
  services:                          # 순서대로 기동, 각자 healthcheck 통과까지 대기
    - name: backend
      start: "./gradlew bootRun --args='--spring.profiles.active=ci-e2e'"
      healthcheck_url: "http://localhost:8080/actuator/health"
      timeout_seconds: 120
    - name: frontend
      start: "npm run build && npm run start"
      working_directory: frontend
      healthcheck_url: "http://localhost:3000"
      timeout_seconds: 60
  test_command: "npm run test:e2e"
  timeout_seconds: 300               # test_command 자체의 타임아웃
```

`Gate`에도 대응 필드를 하나 추가한다: `gate.e2e_fail_on_any`(boolean, 기본 false) — 켜면 E2E
실패가 전체 게이트를 막는다. 커버리지/뮤테이션 게이트처럼 "설정 안 하면 경고만" 원칙을 따른다.

## 5. 파이프라인 변경안

**`StackAdapter`를 확장하지 않는다.** `test()`/`coverage()`는 "레포 하나, 스택 하나"를
전제하지만, E2E는 백엔드(Gradle)+프런트(Node)를 **동시에** 기동해야 해서 어느 한 어댑터의
책임이 아니다. 대신 `ReviewPipeline`에 별도 단계를 추가한다:

1. `ReviewConfig.e2e()`가 없거나 `enabled: false`면 스킵(기존 StackAdapter들의 "설정 없으면
   결과 없음" 원칙과 동일).
2. `services`를 선언 순서대로 백그라운드 프로세스로 기동 → 각 `healthcheck_url`을 타임아웃까지
   폴링 → 하나라도 실패하면 이미 뜬 프로세스를 정리하고 E2E 자체를 실패 처리(코드 버그로 보지
   않고 "환경 문제"로 별도 표시 — false positive 방지).
3. `test_command`를 `working_directory`에서 실행.
4. 성공/실패 무관하게 **항상** 띄운 프로세스를 정리한다(좀비 프로세스/포트 점유 방지 — 같은
   컨테이너를 재사용하지 않는 `docker run --rm` 모델이라 크리티컬하진 않지만, 로컬 실행 시
   중요하다).
5. 리포트 파싱: Playwright `junit` 리포터로 표준 JUnit XML을 내게 하면(marketplace의
   `playwright.config.ts`에 `reporter: [['junit', {outputFile: '.ai-review-engine/e2e-junit.xml'}]]`
   (파일명은 구현체 `E2eRunner`의 고정 경로를 따른다 — 11장 참고)
   추가) **기존 `JUnitReportParser`를 그대로 재사용**할 수 있다 — 새 파서가 필요 없다.
6. 결과는 `TestResult`와 분리된 별도 모델(`E2eResult` 등, source="e2e")로 둔다 — 단위 테스트와
   섞이면 "왜 테스트가 100개에서 갑자기 4개 늘었지" 같은 혼란을 만든다.

## 6. 리포팅

`PrCommentRenderer`/`MarkdownReportWriter`에 E2E 섹션을 추가한다 — "✅ E2E 4/4 통과" 또는
실패 시 어떤 스펙이 깨졌는지 요약(JUnit testcase의 className/methodName/failureMessage는
이미 `TestCaseResult`에 다 있다).

## 7. 대상 레포(marketplace) 쪽 준비물

엔진 작업과 별개로, marketplace 쪽에서 미리 해둬야 하는 것:

- **`application-ci-e2e.yaml` 프로파일**: DB를 컨테이너 내부 로컬 `127.0.0.1:3306`으로,
  `pg.provider=mock`, Redis도 로컬. 시크릿 없이 뜰 수 있어야 한다. **`spring.docker.compose.enabled=false`도
  반드시 포함해야 한다** — 안 그러면 Spring Boot가 레포의 `docker-compose.yml`을 보고 알아서
  관리하려 들어서(샌드박스 안에 docker 자체가 없으니) 기동이 멈춘다(10장에서 실제로 걸림).
- **카테고리 등 최소 시드 데이터를 Flyway 마이그레이션으로 고정**: 지금 E2E 픽스처
  (`findLeafCategoryId()`)는 "로컬 개발 DB에 이미 쌓여 있는 카테고리"에 암묵적으로 의존한다.
  CI는 매번 깨끗한 DB로 시작하므로, 카테고리 같은 필수 기준 데이터는 Flyway 마이그레이션에
  포함시켜야 한다 — **이게 지금 숨어 있는 전제조건이라 가장 먼저 처리해야 한다.**
- **`playwright.config.ts`에 `junit` 리포터 추가** (5장 참고).
- **결제 위젯처럼 외부망이 필요한 화면은 E2E 스코프에서 제외**하거나 모킹한다(현재 추가된
  3개 스펙은 모두 외부망이 필요 없어서 해당 없음 — 앞으로 결제 플로우를 E2E로 추가할 때
  주의해야 할 지점).

## 8. 리스크 / 미결 사항

- **완전 무설정 범용화는 불가능하다.** lint/test(언어 생태계 표준 명령만 알면 됨)와 달리
  E2E는 "이 레포를 어떻게 기동하는지"가 레포마다 다르므로, `.review.yml`에 명시적으로
  적어야 한다 — "범용 엔진"이되 "설정 없이 자동 감지"는 아니다.
- **이미지 크기·빌드 시간 증가** — 실측 완료(10장): 베이스 대비 +4GB대, 최종 ~4.6GB.
- **PR마다 몇 분 추가될 수 있다** — 실측 완료(10장): 백엔드 단독 기동만 **콜드 스타트 130초대**
  (Gradle single-use daemon + Flyway + Hibernate + 풀 컨텍스트, MySQL/Redis도 같이 콜드
  스타트 중이라 자원 경쟁 포함). 프런트 빌드·기동 + Playwright 실행까지 합치면 PR마다
  수 분 단위로 늘어날 걸 전제해야 한다. `gate.e2e_fail_on_any`를 켜기 전에 실측 데이터를
  더 쌓는다(기존 `gate.fail_on` 전환 때와 같은 방침, 설계서 13장). **설계안의
  `healthcheck timeout_seconds: 120`은 너무 타이트하다 — 백엔드만 180~240초 권장.**
- **프로세스 관리 견고성** — 여러 백그라운드 프로세스를 띄우고 정리하는 로직은 StackAdapter의
  "명령 하나 실행하고 끝"보다 훨씬 많은 실패 모드(포트 충돌, 기동 순서, 타임아웃 후 정리 실패)를
  갖는다. 파일럿 레포(marketplace) 하나로 충분히 안정화한 뒤 다른 레포로 확장한다.
- **외부망이 필요한 플로우는 구조적으로 배제된다** — 화이트리스트 네트워크(특정 도메인만
  허용)는 조사했지만 이번 설계에는 포함하지 않았다(복잡도 대비 marketplace에 당장 필요하지
  않음).

## 9. 단계적 도입 제안

| 단계 | 내용 |
|---|---|
| A | 샌드박스 이미지에 MySQL+Redis+Playwright 추가, marketplace 하나로 **수동** `docker run` 파일럿 검증(워크플로 연동 전) — **완료(10장)** |
| B | `.review.yml` e2e 스키마 + `ReviewPipeline` e2e 단계 구현, PR 코멘트에 결과만 노출(게이트는 끔) — **완료(11장)** |
| C | marketplace에 카테고리 Flyway 시드 추가, `application-ci-e2e.yaml` 정리(10장에서 발견한 `spring.docker.compose.enabled=false` 포함) |
| D | 안정화 확인 후 `gate.e2e_fail_on_any` 전환 여부 결정 |

## 10. Phase A 파일럿 결과 (2026-10-19)

`docker/Dockerfile`에 `mysql-server`/`redis-server`/Playwright Chromium을 추가하고, marketplace
레포를 `/workspace`로 마운트한 `--network none` 컨테이너 안에서 MySQL→Redis→
`./gradlew bootRun`까지 수동으로 끝까지 돌려서 **백엔드가 실제로 뜨는 것까지 확인했다**
(`Started MarketplaceApplication in 130.597 seconds`, `Tomcat started on port 8080`).
핵심 결론(2장)이 맞았다는 뜻이다 — 네트워크 격리를 유지한 채 DB+백엔드를 한 컨테이너 안에서
띄울 수 있다. 과정에서 예상 못 한 문제 3개를 만났고 전부 해결했다 — 다음 구현자가 똑같이
겪지 않도록 기록한다.

### 10.1 MariaDB는 안전한 대체재가 아니다

처음에는(가볍다는 이유로) `mariadb-server`로 시작했다. MySQL 프로토콜 호환이라 괜찮을 거라
생각했는데, Hibernate 7.x가 JDBC 메타데이터로 Dialect를 자동 판별하는 과정에서
`information_schema`의 `RESERVED` 컬럼(실제 MySQL 8.0+에 있는 예약어 테이블용 컬럼)을
조회하다가 `SQLSyntaxErrorException: Unknown column 'RESERVED' in 'WHERE'`로 죽는다 — MariaDB가
이 컬럼을 안 갖고 있어서다. **대상 레포가 운영에서 `mysql:8`(프로젝트의 `docker-compose.yml`
기준)을 쓴다면 샌드박스도 반드시 진짜 `mysql-server`를 써야 한다.** Ubuntu 26.04(resolute)
기준 apt 후보는 `8.4.11-0ubuntu0.26.04.1`.

### 10.2 `--skip-name-resolve` 없으면 새로 만든 유저가 거부당한다

`CREATE USER 'market'@'%' IDENTIFIED BY 'market'; GRANT ALL ... TO 'market'@'%';`까지 정상
실행되는데도 `mysql -h127.0.0.1 -umarket -pmarket`가 `Access denied for user
'market'@'localhost' (using password: YES)`로 거부됐다. 원인: `mysql.user` 테이블에 기본으로
깔리는 익명 유저 행(`''@'localhost'`, `''@<컨테이너 호스트네임>`)이 호스트 이름 역질의(reverse
DNS) 결과와 매칭되면서 실제 유저보다 먼저 선택돼버리는, MySQL/MariaDB의 고전적인 "유령
익명유저" 함정이다. `mysqld_safe --skip-name-resolve`로 역질의 자체를 끄면 IP 리터럴로만
매칭해서 해결된다.

### 10.3 Spring Boot의 docker-compose 자동 지원이 기동을 막는다

`local` 프로파일로 그냥 띄우면 `DockerComposeLifecycleManager`가 레포 루트의
`docker-compose.yml`을 보고 알아서 관리하려 든다 — 샌드박스 안에는 Docker 자체가 없으니
여기서 조용히 멈춘다(에러 로그 없이 헬스체크 타임아웃만 발생해서 원인 파악이 제일 오래
걸렸다). `--spring.docker.compose.enabled=false`를 켜면 해결된다 — Phase C에서
`application-ci-e2e.yaml`에 기본값으로 박아둬야 한다.

### 10.4 실측 수치

- 최종 이미지 크기: **~4.6GB** (`eclipse-temurin:21-jdk` 베이스 대비 +4GB대, Chromium과 그
  X11/그래픽 의존성이 대부분).
- 백엔드 콜드 스타트(Gradle single-use daemon 포함, MySQL/Redis도 같은 컨테이너에서 동시에
  콜드 스타트 중인 상태): **약 130초.** 첫 헬스체크 폴링 budget(180초)을 거의 다 쓴다 — 실제
  구현에서는 백엔드 healthcheck timeout을 180~240초로 넉넉히 잡아야 한다(8장 갱신).
- Gradle 의존성 캐시를 named volume으로 재사용하면 컴파일 자체는 빠르다(`4 actionable tasks:
  1 executed, 3 up-to-date`) — 느린 건 컴파일이 아니라 Spring Context 전체 기동(Flyway 9개
  마이그레이션 + Hibernate 초기화 + 전체 빈 생성)이다.

### 10.5 다음(Phase B)에서 그대로 쓸 수 있는 것

- `docker/Dockerfile`: `mysql-server`/`redis-server`/Playwright 설치 스텝 (이번 파일럿에서
  실제로 빌드·검증됨, 바로 머지 가능).
- mysqld 기동 커맨드: `mysqld_safe --skip-name-resolve` + `CREATE DATABASE`/`CREATE USER`
  스크립트 — `.review.yml`의 `e2e.services` 항목 하나로 그대로 옮길 수 있는 형태다.
- `--spring.docker.compose.enabled=false`는 marketplace의 `application-ci-e2e.yaml`(Phase C)에
  기본 포함.

## 11. Phase B 구현 결과 (2026-10-19)

엔진 코드에 E2E 실행을 실제로 구현했다. 설계(5장)에서 정한 방향을 그대로 따랐다 —
`StackAdapter`를 확장하지 않고 build 단계에 별도로 끼워 넣었다.

### 11.1 추가/변경된 파일

- `core/config/E2eConfig.java` (신규) — `.review.yml`의 `e2e:` 섹션. `enabled`가 false면 완전히
  스킵(lint()와 같은 원칙).
- `core/pipeline/E2eResult.java` (신규) — `TestResult`와 의도적으로 분리. `envFailureReason`으로
  "서비스가 안 떴음"과 "테스트가 실패함"을 구분한다.
- `core/e2e/E2eRunner.java` (신규) — 서비스 기동(백그라운드 프로세스) → healthcheck 폴링 →
  `test_command` 실행(`ProcessExecutor` 재사용) → JUnit XML 파싱 → `finally`에서 항상 프로세스
  정리.
- `core/config/Gate.java` — `e2eFailOnAny` 필드 추가(기본 false, Phase B 방침대로 "경고만").
- `core/config/ReviewConfig.java`/`ReviewConfigLoader.java` — `e2e` 필드 + YAML 파싱 추가.
- `core/pipeline/ReviewPipeline.java` — `runBuildPhase()`가 e2e를 실행하고
  `build/e2e-result.json`에 쓴다.
- `ReviewRunner.java` — REPORT/ALL에서 결과를 읽어 `MarkdownReportWriter`/`PrCommentRenderer`에
  넘기고, `gate.e2e_fail_on_any`가 켜져 있을 때만 전체 게이트에 반영한다.
- `renderer/markdown/MarkdownReportWriter.java`, `renderer/prcomment/PrCommentRenderer.java` —
  E2E 섹션 추가. `PrCommentRenderer`는 생성자를 오버로드해서(`E2eResult` 없는 기존 1-arg 버전은
  `E2eResult.DISABLED`로 위임) 기존 호출부를 안 건드렸다.

### 11.2 설계와 다르게 간 것 하나 — JUnit 파서 재사용 안 함

5장에서는 "기존 `JUnitReportParser`를 그대로 재사용"하려 했는데, 그 클래스가
`stack.gradlespring` 패키지(구현 패키지)에 있어서 그대로 가져오면
`ArchitectureTest.core_does_not_depend_on_implementation_packages`(core는 구현 패키지에
의존하면 안 된다)를 깬다. `core.e2e.E2eRunner`에 거의 같은 파싱 로직을 독립적으로 다시
구현했다 — JUnit5/Gradle 전용 보정(메서드명 끝 `()` 제거)은 Playwright 출력에 안 맞아서 빼고
가져왔다. 작은 중복이지만 이 레포의 기존 관례(EsLintReportParser/JestJsonReportParser도 각자
`relativize`를 따로 들고 있음)와 일치한다.

### 11.3 기존 불변식을 깰 뻔한 지점

`runBuildPhase()`는 원래 **`.review.yml`이 아예 없어도 성공해야 한다**는 불변식이 있다
(`buildPhaseNeverTouchesRulepackOrLlm` 테스트가 명시적으로 검증 — "시크릿/룰팩 불필요" 원칙).
E2E 설정도 결국 `.review.yml`에서 읽어야 해서 처음엔 무조건 `configLoader.load(...)`를
호출했다가 이 테스트들을 깼다. 설정 로드를 try/catch로 감싸 실패하면 `E2eConfig.DISABLED`로
폴백하도록 고쳐서(`loadE2eConfigOnly`), 기존 불변식을 유지한 채 e2e만 선택적으로 켜지게
했다 — `ReviewRunner.loadGateOnly()`가 TESTRUN 단독 실행에서 이미 쓰던 것과 같은 방어
패턴이다.

### 11.4 검증

- 단위 테스트 10여 개 추가(`E2eConfig`/`Gate` 파싱, `E2eRunner` 오케스트레이션 3종 — 비활성/
  환경 실패/정상 실행, `MarkdownReportWriter`/`PrCommentRenderer` E2E 섹션 렌더링). 전체
  스위트 157건 통과.
- **실제 빌드한 jar로 CLI 엔드투엔드 확인**: `package.json` + `e2e.enabled: true`(서비스 없이
  `test_command`만)인 최소 픽스처 레포에 `--phase=build`를 직접 실행해서 `build/e2e-result.json`이
  기대한 그대로(`{"enabled":true,"success":true,...}`) 쓰이는 것, 콘솔에 `e2e: 0/0 통과`가
  찍히는 것까지 확인했다 — 유닛 테스트의 모킹을 거치지 않은 실제 배선 검증이다.

### 11.5 Phase C에서 바로 해야 할 것

- marketplace `.review.yml`에 `e2e:` 섹션 추가(4장 예시 그대로, healthcheck timeout은 8장
  갱신대로 180~240초).
- marketplace `frontend/playwright.config.ts`에 `reporter: [['junit', {outputFile:
  '.ai-review-engine/e2e-junit.xml'}], ...]` 추가(파일명이 `e2e-junit.xml`인 것에 주의 —
  10장 설계 당시 적었던 `playwright-junit.xml`에서 구현 중 이름이 바뀌었다).
- marketplace `application-ci-e2e.yaml`: `spring.docker.compose.enabled=false` 포함, DB/Redis는
  `127.0.0.1`.
- 카테고리 등 최소 시드를 Flyway 마이그레이션으로 고정(7장에서 이미 지적한 전제조건).

## 12. Phase C 구현 결과 (2026-10-19, marketplace 레포)

marketplace 쪽 준비물을 전부 추가했다. 7장/11.5에서 적은 계획과 한 군데 다르게 갔다 —
**카테고리 시드를 Flyway 마이그레이션으로 넣지 않았다.** 마이그레이션은 로컬/dev/운영 DB에
전부 똑같이 적용되므로, 테스트용 카테고리를 거기 넣으면 운영 DB까지 오염된다. 대신
`frontend/e2e/global-setup.ts`가 카테고리가 하나도 없을 때만(= CI의 깨끗한 DB) ADMIN 권한으로
하나 만들어 두도록 했다(`ensureLeafCategoryExists`) — 로컬 개발 DB처럼 이미 카테고리가 있으면
아무것도 안 한다.

### 12.1 추가/변경된 파일 (marketplace)

- `.review.yml` — `e2e:` 섹션 추가. `backend` 서비스는 `working_directory: "."`로 레포 루트를
  가리키게 명시적으로 오버라이드했다(최상위 `e2e.working_directory: frontend`가 기본값이라,
  안 그러면 백엔드도 `frontend/`에서 기동하려 든다).
- `scripts/ci-e2e-backend.sh` (신규) — MySQL(`mysqld_safe --skip-name-resolve`, 10.2 함정
  그대로 적용) → DB/유저 생성 → Redis → `gradlew bootRun`을 한 스크립트로 묶었다. `.review.yml`
  에서 `sh scripts/ci-e2e-backend.sh`로 호출한다(실행 비트에 의존하지 않도록 `sh`를 명시).
- `src/main/resources/application-ci-e2e.yaml` (신규) — DB/Redis `127.0.0.1`,
  `spring.docker.compose.enabled=false`(10.3 함정), 토스 결제위젯 클라이언트 키는 토스
  공개 데모 키(시크릿 아님 — build 단계는 애초에 시크릿을 안 받으므로 이것만 쓸 수 있다).
  `pg.provider`는 `application.yaml` 기본값(mock)을 그대로 쓴다.
- `frontend/playwright.config.ts` — `process.env.CI`일 때만 `junit` 리포터를 추가해
  `.ai-review-engine/e2e-junit.xml`을 낸다(로컬 실행은 그대로 html+list).
- `frontend/e2e/fixtures/api.ts`/`global-setup.ts` — `ensureLeafCategoryExists` 추가(위 참고).
- `.gitattributes` — `*.sh text eol=lf` 추가(Windows에서 커밋해도 CRLF로 안 바뀌게, `gradlew`와
  같은 이유).

### 12.2 검증

- `ai-review-engine`의 `ReviewConfigLoader`로 marketplace의 실제 `.review.yml`을 직접
  파싱해서 기대한 구조(서비스 2개, `working_directory` 오버라이드, 타임아웃 값 등)와 정확히
  일치하는 것을 확인했다(임시 테스트로 확인 후 삭제 — 커밋 안 함).
- `scripts/ci-e2e-backend.sh`는 Phase A 파일럿에서 이미 실제로 돌려본 스크립트와 로직이
  같다(마리아DB 대신 MySQL, `--skip-name-resolve`, `docker-compose` 자동지원 끄기까지 전부
  10장에서 검증된 내용 그대로 옮긴 것) — 다만 `.review.yml`을 통한 전체 파이프라인 자체를
  실제 GitHub Actions PR로 끝까지 돌려보지는 않았다(다음 항목).

### 12.3 아직 안 한 것 — 실제 PR 파일럿

이 레포 쪽 설정은 다 됐지만, **실제로 PR을 열어서 전체 파이프라인이 끝까지 성공하는지는 아직
확인 전이다.** 이게 Phase A/B/C 전부를 통틀어 처음으로 "진짜 GitHub Actions 러너 + 네트워크
차단 + 폴리글랏 런타임"이 전부 합쳐지는 지점이라, 로컬/CLI 검증과 달리 터질 수 있는 지점이
여럿 남아 있다(예: GitHub Actions 러너의 디스크/메모리 제약, 이미지 pull 시간, Playwright
버전과 엔진 이미지에 미리 구워둔 Chromium 리비전이 어긋나는 문제 — 8장에 적어둔 미결
사항). `gate.e2e_fail_on_any`는 계속 꺼둔 채로, 테스트 PR을 하나 열어서 실제로 관찰하는 게
다음 단계다.
