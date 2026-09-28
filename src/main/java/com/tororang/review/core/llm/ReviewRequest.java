package com.tororang.review.core.llm;

import java.nio.file.Path;
import java.time.Duration;

/**
 * reviewer 에이전트 호출 요청. 룰팩은 이미 workingDirectory의 .claude/ 로 주입되어 있다고 가정한다
 * (설계서 1.1, 3장 ②단계). 이 요청은 "어디서, 어떤 슬래시 커맨드로" 실행할지만 담는다.
 */
public record ReviewRequest(Path workingDirectory, String command, Duration timeout) {

    public ReviewRequest(Path workingDirectory) {
        this(workingDirectory, "/review", Duration.ofMinutes(5));
    }
}
