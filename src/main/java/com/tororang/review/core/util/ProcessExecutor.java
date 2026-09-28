package com.tororang.review.core.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 외부 프로세스(gradlew, claude CLI 등) 실행 공통 유틸. 샌드박스(Docker) 안에서 실행하는 것을
 * 전제로 한다(설계서 1.3) — 이 클래스 자체는 어떤 컨테이너에서 실행되는지 알지 못한다.
 */
public final class ProcessExecutor {

    private static final Logger log = LoggerFactory.getLogger(ProcessExecutor.class);

    private ProcessExecutor() {
    }

    public static ProcessOutcome run(Path workingDir, Duration timeout, List<String> command) {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workingDir.toFile())
                .redirectErrorStream(true);

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to start process: " + command, e);
        }

        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read process output: " + command, e);
        }

        boolean finished;
        try {
            finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IllegalStateException("interrupted while waiting for process: " + command, e);
        }

        if (!finished) {
            log.warn("process timed out after {}, killing: {}", timeout, command);
            process.destroyForcibly();
            return new ProcessOutcome(-1, output, true);
        }

        return new ProcessOutcome(process.exitValue(), output, false);
    }
}
