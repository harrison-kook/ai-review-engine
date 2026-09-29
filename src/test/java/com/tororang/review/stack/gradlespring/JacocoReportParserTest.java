package com.tororang.review.stack.gradlespring;

import com.tororang.review.core.stack.CoverageReport;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class JacocoReportParserTest {

    private final JacocoReportParser parser = new JacocoReportParser();

    @Test
    void usesReportLevelCounterNotFirstNestedMethodCounter() {
        // 실사용 중 실제로 발생한 버그를 그대로 재현: 첫 번째 <method>의 LINE 카운터는
        // missed=2/covered=0(0%)인데, report 전체 합계는 missed=4/covered=10(71.4%)다.
        // getElementsByTagName("counter")로 문서 전체를 재귀 검색하면 method 레벨 카운터를
        // 먼저 만나서 항상 0%를 돌려주는 버그가 있었다.
        String xml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <report name="sample-target">
                  <sessioninfo id="x" start="1" dump="2"/>
                  <package name="com/example">
                    <class name="com/example/PaymentClient">
                      <method name="&lt;init&gt;" desc="()V" line="3">
                        <counter type="INSTRUCTION" missed="6" covered="0"/>
                        <counter type="LINE" missed="2" covered="0"/>
                      </method>
                      <counter type="LINE" missed="4" covered="0"/>
                    </class>
                    <class name="com/example/PaymentService">
                      <method name="approve" desc="(Ljava/lang/String;)V" line="16">
                        <counter type="LINE" missed="0" covered="5"/>
                      </method>
                      <counter type="LINE" missed="0" covered="10"/>
                    </class>
                    <counter type="LINE" missed="4" covered="10"/>
                  </package>
                  <counter type="INSTRUCTION" missed="20" covered="47"/>
                  <counter type="LINE" missed="4" covered="10"/>
                  <counter type="METHOD" missed="2" covered="3"/>
                  <counter type="CLASS" missed="1" covered="1"/>
                </report>
                """;

        CoverageReport report = parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        assertThat(report.coveredLines()).isEqualTo(10);
        assertThat(report.missedLines()).isEqualTo(4);
        assertThat(report.lineCoveragePercent()).isCloseTo(71.43, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void returnsEmptyWhenNoReportLevelLineCounter() {
        String xml = """
                <report name="empty">
                  <sessioninfo id="x" start="1" dump="2"/>
                </report>
                """;

        CoverageReport report = parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        assertThat(report).isEqualTo(CoverageReport.EMPTY);
    }

    @Test
    void returnsZeroPercentWhenNoLinesExistAtAll() {
        String xml = """
                <report name="no-lines">
                  <counter type="LINE" missed="0" covered="0"/>
                </report>
                """;

        CoverageReport report = parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        assertThat(report.lineCoveragePercent()).isEqualTo(0.0);
        assertThat(report.coveredLines()).isEqualTo(0);
        assertThat(report.missedLines()).isEqualTo(0);
    }
}