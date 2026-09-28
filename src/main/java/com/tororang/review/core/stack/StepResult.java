package com.tororang.review.core.stack;

public record StepResult(boolean success, int exitCode, String output) {

    public static StepResult skipped(String reason) {
        return new StepResult(true, 0, "skipped: " + reason);
    }
}
