package com.tororang.review.core.rule;

import com.tororang.review.core.model.Severity;

/**
 * rules/**&#47;*.md 에서 스캔한 규칙 1건. severity는 규칙 md의 "- 심각도: X" 값이며,
 * .review.yml의 overrides로 최종 조정될 수 있다.
 */
public record RuleDefinition(String ruleId, Severity severity, String sourceFile) {
}
