package com.tororang.review.core.stack;

import java.nio.file.Path;

/**
 * StackAdapter가 작업할 대상 레포의 워크스페이스. 이미 clone되어 있고,
 * 룰팩이 .claude/ 로 주입된 이후의 상태를 가리킨다.
 */
public record Workspace(Path repoRoot) {
}
