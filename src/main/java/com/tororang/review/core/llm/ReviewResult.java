package com.tororang.review.core.llm;

import com.tororang.review.core.model.FindingCandidate;

import java.util.List;

/**
 * reviewer 에이전트가 낸 Findings 원문. ruleId/evidence 필터링과 fingerprint 계산은
 * 아직 하지 않은 상태 — {@link com.tororang.review.core.model.FindingsFilter}를 거쳐야 한다.
 */
public record ReviewResult(List<FindingCandidate> candidates, String rawOutput) {
}
