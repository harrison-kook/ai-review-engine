package com.tororang.review.stack.gradlespring;

import com.tororang.review.core.stack.MutationReport;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PitestReportParserTest {

    private final PitestReportParser parser = new PitestReportParser();

    @Test
    void computesMutationScoreFromDetectedAttribute() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <mutations>
                  <mutation detected='true' status='KILLED'>
                    <sourceFile>PaymentService.java</sourceFile>
                    <mutatedClass>com.example.PaymentService</mutatedClass>
                    <mutatedMethod>approve</mutatedMethod>
                    <lineNumber>19</lineNumber>
                    <killingTest>com.example.PaymentServiceTest.duplicateApprove_isIdempotent</killingTest>
                  </mutation>
                  <mutation detected='false' status='SURVIVED'>
                    <sourceFile>PaymentService.java</sourceFile>
                    <mutatedClass>com.example.PaymentService</mutatedClass>
                    <mutatedMethod>isApproved</mutatedMethod>
                    <lineNumber>23</lineNumber>
                  </mutation>
                  <mutation detected='true' status='KILLED'>
                    <sourceFile>PaymentService.java</sourceFile>
                    <mutatedClass>com.example.PaymentService</mutatedClass>
                    <mutatedMethod>approve</mutatedMethod>
                    <lineNumber>20</lineNumber>
                    <killingTest>com.example.PaymentServiceTest.duplicateApprove_isIdempotent</killingTest>
                  </mutation>
                </mutations>
                """;

        MutationReport report = parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        assertThat(report.totalMutations()).isEqualTo(3);
        assertThat(report.killedMutations()).isEqualTo(2);
        assertThat(report.mutationScorePercent()).isCloseTo(66.666, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void returnsEmptyWhenNoMutations() {
        String xml = "<mutations></mutations>";

        MutationReport report = parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        assertThat(report).isEqualTo(MutationReport.EMPTY);
    }
}