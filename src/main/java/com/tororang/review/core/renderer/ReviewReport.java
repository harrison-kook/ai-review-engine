package com.tororang.review.core.renderer;

import com.tororang.review.core.config.ReviewConfig;
import com.tororang.review.core.model.Finding;
import com.tororang.review.core.model.Severity;

import java.util.List;

/**
 * 파이프라인 실행 결과. findings는 이미 필터링/디듑(FindingsFilter)과 diff 범위 제한을
 * 거친 최종 목록이다.
 */
public record ReviewReport(List<Finding> findings, ReviewConfig config) {

    public ReviewReport {
        findings = List.copyOf(findings);
    }

    /**
     * gate.fail_on 심각도 이상의 지적이 하나라도 있으면 게이트 실패.
     * Severity는 HIGH, MEDIUM, LOW, INFO 순으로 선언되어 있어 ordinal이 낮을수록 심각하다.
     */
    public boolean gateFailed() {
        if (!config.gate().isEnabled()) {
            return false;
        }
        Severity threshold = config.gate().failOn();
        return findings.stream().anyMatch(f -> f.severity().ordinal() <= threshold.ordinal());
    }
}
