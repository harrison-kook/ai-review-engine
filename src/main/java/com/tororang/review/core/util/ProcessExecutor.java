package com.tororang.review.core.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
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

        // 자식 프로세스의 stdin에 EOF를 바로 보낸다. 그렇지 않으면 stdin을 기다리는 CLI(예: claude -p)가
        // 몇 초간 대기하다 경고 문구를 stdout에 섞어 출력해 이후 파싱을 깨뜨릴 수 있다.
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // 이미 닫혔거나 프로세스가 즉시 종료된 경우
        }

        // stdout을 별도 스레드에서 읽는다. readAllBytes()를 메인 스레드에서 바로 부르면 자식이
        // stdout을 오래 열어둘 때 waitFor(timeout)까지 도달하지 못해 타임아웃이 영영 발동하지 않는다.
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                in.transferTo(buffer);
            } catch (IOException e) {
                // 타임아웃으로 강제 종료되면 스트림이 끊기는 것은 정상적인 상황
            }
        }, "process-output-reader");
        reader.setDaemon(true);
        reader.start();

        boolean finished;
        try {
            finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IllegalStateException("interrupted while waiting for process: " + command, e);
        }

        if (!finished) {
            log.warn("process timed out after {}, killing process tree: {}", timeout, command);
            destroyTree(process);
        }

        try {
            reader.join(Duration.ofSeconds(5).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        String output = buffer.toString(StandardCharsets.UTF_8);
        if (!finished) {
            return new ProcessOutcome(-1, output, true);
        }
        return new ProcessOutcome(process.exitValue(), output, false);
    }

    /**
     * Process.destroyForcibly()는 Windows에서 직접 자식만 죽인다 — claude.cmd처럼 배치 스크립트가
     * 또 다른 실행파일(cmd.exe → node/claude.exe)을 띄우는 경우 손자 프로세스가 고아로 남는다.
     * descendants()로 트리 전체를 찾아서 개별적으로 강제 종료한다.
     */
    private static void destroyTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
