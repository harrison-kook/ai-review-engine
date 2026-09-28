package com.tororang.review.core.model;

/**
 * reviewer 에이전트(LLM)나 결정적 도구가 만들어낸, 아직 검증되지 않은 지적.
 * ruleId/evidence가 비어 있을 수 있으며 {@link FindingsFilter#filter}를 거쳐야
 * 최종 {@link Finding}이 된다.
 */
public record FindingCandidate(
        String ruleId,
        Severity severity,
        Source source,
        String file,
        int line,
        String message,
        String evidence,
        String suggestion
) {
}
