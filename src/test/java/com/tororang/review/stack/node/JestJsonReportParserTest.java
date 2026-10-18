package com.tororang.review.stack.node;

import com.tororang.review.core.stack.TestCaseResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JestJsonReportParserTest {

    private final JestJsonReportParser parser = new JestJsonReportParser();

    @Test
    void parsesPassingAndFailingTestCases(@TempDir Path tempDir) throws IOException {
        String json = """
                {
                  "numTotalTests": 2,
                  "numFailedTests": 1,
                  "success": false,
                  "testResults": [
                    {
                      "name": "/repo/src/components/ProductVariantSelector.test.tsx",
                      "assertionResults": [
                        {
                          "fullName": "ProductVariantSelector matches price when option changes",
                          "status": "passed",
                          "failureMessages": []
                        },
                        {
                          "fullName": "ProductVariantSelector shows sold out when stock is zero",
                          "status": "failed",
                          "failureMessages": ["expected 품절 but received 재고 100개"]
                        }
                      ]
                    }
                  ]
                }
                """;
        Path reportFile = tempDir.resolve("node-test-report.json");
        Files.writeString(reportFile, json);

        List<TestCaseResult> results = parser.parse(reportFile, Path.of("/repo"));

        assertThat(results).hasSize(2);

        TestCaseResult passing = results.get(0);
        assertThat(passing.className()).isEqualTo("src/components/ProductVariantSelector.test.tsx");
        assertThat(passing.methodName()).isEqualTo("ProductVariantSelector matches price when option changes");
        assertThat(passing.passed()).isTrue();
        assertThat(passing.failureMessage()).isNull();

        TestCaseResult failing = results.get(1);
        assertThat(failing.methodName()).isEqualTo("ProductVariantSelector shows sold out when stock is zero");
        assertThat(failing.passed()).isFalse();
        assertThat(failing.failureMessage()).isEqualTo("expected 품절 but received 재고 100개");
    }

    @Test
    void treatsPendingTestsAsNotFailed(@TempDir Path tempDir) throws IOException {
        String json = """
                {
                  "numTotalTests": 1,
                  "numFailedTests": 0,
                  "success": true,
                  "testResults": [
                    {
                      "name": "/repo/src/skipped.test.ts",
                      "assertionResults": [
                        { "fullName": "skipped test", "status": "pending", "failureMessages": [] }
                      ]
                    }
                  ]
                }
                """;
        Path reportFile = tempDir.resolve("node-test-report.json");
        Files.writeString(reportFile, json);

        List<TestCaseResult> results = parser.parse(reportFile, Path.of("/repo"));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).passed()).isTrue();
    }

    @Test
    void returnsEmptyListForEmptyReport(@TempDir Path tempDir) throws IOException {
        Path reportFile = tempDir.resolve("node-test-report.json");
        Files.writeString(reportFile, "{\"testResults\":[]}");

        assertThat(parser.parse(reportFile, Path.of("/repo"))).isEmpty();
    }
}
