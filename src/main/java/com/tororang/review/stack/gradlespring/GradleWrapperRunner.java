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
        // 샌드박스는 매 phase마다 새 컨테이너로 뜨는 1회성 실행이라 데몬 재사용 이점이 없고,
        // 컨테이너 종료 시 데몬이 완전히 죽지 않은 채로 남아 다음 컨테이너의 ~/.gradle 캐시
        // 잠금(journal-1.lock)과 충돌하는 문제가 있어 항상 끈다.
        command.add("--no-daemon");
        command.addAll(List.of(gradleArgs));

        ProcessOutcome outcome = ProcessExecutor.run(repoRoot, timeout, command);
        return new ProcessResult(outcome.exitCode(), outcome.output(), outcome.timedOut());
    }
}
