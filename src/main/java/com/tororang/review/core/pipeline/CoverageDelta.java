package com.tororang.review.core.pipeline;

import com.tororang.review.core.stack.CoverageReport;

/**
 * 설계서 9장 "테스트 품질 검증": tester가 만든 테스트를 더하기 전(baseline)과 더한 뒤(after)의
 * 커버리지를 비교한다. deltaPercentagePoints가 음수면 오히려 줄어든 것(정상적으로는 발생하지
 * 않아야 하지만 baseline 자체가 불안정한 테스트를 포함하면 가능하다).
 */
public record CoverageDelta(CoverageReport baseline, CoverageReport after, double deltaPercentagePoints) {

    public static CoverageDelta of(CoverageReport baseline, CoverageReport after) {
        return new CoverageDelta(baseline, after, after.lineCoveragePercent() - baseline.lineCoveragePercent());
    }
}