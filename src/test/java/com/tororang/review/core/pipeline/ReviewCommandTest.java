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
    }

    @Test
    void parsesBuildPhase() {
        ReviewCommand command = ReviewCommand.from(new DefaultApplicationArguments("--phase=build"));

        assertThat(command.phase()).isEqualTo(ReviewCommand.Phase.BUILD);
    }

    @Test
    void parsesReportPhase() {
        ReviewCommand command = ReviewCommand.from(new DefaultApplicationArguments("--phase=report"));

        assertThat(command.phase()).isEqualTo(ReviewCommand.Phase.REPORT);
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
                "--repo=/repo", "--rulepack=/rp", "--config=/repo/custom.yml", "--findings=/repo/f.json"));

        assertThat(command.repoRoot()).isEqualTo(Path.of("/repo"));
        assertThat(command.rulepackDir()).isEqualTo(Path.of("/rp"));
        assertThat(command.configPath()).isEqualTo(Path.of("/repo/custom.yml"));
        assertThat(command.findingsPath()).isEqualTo(Path.of("/repo/f.json"));
    }
}
