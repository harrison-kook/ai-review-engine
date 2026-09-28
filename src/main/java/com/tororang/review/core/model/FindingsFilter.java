package com.tororang.review.core.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 규칙: ruleId와 evidence가 없는 지적은 렌더링 전에 자동 폐기한다 (설계서 7.3).
 * 같은 fingerprint를 가진 후보는 먼저 나온 것만 남긴다.
 */
public final class FindingsFilter {

    private static final Logger log = LoggerFactory.getLogger(FindingsFilter.class);

    private FindingsFilter() {
    }

    public static List<Finding> filter(List<FindingCandidate> candidates) {
        Map<String, Finding> byFingerprint = new LinkedHashMap<>();
        for (FindingCandidate candidate : candidates) {
            if (isBlank(candidate.ruleId()) || isBlank(candidate.evidence())) {
                log.warn("discarding finding without ruleId/evidence: ruleId='{}', file='{}', line={}",
                        candidate.ruleId(), candidate.file(), candidate.line());
                continue;
            }
            String fingerprint = Fingerprint.of(candidate.ruleId(), candidate.file(), candidate.evidence());
            byFingerprint.putIfAbsent(fingerprint, new Finding(
                    candidate.ruleId(),
                    candidate.severity(),
                    candidate.source(),
                    candidate.file(),
                    candidate.line(),
                    candidate.message(),
                    candidate.evidence(),
                    candidate.suggestion(),
                    fingerprint
            ));
        }
        return new ArrayList<>(byFingerprint.values());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
