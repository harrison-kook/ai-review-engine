package com.tororang.review.core.rule;

import com.tororang.review.core.config.Gate;
import com.tororang.review.core.config.Limits;
import com.tororang.review.core.config.Overrides;
import com.tororang.review.core.config.ReviewConfig;
import com.tororang.review.core.config.ReviewMode;
import com.tororang.review.core.model.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleMergerTest {

    private final RuleMerger merger = new RuleMerger();

    @Test
    void mergesProfilesInOrderAndAppliesOverrides(@TempDir Path root) throws IOException {
        Path rulepackDir = root.resolve("rulepack");
        Path repoRoot = root.resolve("repo");
        Files.createDirectories(repoRoot);
        writeRule(rulepackDir, "common", "security.md", "SEC-001", Severity.HIGH);
        writeRule(rulepackDir, "java-spring", "jpa.md", "JPA-003", Severity.HIGH);
        writeRule(rulepackDir, "team", "our-team.md", "STYLE-010", Severity.LOW);

        ReviewConfig config = new ReviewConfig(
                "our-org/review-rulepack@v0.1.0",
                List.of("common", "java-spring", "team"),
                ReviewMode.DIFF,
                List.of(),
                List.of(),
                new Overrides(List.of("STYLE-010"), Map.of("JPA-003", Severity.MEDIUM)),
                Limits.EMPTY,
                Gate.DISABLED
        );

        List<RuleDefinition> merged = merger.merge(rulepackDir, repoRoot, config);

        assertThat(merged).extracting(RuleDefinition::ruleId).containsExactlyInAnyOrder("SEC-001", "JPA-003");
        assertThat(merged).filteredOn(r -> r.ruleId().equals("JPA-003"))
                .extracting(RuleDefinition::severity)
                .containsExactly(Severity.MEDIUM);
    }

    @Test
    void repoLocalRulesAreAddedAndOverrideSameIdFromRulepack(@TempDir Path root) throws IOException {
        Path rulepackDir = root.resolve("rulepack");
        Path repoRoot = root.resolve("repo");
        writeRule(rulepackDir, "common", "security.md", "SEC-001", Severity.HIGH);

        Path localRules = repoRoot.resolve(".review-rules/rules");
        Files.createDirectories(localRules);
        // repo-local이 마지막 레이어이므로 같은 ID를 다시 정의하면 심각도를 덮어쓴다.
        Files.writeString(localRules.resolve("internal.md"), """
                ## SEC-001: 내부 정책으로 완화
                - 심각도: LOW
                - 검사 방식: LLM

                ## INTERNAL-001: 회사 내부 전용 규칙
                - 심각도: MEDIUM
                - 검사 방식: LLM
                """);

        ReviewConfig config = new ReviewConfig(
                "our-org/review-rulepack@v0.1.0",
                List.of("common"),
                ReviewMode.DIFF,
                List.of(),
                List.of(),
                Overrides.EMPTY,
                Limits.EMPTY,
                Gate.DISABLED
        );

        List<RuleDefinition> merged = merger.merge(rulepackDir, repoRoot, config);

        assertThat(merged).extracting(RuleDefinition::ruleId)
                .containsExactlyInAnyOrder("SEC-001", "INTERNAL-001");
        assertThat(merged).filteredOn(r -> r.ruleId().equals("SEC-001"))
                .extracting(RuleDefinition::severity)
                .containsExactly(Severity.LOW);
    }

    private void writeRule(Path rulepackDir, String profile, String fileName, String ruleId, Severity severity) throws IOException {
        Path dir = rulepackDir.resolve("rules").resolve(profile);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(fileName), """
                ## %s: 테스트 규칙
                - 심각도: %s
                - 검사 방식: LLM
                """.formatted(ruleId, severity));
    }
}
