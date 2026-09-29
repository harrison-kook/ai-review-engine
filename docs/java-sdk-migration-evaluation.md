# Java SDK 전환 검토 (설계서 10장 3단계 항목)

> 설계서 로드맵은 이 항목을 "전환 검토"로만 표기했다 — 구현이 아니라 판단 근거를 남기는
> 문서다. 결론: **지금은 전환하지 않는다.** 근거는 아래.

## 현재 구조: ClaudeCodeCliClient

`llm.ClaudeCodeCliClient`는 `ProcessBuilder`로 `claude -p "/review" --output-format json`을
호출한다. review-rulepack의 `agents/*.md`(system prompt), `commands/*.md`(슬래시 커맨드)를
그대로 쓰고, 멀티턴 도구 호출(Read/Grep/Glob/Write) 루프는 **Claude Code CLI가 대신 돌려준다**
— 우리 엔진은 그 결과(JSON 봉투의 `result` 필드)만 파싱한다.

## 대안: AnthropicApiClient (Java SDK 직접 호출)

설계서 7.2가 언급한 대안. `com.anthropic:anthropic-java`로 Messages API를 직접 호출한다.

## 비교

| | ClaudeCodeCliClient (현재) | AnthropicApiClient (SDK 직접) |
|---|---|---|
| 에이전트 루프(Read/Grep/Glob/Write 멀티턴) | Claude Code CLI가 대신 처리 | **우리가 직접 구현해야 함** — tool 정의, tool_use 응답 파싱, 로컬 파일 I/O 실행, tool_result 재전송, 종료 판단까지 전부 |
| rulepack의 agents/commands 재사용 | 그대로 씀 | system prompt 조립 로직을 새로 짜야 함 |
| 구조화된 출력 신뢰성 | 프롬프트 지시에 의존 (이번 세션에 실제로 fingerprint 필드 끼워넣기, 코드펜스 앞뒤 문장 등 위반 사례 발견 → 방어적 파싱으로 대응) | native tool-use(함수 호출)로 JSON 스키마 강제 가능 — 훨씬 견고 |
| 샌드박스 이미지 의존성 | Node.js + `@anthropic-ai/claude-code` 필요 | 불필요 (JVM만) |
| 재시도/스트리밍/토큰 사용량 제어 | CLI가 내부적으로 처리, 우리는 최종 JSON만 봄 | 세밀하게 직접 제어 가능 |
| 도구 접근 제한(Read/Grep/Glob만, Bash 없음) | CLI의 `--allowedTools`가 강제 | 우리가 구현하는 tool 목록 자체가 곧 허용 범위 — 직접 구현·검증해야 함 |
| 구현/검증 비용 | 완료 (1~2단계 전체) | 미니 에이전트 프레임워크를 새로 만드는 수준 — 이번 세션에서 1~2단계 전체를 만든 것과 비슷한 규모로 추정 |

## 왜 지금 전환하지 않는가

1. **CLI 방식의 가장 큰 약점(구조화 출력 신뢰성)은 이미 완화됐다.** 실사용 중 발견한 문제들
   (fingerprint 필드 끼워넣기, JSON 앞뒤 텍스트, stdin 경고 혼입, Windows claude.cmd)을 전부
   엔진 쪽 방어적 파싱으로 해결했다. SDK의 native tool-use가 이 문제를 "애초에 안 생기게" 막긴
   하지만, 지금 방식도 실전 검증을 거쳐 안정화된 상태다.
2. **에이전트 루프 재구현이 진짜 큰일이다.** Claude Code CLI가 공짜로 해주는 걸(도구 호출
   프로토콜, 다중 턴 오케스트레이션, 도구별 권한 제한) 우리가 처음부터 만들어야 한다. 이건
   "클라이언트만 바꾸는" 작업이 아니라 미니 에이전트 프레임워크를 새로 만드는 작업이다.
3. **지금 구조가 실제로 동작하고 검증됐다.** 이번 세션에서 로컬 샘플 레포에 대해 리뷰·테스트
   생성·커버리지 게이트까지 end-to-end로 여러 번 실전 검증했다. 전환 자체가 이 검증을 상당 부분
   무효화한다(에이전트 루프가 바뀌면 프롬프트/도구 동작이 달라질 수 있다).
4. **전환을 정당화할 구체적 압박 요인이 아직 없다.** 예를 들면: 샌드박스 이미지에서 Node.js를
   빼야 하는 보안/크기 요구사항이 생기거나, 방어적 파싱으로도 못 막는 구조적 신뢰성 한계에
   부딪히거나, claude CLI 자체의 라이선스/배포 제약이 걸리는 경우. 지금은 해당 없음.

## 재검토 시점

아래 중 하나라도 해당되면 이 문서를 다시 꺼내 실제 마이그레이션을 계획한다:
- 샌드박스 이미지에서 Node.js 의존성을 반드시 제거해야 하는 요구사항이 생김
- `claude` CLI의 `--output-format json` 계약이 깨지거나(버전 업), 방어적 파싱으로도 못 잡는
  구조적 파싱 실패가 반복됨
- 팀 규모가 커져서 토큰 사용량/비용을 SDK 수준으로 세밀하게 제어해야 할 필요가 생김
- Claude Agent SDK가 Java를 지원하게 되어(현재는 Python/TypeScript만) 에이전트 루프를
  직접 구현할 필요 자체가 없어짐 — 이 경우가 가장 유력한 전환 트리거다.