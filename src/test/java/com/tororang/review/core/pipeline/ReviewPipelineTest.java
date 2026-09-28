package com.tororang.review.core.pipeline;

import com.tororang.review.core.config.Gate;
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
            return TestResult.NONE;
        }

        @Override
        public CoverageReport coverage(Workspace ws) {
            return CoverageReport.EMPTY;
        }
    }

    private static class FakeLlmClient implements LlmClient {
        List<FindingCandidate> candidates = List.of();

        @Override
        public ReviewResult review(ReviewRequest request) {
            return new ReviewResult(candidates, "raw");
        }

        @Override
        public TestGenResult generateTests(TestGenRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    private Finding finding(String ruleId, String fingerprint) {
        return new Finding(ruleId, Severity.HIGH, Source.CHECKSTYLE, "A.java", 1, "msg", "evidence", null, fingerprint);
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
        ReviewCommand command = new ReviewCommand(dir, dir.resolve("rulepack"), dir.resolve(".review.yml"),
                findingsPath, ReviewCommand.Phase.BUILD);

        pipeline.runBuildPhase(command);

        assertThat(FindingsIO.read(findingsPath)).extracting(Finding::ruleId).containsExactly("SEC-001");
    }

    @Test
    void buildPhaseThrowsWhenBuildFails(@TempDir Path dir) {
        FakeStackAdapter stackAdapter = new FakeStackAdapter();
        stackAdapter.buildResult = new StepResult(false, 1, "compile error");
        ReviewPipeline pipeline = new ReviewPipeline(List.of(stackAdapter), new FakeLlmClient());

        ReviewCommand command = new ReviewCommand(dir, dir.resolve("rulepack"), dir.resolve(".review.yml"),
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

        ReviewCommand command = new ReviewCommand(dir, dir.resolve("nonexistent-rulepack"),
                dir.resolve("nonexistent-config.yml"), dir.resolve("build/review-findings.json"),
                ReviewCommand.Phase.BUILD);

        pipeline.runBuildPhase(command);

        assertThat(FindingsIO.read(command.findingsPath())).isEmpty();
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
        ReviewCommand command = new ReviewCommand(dir, rulepackDir, dir.resolve(".review.yml"),
                findingsPath, ReviewCommand.Phase.REPORT);

        ReviewReport report = pipeline.runReportPhase(command);

        assertThat(report.findings()).extracting(Finding::ruleId).containsExactlyInAnyOrder("SEC-001", "JPA-003");
        assertThat(dir.resolve(".claude/rulepack/rules/common/security.md")).exists();
    }

    @Test
    void runExecutesBothPhasesEndToEnd(@TempDir Path dir) throws IOException {
        writeConfig(dir);
        Path rulepackDir = dir.resolve("rulepack");
        Files.createDirectories(rulepackDir);

        FakeStackAdapter stackAdapter = new FakeStackAdapter();
        stackAdapter.lintFindings = List.of(finding("STYLE-010", "fp-lint"));
        FakeLlmClient llmClient = new FakeLlmClient();
        llmClient.candidates = List.of(new FindingCandidate("SEC-001", Severity.HIGH, Source.LLM,
                "C.java", 3, "msg", "evidence", null));

        ReviewPipeline pipeline = new ReviewPipeline(List.of(stackAdapter), llmClient);
        ReviewCommand command = new ReviewCommand(dir, rulepackDir, dir.resolve(".review.yml"),
                dir.resolve("build/review-findings.json"), ReviewCommand.Phase.ALL);

        ReviewReport report = pipeline.run(command);

        assertThat(report.findings()).extracting(Finding::ruleId).containsExactlyInAnyOrder("STYLE-010", "SEC-001");
        assertThat(report.gateFailed()).isFalse();
        assertThat(report.config().gate()).isEqualTo(Gate.DISABLED);
    }
}
