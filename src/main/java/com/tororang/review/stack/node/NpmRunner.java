package com.tororang.review.stack.node;

import com.tororang.review.core.util.ProcessExecutor;
import com.tororang.review.core.util.ProcessOutcome;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 대상 레포의 npm/npx를 실행한다. Windows에서는 ProcessBuilder가 셸 없이 .cmd 배치 파일을
 * 바로 실행할 수 있도록 실행 파일명에 확장자를 명시한다 (GradleWrapperRunner의
 * gradlew/gradlew.bat 분기와 같은 이유).
 */
public class NpmRunner {

    private final Duration timeout;

    public NpmRunner() {
        this(Duration.ofMinutes(10));
    }

    public NpmRunner(Duration timeout) {
        this.timeout = timeout;
    }

    public ProcessOutcome npm(Path repoRoot, String... args) {
        return run(repoRoot, npmExecutable(), args);
    }

    public ProcessOutcome npx(Path repoRoot, String... args) {
        return run(repoRoot, npxExecutable(), args);
    }

    private ProcessOutcome run(Path repoRoot, String executable, String... args) {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(List.of(args));
        return ProcessExecutor.run(repoRoot, timeout, command);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static String npmExecutable() {
        return isWindows() ? "npm.cmd" : "npm";
    }

    private static String npxExecutable() {
        return isWindows() ? "npx.cmd" : "npx";
    }
}
