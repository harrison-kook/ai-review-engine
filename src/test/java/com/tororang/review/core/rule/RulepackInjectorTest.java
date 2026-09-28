package com.tororang.review.core.rule;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RulepackInjectorTest {

    private final RulepackInjector injector = new RulepackInjector();

    @Test
    void copiesClaudeMdAgentsCommandsAndSelectedProfiles(@TempDir Path root) throws IOException {
        Path rulepackDir = root.resolve("rulepack");
        Path repoRoot = root.resolve("repo");
        Files.createDirectories(repoRoot);

        Files.createDirectories(rulepackDir);
        Files.writeString(rulepackDir.resolve("CLAUDE.md"), "공통 지침");

        Files.createDirectories(rulepackDir.resolve("agents"));
        Files.writeString(rulepackDir.resolve("agents/reviewer.md"), "reviewer");

        Files.createDirectories(rulepackDir.resolve("commands"));
        Files.writeString(rulepackDir.resolve("commands/review.md"), "/review");

        Files.createDirectories(rulepackDir.resolve("rules/common"));
        Files.writeString(rulepackDir.resolve("rules/common/security.md"), "SEC-001");

        Files.createDirectories(rulepackDir.resolve("rules/team"));
        Files.writeString(rulepackDir.resolve("rules/team/our-team.md"), "TEAM-001");

        injector.inject(rulepackDir, repoRoot, List.of("common"));

        assertThat(repoRoot.resolve(".claude/CLAUDE.md")).exists().content().isEqualTo("공통 지침");
        assertThat(repoRoot.resolve(".claude/agents/reviewer.md")).exists();
        assertThat(repoRoot.resolve(".claude/commands/review.md")).exists();
        assertThat(repoRoot.resolve(".claude/rulepack/rules/common/security.md")).exists();
        assertThat(repoRoot.resolve(".claude/rulepack/rules/team")).doesNotExist();
    }

    @Test
    void doesNothingWhenRulepackDirMissing(@TempDir Path root) throws IOException {
        Path repoRoot = root.resolve("repo");
        Files.createDirectories(repoRoot);

        injector.inject(root.resolve("does-not-exist"), repoRoot, List.of("common"));

        assertThat(repoRoot.resolve(".claude")).doesNotExist();
    }
}
