package com.tororang.review.core.stack;

/**
 * JaCoCo 커버리지 요약. lineCoveragePercent는 0.0~100.0 범위.
 */
public record CoverageReport(double lineCoveragePercent, int coveredLines, int missedLines) {

    public static final CoverageReport EMPTY = new CoverageReport(0.0, 0, 0);
}
