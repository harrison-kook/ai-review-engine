package com.tororang.review.core.model;

import java.util.Objects;

/**
 * 모든 결과(LLM 리뷰, Checkstyle/PMD/SpotBugs, 테스트 실패)의 표준 출력 형식.
 * ruleId/evidence가 비어 있는 인스턴스는 만들 수 없다 — {@link FindingCandidate}와
 * {@link FindingsFilter}를 거쳐야 하며, 근거 없는 지적은 그 단계에서 폐기된다.
 */
public record Finding(
        String ruleId,
        Severity severity,
        Source source,
        String file,
        int line,
        String message,
        String evidence,
        String suggestion,
        String fingerprint
) {
    public Finding {
        requireNonBlank(ruleId, "ruleId");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(source, "source");
        requireNonBlank(file, "file");
        if (line < 1) {
            throw new IllegalArgumentException("line must be >= 1, got " + line);
        }
        requireNonBlank(message, "message");
        requireNonBlank(evidence, "evidence");
        requireNonBlank(fingerprint, "fingerprint");
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
