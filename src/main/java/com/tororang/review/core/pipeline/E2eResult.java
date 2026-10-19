package com.tororang.review.core.pipeline;

import com.tororang.review.core.stack.TestCaseResult;

import java.util.List;

/**
 * E2E(Playwright 등) 실행 결과. {@link com.tororang.review.core.stack.TestResult}와 의도적으로
 * 분리했다(docs/E2E_PIPELINE_DESIGN.md 5장) — 단위 테스트와 섞이면 "테스트 개수가 갑자기
 * 늘었다"는 혼란을 만들기 때문이다.
 *
 * <p>{@code envFailureReason}이 있으면 테스트가 실행되기 전에 환경이 실패한 것이다(서비스가
 * healthcheck 안에 기동 안 됨 등) — 대상 레포 코드의 버그가 아니라 환경 문제이므로 PR 코멘트에서
 * 구분해서 보여준다(false positive 방지).
 */
public record E2eResult(
        boolean enabled,
        boolean success,
        int totalTests,
        int failedTests,
        List<TestCaseResult> cases,
        String envFailureReason,
        String output
) {
    public static final E2eResult DISABLED = new E2eResult(false, true, 0, 0, List.of(), null, null);

    public E2eResult {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }
}
