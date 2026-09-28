package com.tororang.review.core.config;

import java.util.List;

/**
 * 레포 루트의 .review.yml (schema/review-config.schema.json 대응).
 */
public record ReviewConfig(
        String rulepack,
        List<String> profiles,
        ReviewMode mode,
        List<String> include,
        List<String> exclude,
        Overrides overrides,
        Limits limits,
        Gate gate
) {
    public ReviewConfig {
        if (rulepack == null || rulepack.isBlank()) {
            throw new ReviewConfigException("rulepack is required");
        }
        if (profiles == null || profiles.isEmpty()) {
            throw new ReviewConfigException("profiles must not be empty");
        }
        if (mode == null) {
            throw new ReviewConfigException("mode is required");
        }
        profiles = List.copyOf(profiles);
        include = include == null ? List.of() : List.copyOf(include);
        exclude = exclude == null ? List.of() : List.copyOf(exclude);
        overrides = overrides == null ? Overrides.EMPTY : overrides;
        limits = limits == null ? Limits.EMPTY : limits;
        gate = gate == null ? Gate.DISABLED : gate;
    }
}
