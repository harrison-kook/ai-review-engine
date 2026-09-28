package com.tororang.review.core.llm;

import java.nio.file.Path;
import java.time.Duration;

/**
 * tester 에이전트 호출 요청 (로드맵 2단계). 설계서 7.2 인터페이스 정의를 위해 자리만 잡아둔다.
 */
public record TestGenRequest(Path workingDirectory, String command, Duration timeout) {

    public TestGenRequest(Path workingDirectory) {
        this(workingDirectory, "/gen-test", Duration.ofMinutes(10));
    }
}
