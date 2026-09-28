package com.tororang.review.core.config;

public enum ReviewMode {
    DIFF,
    FULL;

    public static ReviewMode from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ReviewConfigException("mode is required (diff|full)");
        }
        return switch (raw.trim().toLowerCase()) {
            case "diff" -> DIFF;
            case "full" -> FULL;
            default -> throw new ReviewConfigException("unknown mode: " + raw);
        };
    }
}
