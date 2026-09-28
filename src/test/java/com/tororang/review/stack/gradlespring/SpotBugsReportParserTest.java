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

class SpotBugsReportParserTest {

    private final SpotBugsReportParser parser = new SpotBugsReportParser();

    @Test
    void parsesBugInstancesAndMapsPriorityToSeverity() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <BugCollection version="4.8.6">
                  <BugInstance type="DLS_DEAD_LOCAL_STORE" priority="1" rank="18" category="STYLE">
                    <ShortMessage>Dead local store</ShortMessage>
                    <LongMessage>Dead store to x in com.example.OrderService.approve()</LongMessage>
                    <SourceLine classname="com.example.OrderService" start="42" end="42"
                                sourcefile="OrderService.java" sourcepath="com/example/OrderService.java"/>
                  </BugInstance>
                </BugCollection>
                """;

        List<FindingCandidate> candidates = parser.parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), Path.of("/repo"));

        assertThat(candidates).hasSize(1);
        FindingCandidate candidate = candidates.get(0);
        assertThat(candidate.ruleId()).isEqualTo("DLS_DEAD_LOCAL_STORE");
        assertThat(candidate.severity()).isEqualTo(Severity.HIGH);
        assertThat(candidate.source()).isEqualTo(Source.SPOTBUGS);
        assertThat(candidate.file()).isEqualTo("src/main/java/com/example/OrderService.java");
        assertThat(candidate.line()).isEqualTo(42);
        assertThat(candidate.message()).contains("Dead store to x");
    }
}
