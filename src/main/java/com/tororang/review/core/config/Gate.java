package com.tororang.review.core.config;

import com.tororang.review.core.model.Severity;

/**
 * failOn: 지정한 심각도 이상 Finding이 하나라도 있으면 게이트 실패.
 * minCoverageDelta: tester가 추가한 테스트로 인한 커버리지 증가분(퍼센트 포인트)이 이 값보다
 * 작으면 게이트 실패 (설계서 9장). null이면 커버리지로는 게이트하지 않는다.
 * minMutationScore: PIT 뮤테이션 스코어(%)가 이 값보다 작으면 게이트 실패. null이면
 * 뮤테이션 스코어로는 게이트하지 않는다 (대상 레포에 pitest 플러그인이 없으면 항상 0%이므로,
 * 설정했는데 플러그인이 없으면 게이트는 항상 실패한다 — 의도적으로 명시적이게 뒀다).
 * e2eFailOnAny: E2E 테스트가 하나라도 실패하거나 환경 문제로 실행 자체가 안 됐으면 게이트
 * 실패. 기본값 false — docs/E2E_PIPELINE_DESIGN.md Phase B는 "경고만, 게이트는 끔"이
 * 방침이라 명시적으로 켜기 전까지는 PR을 막지 않는다(기존 fail_on 전환 때와 같은 방침,
 * 설계서 13장).
 */
public record Gate(Severity failOn, Double minCoverageDelta, Double minMutationScore, boolean e2eFailOnAny) {

    public static final Gate DISABLED = new Gate(null, null, null, false);

    public Gate(Severity failOn) {
        this(failOn, null, null, false);
    }

    public Gate(Severity failOn, Double minCoverageDelta) {
        this(failOn, minCoverageDelta, null, false);
    }

    public boolean isEnabled() {
        return failOn != null;
    }

    public boolean hasCoverageThreshold() {
        return minCoverageDelta != null;
    }

    public boolean hasMutationThreshold() {
        return minMutationScore != null;
    }
}
