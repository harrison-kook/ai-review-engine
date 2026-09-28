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
        String wrapper = System.getProperty("os.name", "").toLowerCase().contains("win") ? "gradlew.bat" : "gradlew";
        Path wrapperPath = repoRoot.resolve(wrapper);

        List<String> command = new ArrayList<>();
        command.add(wrapperPath.toString());
        command.add("--console=plain");
        command.addAll(List.of(gradleArgs));

        ProcessOutcome outcome = ProcessExecutor.run(repoRoot, timeout, command);
        return new ProcessResult(outcome.exitCode(), outcome.output(), outcome.timedOut());
    }
}
