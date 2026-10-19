# E2E(Playwright) CI 연동 설계

> 상태: **설계 단계 — 아직 구현 안 됨.** marketplace에 Playwright E2E 스모크 테스트
> (2026-10-16 추가)를 실제로 PR마다 자동 실행하려면 무엇이 필요한지 정리한다.

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
| `mysql-server` 또는 `mariadb-server` | 컨테이너 안에서 `mysqld`를 로컬 프로세스로 기동 |
| `redis-server` | 마찬가지로 로컬 프로세스 |
| Playwright 브라우저(Chromium) + OS 의존성 | 이미지 빌드 시점에 `npx playwright install --with-deps chromium`으로 미리 구워둔다 — 네트워크 차단 단계에서 매번 받을 수 없다 |

이미지가 눈에 띄게 커진다(수백MB~1GB대 예상) — 엔진 자체 CI(`./gradlew bootJar` + 이미지 빌드)
시간에 영향을 주므로 레이어 캐싱을 신경 써야 한다.

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
   `playwright.config.ts`에 `reporter: [['junit', {outputFile: '.ai-review-engine/playwright-junit.xml'}]]`
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
  `pg.provider=mock`, Redis도 로컬. 시크릿 없이 뜰 수 있어야 한다.
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
- **이미지 크기·빌드 시간 증가** — MySQL+Redis+Chromium 추가 비용을 실측해야 한다.
- **PR마다 몇 분 추가될 수 있다** — Flyway+시드+앱 기동+브라우저 테스트는 지금 빌드/린트보다
  훨씬 느리다. `gate.e2e_fail_on_any`를 켜기 전에 실측 데이터를 먼저 쌓는다(기존
  `gate.fail_on` 전환 때와 같은 방침, 설계서 13장).
- **프로세스 관리 견고성** — 여러 백그라운드 프로세스를 띄우고 정리하는 로직은 StackAdapter의
  "명령 하나 실행하고 끝"보다 훨씬 많은 실패 모드(포트 충돌, 기동 순서, 타임아웃 후 정리 실패)를
  갖는다. 파일럿 레포(marketplace) 하나로 충분히 안정화한 뒤 다른 레포로 확장한다.
- **외부망이 필요한 플로우는 구조적으로 배제된다** — 화이트리스트 네트워크(특정 도메인만
  허용)는 조사했지만 이번 설계에는 포함하지 않았다(복잡도 대비 marketplace에 당장 필요하지
  않음).

## 9. 단계적 도입 제안

| 단계 | 내용 |
|---|---|
| A | 샌드박스 이미지에 MySQL+Redis+Playwright 추가, marketplace 하나로 **수동** `docker run` 파일럿 검증(워크플로 연동 전) |
| B | `.review.yml` e2e 스키마 + `ReviewPipeline` e2e 단계 구현, PR 코멘트에 결과만 노출(게이트는 끔) |
| C | marketplace에 카테고리 Flyway 시드 추가, `application-ci-e2e.yaml` 정리 |
| D | 안정화 확인 후 `gate.e2e_fail_on_any` 전환 여부 결정 |
