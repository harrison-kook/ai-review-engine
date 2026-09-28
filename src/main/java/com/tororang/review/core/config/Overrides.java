package com.tororang.review.core.config;

import com.tororang.review.core.model.Severity;

import java.util.List;
import java.util.Map;

/**
 * .review.yml 의 overrides 블록.
 * disable: 완전히 비활성화할 규칙 ID 목록
 * severityOverrides: 규칙 ID -> 낮춘 심각도 (ruleId 키가 그대로 severity 오브젝트를 가리킨다)
 */
public record Overrides(List<String> disable, Map<String, Severity> severityOverrides) {

    public static final Overrides EMPTY = new Overrides(List.of(), Map.of());

    public Overrides {
        disable = disable == null ? List.of() : List.copyOf(disable);
        severityOverrides = severityOverrides == null ? Map.of() : Map.copyOf(severityOverrides);
    }
}
