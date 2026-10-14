package com.tororang.review.stack.node;

import com.tororang.review.core.model.FindingCandidate;
import com.tororang.review.core.model.Severity;
import com.tororang.review.core.model.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EsLintReportParserTest {

    private final EsLintReportParser parser = new EsLintReportParser();

    @Test
    void parsesMessagesAndMapsSeverity(@TempDir Path tempDir) throws IOException {
        String json = """
                [
                  {
                    "filePath": "/repo/src/components/UserCard.jsx",
                    "messages": [
                      { "ruleId": "react-hooks/exhaustive-deps", "severity": 2, "message": "Missing dependency", "line": 14 },
                      { "ruleId": "no-unused-vars", "severity": 1, "message": "'x' is defined but never used.", "line": 3 },
                      { "ruleId": null, "severity": 2, "message": "Parsing error: Unexpected token", "line": 1 }
                    ]
                  }
                ]
                """;
        Path reportFile = tempDir.resolve("eslint-report.json");
        Files.writeString(reportFile, json);

        List<FindingCandidate> candidates = parser.parse(reportFile, Path.of("/repo"));

        assertThat(candidates).hasSize(3);

        FindingCandidate first = candidates.get(0);
        assertThat(first.ruleId()).isEqualTo("react-hooks/exhaustive-deps");
        assertThat(first.severity()).isEqualTo(Severity.HIGH);
        assertThat(first.source()).isEqualTo(Source.ESLINT);
        assertThat(first.file()).isEqualTo("src/components/UserCard.jsx");
        assertThat(first.line()).isEqualTo(14);
        assertThat(first.evidence()).isEqualTo("Missing dependency");

        assertThat(candidates.get(1).severity()).isEqualTo(Severity.MEDIUM);

        FindingCandidate thirdWithoutRuleId = candidates.get(2);
        assertThat(thirdWithoutRuleId.ruleId()).isEqualTo("ESLINT");
    }

    @Test
    void returnsEmptyListForEmptyReport(@TempDir Path tempDir) throws IOException {
        Path reportFile = tempDir.resolve("eslint-report.json");
        Files.writeString(reportFile, "[]");

        assertThat(parser.parse(reportFile, Path.of("/repo"))).isEmpty();
    }
}
