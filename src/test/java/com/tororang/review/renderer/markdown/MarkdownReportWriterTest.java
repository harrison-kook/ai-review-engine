package com.tororang.review.renderer.markdown;

import com.tororang.review.core.config.Gate;
import com.tororang.review.core.config.Limits;
import com.tororang.review.core.config.Overrides;
import com.tororang.review.core.config.ReviewConfig;
import com.tororang.review.core.config.ReviewMode;
import com.tororang.review.core.model.Finding;
import com.tororang.review.core.model.Severity;
import com.tororang.review.core.model.Source;
import com.tororang.review.core.pipeline.TestCaseReport;
import com.tororang.review.core.renderer.ReviewReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownReportWriterTest {

    private final MarkdownReportWriter writer = new MarkdownReportWriter();

    private ReviewConfig config(Gate gate) {
        return new ReviewConfig("org/repo@v0.1.0", List.of("common"), ReviewMode.FULL,
                List.of(), List.of(), Overrides.EMPTY, Limits.EMPTY, gate);
    }

    @Test
    void writesFindingsAndTestCaseSections(@TempDir Path dir) throws IOException {
        Finding finding = new Finding("SEC-002", Severity.HIGH, Source.LLM,
                "src/main/java/PaymentClient.java", 5, "API 키 하드코딩", "private String apiKey = ...", "제안", "fp-1");
        ReviewReport reviewReport = new ReviewReport(List.of(finding), config(new Gate(Severity.HIGH)));

        List<TestCaseReport> testCaseReports = List.of(
                new TestCaseReport("TC-PAY-001", "중복 승인 멱등", TestCaseReport.Status.PASSED,
                        "com.example.PaymentServiceTest", "duplicateApprove_isIdempotent", null),
                new TestCaseReport("TC-PAY-002", "부분 취소 초과", TestCaseReport.Status.FAILED,
                        "com.example.PaymentServiceTest", "partialCancel_exceedsAmount", "assertion failed"),
                new TestCaseReport("TC-PAY-003", "커버 안 됨", TestCaseReport.Status.NOT_GENERATED,
                        null, null, "대상 클래스 없음")
        );

        Path output = dir.resolve("review-report.md");
        writer.write(reviewReport, testCaseReports, output);

        String content = Files.readString(output);
        assertThat(content).contains("# 코드 리뷰 리포트");
        assertThat(content).contains("SEC-002");
        assertThat(content).contains("PaymentClient.java:5");
        assertThat(content).contains("**FAIL**");
        assertThat(content).contains("TC-PAY-001").contains("PASSED");
        assertThat(content).contains("TC-PAY-002").contains("FAILED").contains("assertion failed");
        assertThat(content).contains("TC-PAY-003").contains("NOT_GENERATED");
    }

    @Test
    void handlesEmptyFindingsAndTestCases(@TempDir Path dir) throws IOException {
        ReviewReport reviewReport = new ReviewReport(List.of(), config(Gate.DISABLED));

        Path output = dir.resolve("review-report.md");
        writer.write(reviewReport, List.of(), output);

        String content = Files.readString(output);
        assertThat(content).contains("지적 사항 없음");
        assertThat(content).contains("테스트케이스 결과 없음");
    }
}
