package com.tororang.review.core.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실사용 중 실제로 걸렸던 버그의 회귀 테스트: readAllBytes()를 waitFor(timeout) 전에 블로킹
 * 호출하면, 자식 프로세스가 오래 살아있는 한 타임아웃이 영영 발동하지 않는다. 이 테스트는
 * 짧은 타임아웃을 주고, 실제 실행 시간이 그보다 훨씬 오래 사는 프로세스보다 빨리 끝나는지
 * (=타임아웃이 실제로 작동하는지) 검증한다.
 */
class ProcessExecutorTest {

    @Test
    void timesOutInsteadOfBlockingUntilProcessExits(@TempDir Path dir) throws IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path script = dir.resolve(windows ? "long-running.bat" : "long-running.sh");
        // 30초 넘게 사는 프로세스를 흉내낸다. Windows의 `timeout` 명령은 stdin 리다이렉션이 있으면
        // 실패하므로(ProcessExecutor가 stdin을 닫는다) `ping`으로 대체한다.
        String content = windows
                ? "@echo off\r\nping -n 31 127.0.0.1 > nul\r\n"
                : "#!/bin/sh\nsleep 30\n";
        Files.writeString(script, content);
        if (!windows) {
            script.toFile().setExecutable(true);
        }

        Instant start = Instant.now();
        ProcessOutcome outcome = ProcessExecutor.run(dir, Duration.ofSeconds(2), List.of(script.toString()));
        Duration elapsed = Duration.between(start, Instant.now());

        assertThat(outcome.timedOut()).isTrue();
        assertThat(outcome.success()).isFalse();
        assertThat(elapsed).isLessThan(Duration.ofSeconds(15));
    }

    @Test
    void returnsOutputAndSuccessWhenProcessFinishesInTime(@TempDir Path dir) throws IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path script = dir.resolve(windows ? "quick.bat" : "quick.sh");
        String content = windows
                ? "@echo off\r\necho hello-from-process\r\n"
                : "#!/bin/sh\necho hello-from-process\n";
        Files.writeString(script, content);
        if (!windows) {
            script.toFile().setExecutable(true);
        }

        ProcessOutcome outcome = ProcessExecutor.run(dir, Duration.ofSeconds(30), List.of(script.toString()));

        assertThat(outcome.timedOut()).isFalse();
        assertThat(outcome.success()).isTrue();
        assertThat(outcome.output()).contains("hello-from-process");
    }
}
