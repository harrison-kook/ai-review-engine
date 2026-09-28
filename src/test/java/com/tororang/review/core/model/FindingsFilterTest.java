package com.tororang.review.core.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FindingsFilterTest {

    private FindingCandidate candidate(String ruleId, String evidence) {
        return new FindingCandidate(ruleId, Severity.HIGH, Source.LLM,
                "src/main/java/com/example/OrderService.java", 87,
                "message", evidence, "suggestion");
    }

    @Test
    void discardsCandidateWithoutRuleId() {
        List<Finding> findings = FindingsFilter.filter(List.of(
                candidate(null, "orders.forEach(o -> o.getItems().size());"),
                candidate("", "orders.forEach(o -> o.getItems().size());")
        ));

        assertThat(findings).isEmpty();
    }

    @Test
    void discardsCandidateWithoutEvidence() {
        List<Finding> findings = FindingsFilter.filter(List.of(
                candidate("JPA-003", null),
                candidate("JPA-003", "  ")
        ));

        assertThat(findings).isEmpty();
    }

    @Test
    void keepsValidCandidateAndComputesFingerprint() {
        List<Finding> findings = FindingsFilter.filter(List.of(
                candidate("JPA-003", "orders.forEach(o -> o.getItems().size());")
        ));

        assertThat(findings).hasSize(1);
        Finding finding = findings.get(0);
        assertThat(finding.ruleId()).isEqualTo("JPA-003");
        assertThat(finding.fingerprint()).isEqualTo(
                Fingerprint.of("JPA-003", "src/main/java/com/example/OrderService.java",
                        "orders.forEach(o -> o.getItems().size());"));
    }

    @Test
    void deduplicatesByFingerprintKeepingFirst() {
        FindingCandidate first = candidate("JPA-003", "orders.forEach(o -> o.getItems().size());");
        FindingCandidate duplicate = candidate("JPA-003", "orders.forEach(o -> o.getItems().size());");

        List<Finding> findings = FindingsFilter.filter(List.of(first, duplicate));

        assertThat(findings).hasSize(1);
    }

    @Test
    void fingerprintNormalizesWhitespaceInEvidence() {
        String a = Fingerprint.of("JPA-003", "File.java", "orders.forEach(o -> o.getItems().size());");
        String b = Fingerprint.of("JPA-003", "File.java", "  orders.forEach(o  ->   o.getItems().size());  ");

        assertThat(a).isEqualTo(b);
    }
}
