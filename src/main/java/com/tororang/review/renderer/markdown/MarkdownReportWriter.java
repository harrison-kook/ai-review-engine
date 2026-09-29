package com.tororang.review.renderer.markdown;

import com.tororang.review.core.model.Finding;
import com.tororang.review.core.model.Severity;
import com.tororang.review.core.pipeline.TestCaseReport;
import com.tororang.review.core.renderer.ReviewReport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 소스 리뷰 결과(Findings)와 TC-ID별 테스트 결과를 하나의 Markdown 파일로 남긴다.
 * review-rulepack의 report-template.md 구조를 로컬/CI 산출물용으로 단순화한 버전.
 */
public final class MarkdownReportWriter {

    public void write(ReviewReport reviewReport, List<TestCaseReport> testCaseReports, Path outputPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 코드 리뷰 리포트\n\n");
        sb.append("생성 시각: ").append(Instant.now()).append("\n\n");

        appendFindingsSection(sb, reviewReport);
        appendTestCaseSection(sb, testCaseReports);

        try {
            if (outputPath.getParent() != null) {
                Files.createDirectories(outputPath.getParent());
            }
            Files.writeString(outputPath, sb.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write markdown report: " + outputPath, e);
        }
    }

    private void appendFindingsSection(StringBuilder sb, ReviewReport report) {
        Map<Severity, Long> counts = new EnumMap<>(Severity.class);
        for (Severity severity : Severity.values()) {
            counts.put(severity, 0L);
        }
        for (Finding finding : report.findings()) {
            counts.merge(finding.severity(), 1L, Long::sum);
        }

        sb.append("## 소스 리뷰 결과\n\n");
        sb.append("- 전체 지적: ").append(report.findings().size()).append("건")
                .append(" (HIGH ").append(counts.get(Severity.HIGH))
                .append(" · MEDIUM ").append(counts.get(Severity.MEDIUM))
                .append(" · LOW ").append(counts.get(Severity.LOW))
                .append(" · INFO ").append(counts.get(Severity.INFO))
                .append(")\n");
        if (report.config().gate().isEnabled()) {
            sb.append("- Gate(`fail_on: ").append(report.config().gate().failOn()).append("`): ")
                    .append(report.gateFailed() ? "**FAIL**" : "PASS").append("\n");
        }
        sb.append("\n");

        if (report.findings().isEmpty()) {
            sb.append("지적 사항 없음.\n\n");
            return;
        }

        sb.append("| 심각도 | 규칙 ID | 파일:라인 | 메시지 |\n");
        sb.append("|---|---|---|---|\n");
        for (Finding finding : report.findings()) {
            sb.append("| ").append(finding.severity())
                    .append(" | ").append(finding.ruleId())
                    .append(" | `").append(finding.file()).append(':').append(finding.line()).append('`')
                    .append(" | ").append(escapeCell(finding.message()))
                    .append(" |\n");
        }
        sb.append("\n");
    }

    private void appendTestCaseSection(StringBuilder sb, List<TestCaseReport> reports) {
        sb.append("## 테스트케이스 결과\n\n");

        if (reports.isEmpty()) {
            sb.append("테스트케이스 결과 없음 (gentest/testrun 단계를 실행하지 않았거나, 적용된 "
                    + "프로파일에 testcases가 없음).\n\n");
            return;
        }

        Map<TestCaseReport.Status, Long> counts = new EnumMap<>(TestCaseReport.Status.class);
        for (TestCaseReport.Status status : TestCaseReport.Status.values()) {
            counts.put(status, 0L);
        }
        for (TestCaseReport report : reports) {
            counts.merge(report.status(), 1L, Long::sum);
        }

        sb.append("- 전체 TC: ").append(reports.size()).append("개")
                .append(" (PASSED ").append(counts.get(TestCaseReport.Status.PASSED))
                .append(" · FAILED ").append(counts.get(TestCaseReport.Status.FAILED))
                .append(" · NOT_GENERATED ").append(counts.get(TestCaseReport.Status.NOT_GENERATED))
                .append(")\n\n");

        sb.append("| TC-ID | 제목 | 상태 | 테스트 | 메시지 |\n");
        sb.append("|---|---|---|---|---|\n");
        for (TestCaseReport report : reports) {
            String testRef = report.className() != null
                    ? "`" + report.className() + "#" + report.methodName() + "`"
                    : "-";
            sb.append("| ").append(report.tcId())
                    .append(" | ").append(escapeCell(report.title()))
                    .append(" | ").append(report.status())
                    .append(" | ").append(testRef)
                    .append(" | ").append(report.message() == null ? "" : escapeCell(report.message()))
                    .append(" |\n");
        }
        sb.append("\n");
    }

    private String escapeCell(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\n", " ").replace("|", "\\|");
    }
}
