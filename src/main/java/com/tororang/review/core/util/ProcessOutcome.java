package com.tororang.review.core.util;

public record ProcessOutcome(int exitCode, String output, boolean timedOut) {

    public boolean success() {
        return !timedOut && exitCode == 0;
    }
}
