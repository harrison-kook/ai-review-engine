package com.tororang.review.core.pipeline;

/**
 * testcases/*.md 의 TC-ID 하나에 대한 최종 판정. "테스트케이스 리스트별 결과" 산출물의 단위.
 */
public record TestCaseReport(
        String tcId,
        String title,
        Status status,
        String className,
        String methodName,
        String message
) {
    public enum Status {
        /** 테스트가 생성/식별되어 실행됐고 통과함. */
        PASSED,
        /** 테스트가 생성/식별되어 실행됐지만 실패함(또는 실행 결과를 찾지 못함=컴파일 실패 등). */
        FAILED,
        /** tester가 이 TC를 다루지 않았거나(카탈로그에만 있음) skipped로 표시함. */
        NOT_GENERATED
    }
}
