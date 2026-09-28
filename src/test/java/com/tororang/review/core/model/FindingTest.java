package com.tororang.review.core.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class FindingTest {

    @Test
    void rejectsBlankRuleId() {
        assertThatThrownBy(() -> new Finding("", Severity.HIGH, Source.LLM,
                "File.java", 1, "msg", "evidence", "suggestion", "fp"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ruleId");
    }

    @Test
    void rejectsBlankEvidence() {
        assertThatThrownBy(() -> new Finding("JPA-003", Severity.HIGH, Source.LLM,
                "File.java", 1, "msg", " ", "suggestion", "fp"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidence");
    }

    @Test
    void rejectsNonPositiveLine() {
        assertThatThrownBy(() -> new Finding("JPA-003", Severity.HIGH, Source.LLM,
                "File.java", 0, "msg", "evidence", "suggestion", "fp"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("line");
    }

    @Test
    void acceptsValidFinding() {
        assertThatCode(() -> new Finding("JPA-003", Severity.HIGH, Source.LLM,
                "File.java", 87, "msg", "evidence", "suggestion", "fp"))
                .doesNotThrowAnyException();
    }
}
