package com.tororang.review.core.pipeline;

import com.tororang.review.core.stack.MutationReport;

import java.util.List;

/** {@link ReviewPipeline#runTestRunPhase}의 산출물: TC-ID별 판정 + 커버리지 증감 + 뮤테이션 스코어. */
public record TestRunResult(List<TestCaseReport> testCaseReports, CoverageDelta coverageDelta, MutationReport mutationReport) {

    public TestRunResult {
        testCaseReports = List.copyOf(testCaseReports);
    }
}