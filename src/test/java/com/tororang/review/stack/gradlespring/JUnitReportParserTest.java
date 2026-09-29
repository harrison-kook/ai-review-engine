package com.tororang.review.stack.gradlespring;

import com.tororang.review.core.stack.TestCaseResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JUnitReportParserTest {

    private final JUnitReportParser parser = new JUnitReportParser();

    @Test
    void parsesPassingAndFailingTestCases() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.PaymentServiceTest" tests="2" skipped="0" failures="1" errors="0">
                  <testcase name="duplicateApprove_isIdempotent" classname="com.example.PaymentServiceTest" time="0.01"/>
                  <testcase name="cancel_exceedsAmount_throws" classname="com.example.PaymentServiceTest" time="0.02">
                    <failure message="expected exception but none thrown" type="org.opentest4j.AssertionFailedError">
                      stack trace here
                    </failure>
                  </testcase>
                </testsuite>
                """;

        List<TestCaseResult> results = parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        assertThat(results).hasSize(2);
        TestCaseResult passing = results.get(0);
        assertThat(passing.methodName()).isEqualTo("duplicateApprove_isIdempotent");
        assertThat(passing.className()).isEqualTo("com.example.PaymentServiceTest");
        assertThat(passing.passed()).isTrue();
        assertThat(passing.failureMessage()).isNull();

        TestCaseResult failing = results.get(1);
        assertThat(failing.methodName()).isEqualTo("cancel_exceedsAmount_throws");
        assertThat(failing.passed()).isFalse();
        assertThat(failing.failureMessage()).isEqualTo("expected exception but none thrown");
    }

    @Test
    void stripsTrailingParenthesesFromNoArgMethodNames() {
        // JUnit5의 Gradle XML 리포트는 무인자 메서드의 name 속성에 "()"를 붙인다. tester가 보고하는
        // methodName에는 괄호가 없어서, 벗겨내지 않으면 TC-ID 상관관계 매칭이 항상 깨진다
        // (실사용 중 실제로 발생: 2건 실행됐는데 둘 다 "결과에서 찾을 수 없음"으로 오판정됨).
        String xml = """
                <testsuite name="com.example.PaymentServiceTest" tests="1" failures="0" errors="0">
                  <testcase name="duplicateApprove_isIdempotent()" classname="com.example.PaymentServiceTest" time="0.01"/>
                </testsuite>
                """;

        List<TestCaseResult> results = parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).methodName()).isEqualTo("duplicateApprove_isIdempotent");
    }

    @Test
    void treatsErrorElementAsFailure() {
        String xml = """
                <testsuite name="X" tests="1" failures="0" errors="1">
                  <testcase name="throwsUnexpectedException" classname="com.example.X">
                    <error message="NullPointerException" type="java.lang.NullPointerException">trace</error>
                  </testcase>
                </testsuite>
                """;

        List<TestCaseResult> results = parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).passed()).isFalse();
        assertThat(results.get(0).failureMessage()).isEqualTo("NullPointerException");
    }
}
