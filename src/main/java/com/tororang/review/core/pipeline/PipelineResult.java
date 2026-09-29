package com.tororang.review.core.pipeline;

import com.tororang.review.core.renderer.ReviewReport;
import com.tororang.review.core.stack.MutationReport;

import java.util.List;

/**
 * run() [ALL 편의 메서드]의 최종 산출물: 소스 리뷰 결과 + TC-ID별 테스트 결과 + 커버리지 증감
 * + 뮤테이션 스코어.
 */
public record PipelineResult(
        ReviewReport reviewReport,
        List<TestCaseReport> testCaseReports,
        CoverageDelta coverageDelta,
        MutationReport mutationReport
) {

    public PipelineResult {
        testCaseReports = List.copyOf(testCaseReports);
    }
}