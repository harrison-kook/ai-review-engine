package com.tororang.review.core.stack;

/**
 * JUnit XML의 &lt;testcase&gt; 하나에 대응하는 개별 결과. tester 에이전트가 생성한 테스트를
 * TC-ID와 상관관계 짓기 위해 className+methodName으로 식별한다.
 */
public record TestCaseResult(String className, String methodName, boolean passed, String failureMessage) {
}
