package com.tororang.review.core.llm;

/**
 * tester 에이전트(agents/tester.md)가 TC-ID 하나에 대해 낸 결과.
 * status가 GENERATED/ALREADY_COVERED면 className/methodName/testFilePath가 채워지고,
 * SKIPPED면 reason만 채워진다.
 */
public record GeneratedTestCase(
        String tcId,
        GenTestStatus status,
        String className,
        String methodName,
        String testFilePath,
        String reason
) {
}
