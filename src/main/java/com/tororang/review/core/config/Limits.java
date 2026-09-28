package com.tororang.review.core.config;

public record Limits(Integer maxDiffLines) {

    public static final Limits EMPTY = new Limits(null);

    public boolean hasMaxDiffLines() {
        return maxDiffLines != null;
    }
}
