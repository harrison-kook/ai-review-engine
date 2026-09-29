package com.tororang.review.stack.gradlespring;

import com.tororang.review.core.util.ProcessExecutor;
import com.tororang.review.core.util.ProcessOutcome;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 대상 레포의 gradlew(.bat)를 실행한다.
 */
public class GradleWrapperRunner {

    private final Duration timeout;

    public GradleWrapperRunner() {
        this(Duration.ofMinutes(10));
    }

    public GradleWrapperRunner(Duration timeout) {
        this.timeout = timeout;
    }

    public ProcessResult run(Path repoRoot, String... gradleArgs) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String wrapper = windows ? "gradlew.bat" : "gradlew";
        Path wrapperPath = repoRoot.resolve(wrapper);

        // 대상 레포가 Windows에서 커밋됐거나 core.filemode=false로 관리된 경우 gradlew의
        // 실행 비트가 빠진 채로 체크아웃될 수 있다 (샌드박스는 항상 Linux 컨테이너).
        if (!windows) {
            wrapperPath.toFile().setExecutable(true);
        }

        List<String> command = new ArrayList<>();
        command.add(wrapperPath.toString());
        command.add("--console=plain");
        command.addAll(List.of(gradleArgs));

        ProcessOutcome outcome = ProcessExecutor.run(repoRoot, timeout, command);
        return new ProcessResult(outcome.exitCode(), outcome.output(), outcome.timedOut());
    }
}
