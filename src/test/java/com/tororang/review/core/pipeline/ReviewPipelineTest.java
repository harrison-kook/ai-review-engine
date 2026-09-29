package com.tororang.review.core.pipeline;

import com.tororang.review.core.config.Gate;
import com.tororang.review.core.llm.GenTestStatus;
import com.tororang.review.core.llm.GeneratedTestCase;
import com.tororang.review.core.llm.LlmClient;
import com.tororang.review.core.llm.ReviewRequest;
import com.tororang.review.core.llm.ReviewResult;
import com.tororang.review.core.llm.TestGenRequest;
import com.tororang.review.core.llm.TestGenResult;
import com.tororang.review.core.model.Finding;
import com.tororang.review.core.model.FindingCandidate;
import com.tororang.review.core.model.Severity;
import com.tororang.review.core.model.Source;
import com.tororang.review.core.renderer.ReviewReport;
import com.tororang.review.core.stack.CoverageReport;
import com.tororang.review.core.stack.StackAdapter;
import com.tororang.review.core.stack.StepResult;
import com.tororang.review.core.stack.TestResult;
import com.tororang.review.core.stack.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewPipelineTest {

    private static class FakeStackAdapter implements StackAdapter {
        boolean detected = true;
        StepResult buildResult = new StepResult(true, 0, "ok");
        List<Finding> lintFindings = List.of();
        TestResult testResult = TestResult.NONE;
        // coverage()는 BUILD(baseline)와 TESTRUN(after)에서 순서대로 한 번씩 불린다.
        List<CoverageReport> coverageResults = new java.util.ArrayList<>(List.of(CoverageReport.EMPTY, CoverageReport.EMPTY));
        int coverageCallIndex = 0;

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public boolean detect(Path repoRoot) {
            return detected;
        }

        @Override
        public StepResult build(Workspace ws) {
            return buildResult;
        }

        @Override
        public List<Finding> lint(Workspace ws) {
            return lintFindings;
        }

        @Override
        public TestResult test(Workspace ws) {
            return testResult;
        }

        @Override
        public CoverageReport coverage(Workspace ws) {
            if (coverageCallIndex < coverageResults.size()) {
                return coverageResults.get(coverageCallIndex++);
            }
            return CoverageReport.EMPTY;
        }

        @Override
        public com.tororang.review.core.stack.MutationReport mutate(Workspace ws) {
            return mutationReport;
        }

        com.tororang.review.core.stack.MutationReport mutationReport = com.tororang.review.core.stack.MutationReport.EMPTY;
    }

    private static class FakeLlmClient implements LlmClient {
        List<FindingCandidate> candidates = List.of();
        List<com.tororang.review.core.llm.GeneratedTestCase> generatedTests = List.of();

        @Override
        public ReviewResult review(ReviewRequest request) {
            return new ReviewResult(candidates, "raw");
        }

        @Override
        public TestGenResult generateTests(TestGenRequest request) {
            return new TestGenResult(generatedTests, "raw");
        }
    }

    private Finding finding(String ruleId, String fingerprint) {
        return new Finding(ruleId, Severity.HIGH, Source.CHECKSTYLE, "A.java", 1, "msg", "evidence", null, fingerprint);
    }

    private ReviewCommand command(Path dir, Path rulepackDir, Path configPath, Path findingsPath, ReviewCommand.Phase phase) {
        return new ReviewCommand(dir, rulepackDir, configPath, findingsPath,
                dir.resolve("build/gen-test-mapping.json"), dir.resolve("build/testcase-report.json"),
                dir.resolve("build/baseline-coverage.json"), dir.resolve("build/coverage-delta.json"),
                dir.resolve("build/mutation-report.json"), null, 200, "origin/main", phase);
    }

    private void writeConfig(Path dir) throws IOException {
        Files.writeString(dir.resolve(".review.yml"), """
                rulepack: org/repo@v0.1.0
                profiles: [common]
                mode: diff
                """);
    }

    @Test
    void buildPhaseWritesLintFindingsWhenBuildSucceeds(@TempDir Path dir) throws IOException {
        FakeStackAdapter stackAdapter = new FakeStackAdapter();
        stackAdapter.lintFindings = List.of(finding("SEC-001", "fp-1"));
        ReviewPipeline pipeline = new ReviewPipeline(List.of(stackAdapter), new FakeLlmClient());

        Path findingsPath = dir.resolve("build/review-findings.json");
        ReviewCommand command = command(dir, dir.resolve("rulepack"), dir.resolve(".review.yml"),
                findingsPath, ReviewCommand.Phase.BUILD);

        pipeline.runBuildPhase(command);

        assertThat(FindingsIO.read(findingsPath)).extracting(Finding::ruleId).containsExactly("SEC-001");
    }

    @Test
    void buildPhaseThrowsWhenBuildFails(@TempDir Path dir) {
        FakeStackAdapter stackAdapter = new FakeStackAdapter();
        stackAdapter.buildResult = new StepResult(false, 1, "compile error");
        ReviewPipeline pipeline = new ReviewPipeline(List.of(stackAdapter), new FakeLlmClient());

        ReviewCommand command = command(dir, dir.resolve("rulepack"), dir.resolve(".review.yml"),
                dir.resolve("build/review-findings.json"), ReviewCommand.Phase.BUILD);

        assertThatThrownBy(() -> pipeline.runBuildPhase(command))
                .isInstanceOf(BuildFailedException.class)
                .hasMessageContaining("compile error");
    }

    @Test
    void buildPhaseNeverTouchesRulepackOrLlm(@TempDir Path dir) {
        // rulepackDir/config를 아예 만들지 않아도 BUILD 단계는 성공해야 한다 — 시크릿/룰팩 불필요 원칙 검증
        FakeStackAdapter stackAdapter = new FakeStackAdapter();
        ReviewPipeline pipeline = new ReviewPipeline(List.of(stackAdapter), new FakeLlmClient());

        ReviewCommand command = command(dir, dir.resolve("nonexistent-rulepack"),
                dir.resolve("nonexistent-config.yml"), dir.resolve("build/review-findings.json"),
                ReviewCommand.Phase.BUILD);

        pipeline.runBuildPhase(command);

        assertThat(FindingsIO.read(command.findingsPath())).isEmpty();
    }

    @Test
    void buildPhaseWritesBaselineCoverage(@TempDir Path dir) {
        FakeStackAdapter stackAdapter = new FakeStackAdapter();
        stackAdapter.coverageResults = List.of(new CoverageReport(42.0, 42, 58));
        ReviewPipeline pipeline = new ReviewPipeline(List.of(stackAdapter), new FakeLlmClient());

        ReviewCommand command = command(dir, dir.resolve("rulepack"), dir.resolve(".review.yml"),
                dir.resolve("build/review-findings.json"), ReviewCommand.Phase.BUILD);

        pipeline.runBuildPhase(command);

        CoverageReport baseline = JsonIO.read(command.baselineCoveragePath(), CoverageReport.class, null);
        assertThat(baseline).isNotNull();
        assertThat(baseline.lineCoveragePercent()).isEqualTo(42.0);
    }

    @Test
    void testRunPhaseComputesCoverageDeltaAgainstBaseline(@TempDir Path dir) throws IOException {
        writeConfig(dir);
        Path rulepackDir = dir.resolve("rulepack");
        Files.createDirectories(rulepackDir);

        JsonIO.write(dir.resolve("build/baseline-coverage.json"), new CoverageReport(40.0, 40, 60));

        FakeStackAdapter stackAdapter = new FakeStackAdapter();
        stackAdapter.coverageResults = List.of(new CoverageReport(55.0, 55, 45));

        ReviewPipeline pipeline = new ReviewPipeline(List.of(stackAdapter), new FakeLlmClient());
        ReviewCommand command = command(dir, rulepackDir, dir.resolve(".review.yml"),
                dir.resolve("build/review-findings.json"), ReviewCommand.Phase.TESTRUN);

        TestRunResult result = pipeline.runTestRunPhase(command);

        assertThat(result.coverageDelta().baseline().lineCoveragePercent()).isEqualTo(40.0);
        assertThat(result.coverageDelta().after().lineCoveragePercent()).isEqualTo(55.0);
        assertThat(result.coverageDelta().deltaPercentagePoints()).isEqualTo(15.0);

        CoverageDelta persisted = JsonIO.read(command.coverageDeltaPath(), CoverageDelta.class, null);
        assertThat(persisted.deltaPercentagePoints()).isEqualTo(15.0);
    }

    @Test
    void testRunPhaseTreatsMissingBaselineAsZeroCoverage(@TempDir Path dir) throws IOException {
        writeConfig(dir);
        Path rulepackDir = dir.resolve("rulepack");
        Files.createDirectories(rulepackDir);

        // baseline-coverage.json을 쓰지 않음 (BUILD 단계를 안 돌린 상황을 흉내)
        FakeStackAdapter stackAdapter = new FakeStackAdapter();
        stackAdapter.coverageResults = List.of(new CoverageReport(20.0, 20, 80));

        ReviewPipeline pipeline = new ReviewPipeline(List.of(stackAdapter), new FakeLlmClient());
        ReviewCommand command = command(dir, rulepackDir, dir.resolve(".review.yml"),
                dir.resolve("build/review-findings.json"), ReviewCommand.Phase.TESTRUN);

        TestRunResult result = pipeline.runTestRunPhase(command);

        assertThat(result.coverageDelta().baseline()).isEqualTo(CoverageReport.EMPTY);
        assertThat(result.coverageDelta().deltaPercentagePoints()).isEqualTo(20.0);
    }

    @Test
    void reportPhaseCombinesPersistedAndLlmFindings(@TempDir Path dir) throws IOException {
        writeConfig(dir);
        Path rulepackDir = dir.resolve("rulepack");
        Files.createDirectories(rulepackDir.resolve("rules/common"));
        Files.writeString(rulepackDir.resolve("rules/common/security.md"), "## SEC-001: x\n- 심각도: HIGH\n");

        Path findingsPath = dir.resolve("build/review-findings.json");
        FindingsIO.write(findingsPath, List.of(finding("SEC-001", "fp-1")));

        FakeLlmClient llmClient = new FakeLlmClient();
        llmClient.candidates = List.of(new FindingCandidate("JPA-003", Severity.HIGH, Source.LLM,
                "B.java", 2, "n+1", "orders.forEach(...)", "fetch join"));

        ReviewPipeline pipeline = new ReviewPipeline(List.of(new FakeStackAdapter()), llmClient);
        ReviewCommand command = command(dir, rulepackDir, dir.resolve(".review.yml"),
                findingsPath, ReviewCommand.Phase.REPORT);

        ReviewReport report = pipeline.runReportPhase(command);

        assertThat(report.findings()).extracting(Finding::ruleId).containsExactlyInAnyOrder("SEC-001", "JPA-003");
        assertThat(dir.resolve(".claude/rulepack/rules/common/security.md")).exists();
    }

    @Test
    void runExecutesAllFourPhasesEndToEnd(@TempDir Path dir) throws IOException {
        writeConfig(dir);
        Path rulepackDir = dir.resolve("rulepack");
        Files.createDirectories(rulepackDir);

        FakeStackAdapter stackAdapter = new FakeStackAdapter();
        stackAdapter.lintFindings = List.of(finding("STYLE-010", "fp-lint"));
        FakeLlmClient llmClient = new FakeLlmClient();
        llmClient.candidates = List.of(new FindingCandidate("SEC-001", Severity.HIGH, Source.LLM,
                "C.java", 3, "msg", "evidence", null));

        ReviewPipeline pipeline = new ReviewPipeline(List.of(stackAdapter), llmClient);
        ReviewCommand command = command(dir, rulepackDir, dir.resolve(".review.yml"),
                dir.resolve("build/review-findings.json"), ReviewCommand.Phase.ALL);

        PipelineResult result = pipeline.run(command);

        assertThat(result.reviewReport().findings()).extracting(Finding::ruleId)
                .containsExactlyInAnyOrder("STYLE-010", "SEC-001");
        assertThat(result.reviewReport().gateFailed()).isFalse();
        assertThat(result.reviewReport().config().gate()).isEqualTo(Gate.DISABLED);
        assertThat(result.testCaseReports()).isEmpty(); // rulepackDir에 testcases/가 없으므로 빈 카탈로그
    }

    @Test
    void genTestPhaseWritesMappingFromLlm(@TempDir Path dir) throws IOException {
        writeConfig(dir);
        Path rulepackDir = dir.resolve("rulepack");
        Files.createDirectories(rulepackDir);

        FakeLlmClient llmClient = new FakeLlmClient();
        llmClient.generatedTests = List.of(new com.tororang.review.core.llm.GeneratedTestCase(
                "TC-PAY-001", GenTestStatus.GENERATED, "com.example.PaymentServiceTest",
                "duplicateApprove_isIdempotent", "src/test/java/com/example/PaymentServiceTest.java", null));

        ReviewPipeline pipeline = new ReviewPipeline(List.of(new FakeStackAdapter()), llmClient);
        ReviewCommand command = command(dir, rulepackDir, dir.resolve(".review.yml"),
                dir.resolve("build/review-findings.json"), ReviewCommand.Phase.GENTEST);

        pipeline.runGenTestPhase(command);

        List<GeneratedTestCase> saved = JsonListIO.read(command.genTestPath(), GeneratedTestCase.class);
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).tcId()).isEqualTo("TC-PAY-001");
        assertThat(saved.get(0).status()).isEqualTo(GenTestStatus.GENERATED);
    }

    @Test
    void testRunPhaseCorrelatesGeneratedTestsWithExecutionResults(@TempDir Path dir) throws IOException {
        writeConfig(dir);
        Path rulepackDir = dir.resolve("rulepack");
        Files.createDirectories(rulepackDir.resolve("testcases/common"));
        Files.writeString(rulepackDir.resolve("testcases/common/payment.md"), """
                ## TC-PAY-001: 중복 승인 멱등 처리
                - 대상 계층: Service

                ## TC-PAY-002: 부분 취소 초과 거부
                - 대상 계층: Service

                ## TC-PAY-003: 커버 안 된 시나리오
                - 대상 계층: Service
                """);

        JsonListIO.write(dir.resolve("build/gen-test-mapping.json"), List.of(
                new GeneratedTestCase("TC-PAY-001", GenTestStatus.GENERATED, "com.example.PaymentServiceTest",
                        "duplicateApprove_isIdempotent", "src/test/java/com/example/PaymentServiceTest.java", null),
                new GeneratedTestCase("TC-PAY-002", GenTestStatus.GENERATED, "com.example.PaymentServiceTest",
                        "partialCancel_exceedsAmount_throws", "src/test/java/com/example/PaymentServiceTest.java", null)
        ));

        FakeStackAdapter stackAdapter = new FakeStackAdapter();
        stackAdapter.testResult = new TestResult(false, 2, 1, List.of(
                new com.tororang.review.core.stack.TestCaseResult(
                        "com.example.PaymentServiceTest", "duplicateApprove_isIdempotent", true, null),
                new com.tororang.review.core.stack.TestCaseResult(
                        "com.example.PaymentServiceTest", "partialCancel_exceedsAmount_throws", false, "assertion failed")
        ), "output");

        ReviewPipeline pipeline = new ReviewPipeline(List.of(stackAdapter), new FakeLlmClient());
        ReviewCommand command = command(dir, rulepackDir, dir.resolve(".review.yml"),
                dir.resolve("build/review-findings.json"), ReviewCommand.Phase.TESTRUN);

        List<TestCaseReport> reports = pipeline.runTestRunPhase(command).testCaseReports();

        assertThat(reports).hasSize(3);
        TestCaseReport tc1 = findByTcId(reports, "TC-PAY-001");
        assertThat(tc1.status()).isEqualTo(TestCaseReport.Status.PASSED);

        TestCaseReport tc2 = findByTcId(reports, "TC-PAY-002");
        assertThat(tc2.status()).isEqualTo(TestCaseReport.Status.FAILED);
        assertThat(tc2.message()).isEqualTo("assertion failed");

        TestCaseReport tc3 = findByTcId(reports, "TC-PAY-003");
        assertThat(tc3.status()).isEqualTo(TestCaseReport.Status.NOT_GENERATED);

        List<TestCaseReport> persisted = JsonListIO.read(command.testCaseReportPath(), TestCaseReport.class);
        assertThat(persisted).hasSize(3);
    }

    @Test
    void testRunPhaseTreatsMissingExecutionResultAsFailed(@TempDir Path dir) throws IOException {
        writeConfig(dir);
        Path rulepackDir = dir.resolve("rulepack");
        Files.createDirectories(rulepackDir.resolve("testcases/common"));
        Files.writeString(rulepackDir.resolve("testcases/common/x.md"), "## TC-X-001: 시나리오\n- 대상 계층: Service\n");

        JsonListIO.write(dir.resolve("build/gen-test-mapping.json"), List.of(
                new GeneratedTestCase("TC-X-001", GenTestStatus.GENERATED, "com.example.XTest",
                        "someMethod", "src/test/java/com/example/XTest.java", null)
        ));

        // 컴파일 실패 등으로 실행 결과 자체가 없는 상황을 흉내낸다 (cases가 비어있음)
        FakeStackAdapter stackAdapter = new FakeStackAdapter();
        stackAdapter.testResult = new TestResult(false, 0, 0, List.of(), "compile error");

        ReviewPipeline pipeline = new ReviewPipeline(List.of(stackAdapter), new FakeLlmClient());
        ReviewCommand command = command(dir, rulepackDir, dir.resolve(".review.yml"),
                dir.resolve("build/review-findings.json"), ReviewCommand.Phase.TESTRUN);

        List<TestCaseReport> reports = pipeline.runTestRunPhase(command).testCaseReports();

        assertThat(reports).hasSize(1);
        assertThat(reports.get(0).status()).isEqualTo(TestCaseReport.Status.FAILED);
    }

    private TestCaseReport findByTcId(List<TestCaseReport> reports, String tcId) {
        return reports.stream().filter(r -> r.tcId().equals(tcId)).findFirst()
                .orElseThrow(() -> new AssertionError("no report for " + tcId));
    }
}
