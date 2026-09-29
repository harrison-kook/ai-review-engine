package com.tororang.review.core.pipeline;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewCommandTest {

    @Test
    void defaultsToAllPhaseWithCurrentDirectory() {
        ReviewCommand command = ReviewCommand.from(new DefaultApplicationArguments());

        assertThat(command.phase()).isEqualTo(ReviewCommand.Phase.ALL);
        assertThat(command.repoRoot()).isEqualTo(Path.of("."));
        assertThat(command.rulepackDir()).isEqualTo(Path.of("./rulepack"));
        assertThat(command.configPath()).isEqualTo(Path.of(".").resolve(".review.yml"));
        assertThat(command.findingsPath()).isEqualTo(Path.of(".").resolve("build/review-findings.json"));
        assertThat(command.genTestPath()).isEqualTo(Path.of(".").resolve("build/gen-test-mapping.json"));
        assertThat(command.testCaseReportPath()).isEqualTo(Path.of(".").resolve("build/testcase-report.json"));
        assertThat(command.githubRepo()).isNull();
        assertThat(command.maxFeedbackPullRequests()).isEqualTo(200);
        assertThat(command.diffBase()).isEqualTo("origin/main");
    }

    @Test
    void parsesBuildPhase() {
        ReviewCommand command = ReviewCommand.from(new DefaultApplicationArguments("--phase=build"));

        assertThat(command.phase()).isEqualTo(ReviewCommand.Phase.BUILD);
    }

    @Test
    void parsesGenTestPhase() {
        ReviewCommand command = ReviewCommand.from(new DefaultApplicationArguments("--phase=gentest"));

        assertThat(command.phase()).isEqualTo(ReviewCommand.Phase.GENTEST);
    }

    @Test
    void parsesTestRunPhase() {
        ReviewCommand command = ReviewCommand.from(new DefaultApplicationArguments("--phase=testrun"));

        assertThat(command.phase()).isEqualTo(ReviewCommand.Phase.TESTRUN);
    }

    @Test
    void parsesReportPhase() {
        ReviewCommand command = ReviewCommand.from(new DefaultApplicationArguments("--phase=report"));

        assertThat(command.phase()).isEqualTo(ReviewCommand.Phase.REPORT);
    }

    @Test
    void parsesFeedbackPhase() {
        ReviewCommand command = ReviewCommand.from(new DefaultApplicationArguments("--phase=feedback"));

        assertThat(command.phase()).isEqualTo(ReviewCommand.Phase.FEEDBACK);
    }

    @Test
    void parsesFeedbackOptions() {
        ReviewCommand command = ReviewCommand.from(new DefaultApplicationArguments(
                "--phase=feedback", "--github-repo=our-org/our-repo", "--max-prs=50"));

        assertThat(command.githubRepo()).isEqualTo("our-org/our-repo");
        assertThat(command.maxFeedbackPullRequests()).isEqualTo(50);
    }

    @Test
    void rejectsInvalidMaxPrs() {
        assertThatThrownBy(() -> ReviewCommand.from(new DefaultApplicationArguments("--max-prs=abc")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-prs");
    }

    @Test
    void rejectsUnknownPhase() {
        assertThatThrownBy(() -> ReviewCommand.from(new DefaultApplicationArguments("--phase=bogus")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bogus");
    }

    @Test
    void honorsExplicitPaths() {
        ReviewCommand command = ReviewCommand.from(new DefaultApplicationArguments(
                "--repo=/repo", "--rulepack=/rp", "--config=/repo/custom.yml", "--findings=/repo/f.json",
                "--gentest=/repo/g.json", "--testreport=/repo/t.json"));

        assertThat(command.repoRoot()).isEqualTo(Path.of("/repo"));
        assertThat(command.rulepackDir()).isEqualTo(Path.of("/rp"));
        assertThat(command.configPath()).isEqualTo(Path.of("/repo/custom.yml"));
        assertThat(command.findingsPath()).isEqualTo(Path.of("/repo/f.json"));
        assertThat(command.genTestPath()).isEqualTo(Path.of("/repo/g.json"));
        assertThat(command.testCaseReportPath()).isEqualTo(Path.of("/repo/t.json"));
    }

    @Test
    void honorsDiffBase() {
        ReviewCommand command = ReviewCommand.from(new DefaultApplicationArguments("--diff-base=main"));

        assertThat(command.diffBase()).isEqualTo("main");
    }
}
