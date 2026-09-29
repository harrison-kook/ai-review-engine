package com.tororang.review.core.stack;

import java.util.List;

public record TestResult(boolean success, int totalTests, int failedTests, List<TestCaseResult> cases, String output) {

    public static final TestResult NONE = new TestResult(true, 0, 0, List.of(), "no tests executed");

    public TestResult {
        cases = List.copyOf(cases);
    }
}
