package com.tororang.review.core.rule;

import com.tororang.review.core.model.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleCatalogTest {

    private final RuleCatalog catalog = new RuleCatalog();

    @Test
    void scansRuleIdsAndSeverityFromMarkdown(@TempDir Path rulepackDir) throws IOException {
        Path commonDir = rulepackDir.resolve("rules/common");
        Files.createDirectories(commonDir);
        Files.writeString(commonDir.resolve("security.md"), """
                # 공통 · 보안 규칙

                ## SEC-001: SQL Injection
                - 심각도: HIGH
                - 검사 방식: LLM

                ## SEC-002: 시크릿 하드코딩
                - 심각도: HIGH
                - 검사 방식: LLM
                """);

        List<RuleDefinition> rules = catalog.scanProfile(rulepackDir, "common");

        assertThat(rules).extracting(RuleDefinition::ruleId).containsExactly("SEC-001", "SEC-002");
        assertThat(rules).allMatch(r -> r.severity() == Severity.HIGH);
    }

    @Test
    void returnsEmptyListWhenProfileDirMissing(@TempDir Path rulepackDir) {
        List<RuleDefinition> rules = catalog.scanProfile(rulepackDir, "does-not-exist");

        assertThat(rules).isEmpty();
    }
}
