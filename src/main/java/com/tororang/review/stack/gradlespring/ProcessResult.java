package com.tororang.review.stack.gradlespring;

public record ProcessResult(int exitCode, String output, boolean timedOut) {

    public boolean success() {
        return !timedOut && exitCode == 0;
    }
}
