package com.tororang.review.core.stack;

public record TestResult(boolean success, int totalTests, int failedTests, String output) {

    public static final TestResult NONE = new TestResult(true, 0, 0, "no tests executed");
}
