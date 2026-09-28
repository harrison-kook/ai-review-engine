package com.tororang.review.stack.gradlespring;

import com.tororang.review.core.model.FindingCandidate;
import com.tororang.review.core.model.Severity;
import com.tororang.review.core.model.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PmdReportParserTest {

    private final PmdReportParser parser = new PmdReportParser();

    @Test
    void parsesViolationsAndMapsPriorityToSeverity() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <pmd version="7.5.0">
                  <file name="/repo/src/main/java/com/example/OrderService.java">
                    <violation beginline="10" endline="10" rule="UnusedLocalVariable"
                               ruleset="Best Practices" priority="3">
                      Avoid unused local variables such as 'x'.
                    </violation>
                  </file>
                </pmd>
                """;

        List<FindingCandidate> candidates = parser.parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), Path.of("/repo"));

        assertThat(candidates).hasSize(1);
        FindingCandidate candidate = candidates.get(0);
        assertThat(candidate.ruleId()).isEqualTo("UnusedLocalVariable");
        assertThat(candidate.severity()).isEqualTo(Severity.MEDIUM);
        assertThat(candidate.source()).isEqualTo(Source.PMD);
        assertThat(candidate.file()).isEqualTo("src/main/java/com/example/OrderService.java");
        assertThat(candidate.line()).isEqualTo(10);
        assertThat(candidate.message()).contains("unused local variables");
    }
}
