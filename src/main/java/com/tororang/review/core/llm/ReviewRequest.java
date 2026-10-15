package com.tororang.review.core.llm;

import com.tororang.review.core.config.ReviewMode;
import com.tororang.review.core.rule.RuleDefinition;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * reviewer 에이전트 호출 요청. 룰팩은 이미 workingDirectory의 .claude/ 로 주입되어 있다고 가정한다
 * (설계서 1.1, 3장 ②단계) — Claude Code CLI 기반 구현체는 activeRules/mode/diffBase를 쓰지 않고
 * Read/Grep/Glob으로 직접 읽는다. 도구 호출을 못 하는 구현체(예: Ollama 기반)는 이 필드들로
 * 규칙 본문과 diff 범위를 프롬프트에 직접 넣어야 한다.
 */
public record ReviewRequest(
        Path workingDirectory,
        String command,
        Duration timeout,
        List<RuleDefinition> activeRules,
        ReviewMode mode,
        String diffBase
) {

    public ReviewRequest(Path workingDirectory) {
        this(workingDirectory, "/review", Duration.ofMinutes(5), List.of(), null, null);
    }

    public ReviewRequest(Path workingDirectory, String command, Duration timeout) {
        this(workingDirectory, command, timeout, List.of(), null, null);
    }
}
