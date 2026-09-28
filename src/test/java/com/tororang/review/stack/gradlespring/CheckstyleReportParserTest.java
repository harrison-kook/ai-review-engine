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

class CheckstyleReportParserTest {

    private final CheckstyleReportParser parser = new CheckstyleReportParser();

    @Test
    void parsesErrorsAndMapsSeverity() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <checkstyle version="10.17.0">
                  <file name="/repo/src/main/java/com/example/OrderService.java">
                    <error line="87" column="5" severity="error" message="Missing braces"
                           source="com.puppycrawl.tools.checkstyle.checks.blocks.NeedBracesCheck"/>
                    <error line="12" severity="warning" message="Line too long"
                           source="com.puppycrawl.tools.checkstyle.checks.sizes.LineLengthCheck"/>
                  </file>
                </checkstyle>
                """;

        List<FindingCandidate> candidates = parser.parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), Path.of("/repo"));

        assertThat(candidates).hasSize(2);
        FindingCandidate first = candidates.get(0);
        assertThat(first.ruleId()).isEqualTo("NeedBracesCheck");
        assertThat(first.severity()).isEqualTo(Severity.HIGH);
        assertThat(first.source()).isEqualTo(Source.CHECKSTYLE);
        assertThat(first.file()).isEqualTo("src/main/java/com/example/OrderService.java");
        assertThat(first.line()).isEqualTo(87);

        assertThat(candidates.get(1).severity()).isEqualTo(Severity.MEDIUM);
    }
}
