package com.tororang.review.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tororang.review.core.llm.LlmClientException;
import com.tororang.review.core.llm.ReviewRequest;
import com.tororang.review.core.llm.ReviewResult;
import com.tororang.review.core.model.FindingCandidate;
import com.tororang.review.core.model.Severity;
import com.tororang.review.core.model.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 claude CLI 대신, 같은 --output-format json 계약을 흉내 내는 가짜 실행 파일로 테스트한다.
 * 배치 스크립트에서 JSON 이스케이프를 직접 다루지 않도록, 응답 본문은 별도 파일(response.json)에
 * Jackson으로 정확히 직렬화해 두고 스크립트는 그 파일 내용을 그대로 출력(type/cat)한다.
 */
class ClaudeCodeCliClientTest {

    private final ObjectMapper jsonMapper = new ObjectMapper();

    @Test
    void parsesFindingsFromSuccessfulResponse(@TempDir Path dir) throws IOException {
        String findingsJson = """
                [
                  {"ruleId":"JPA-003","severity":"HIGH","source":"LLM","file":"src/main/java/OrderService.java",
                   "line":87,"message":"N+1 발생","evidence":"orders.forEach(o -> o.getItems().size());",
                   "suggestion":"fetch join 사용"}
                ]
                """;
        Path fakeClaude = writeFakeClaude(dir, Map.of(
                "is_error", false,
                "result", findingsJson
        ));

        ClaudeCodeCliClient client = new ClaudeCodeCliClient(fakeClaude.toString());
        ReviewResult result = client.review(new ReviewRequest(dir, "/review", Duration.ofSeconds(30)));

        assertThat(result.candidates()).hasSize(1);
        FindingCandidate candidate = result.candidates().get(0);
        assertThat(candidate.ruleId()).isEqualTo("JPA-003");
        assertThat(candidate.severity()).isEqualTo(Severity.HIGH);
        assertThat(candidate.source()).isEqualTo(Source.LLM);
        assertThat(candidate.evidence()).contains("orders.forEach");
    }

    @Test
    void stripsMarkdownCodeFencesFromResult(@TempDir Path dir) throws IOException {
        String fenced = "```json\n[{\"ruleId\":\"SEC-001\",\"severity\":\"HIGH\",\"source\":\"LLM\","
                + "\"file\":\"A.java\",\"line\":1,\"message\":\"m\",\"evidence\":\"e\",\"suggestion\":\"s\"}]\n```";
        Path fakeClaude = writeFakeClaude(dir, Map.of(
                "is_error", false,
                "result", fenced
        ));

        ClaudeCodeCliClient client = new ClaudeCodeCliClient(fakeClaude.toString());
        ReviewResult result = client.review(new ReviewRequest(dir, "/review", Duration.ofSeconds(30)));

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).ruleId()).isEqualTo("SEC-001");
    }

    @Test
    void throwsWhenIsErrorTrue(@TempDir Path dir) throws IOException {
        Path fakeClaude = writeFakeClaude(dir, Map.of(
                "is_error", true,
                "result", "boom"
        ));

        ClaudeCodeCliClient client = new ClaudeCodeCliClient(fakeClaude.toString());

        assertThatThrownBy(() -> client.review(new ReviewRequest(dir, "/review", Duration.ofSeconds(30))))
                .isInstanceOf(LlmClientException.class);
    }

    private Path writeFakeClaude(Path dir, Map<String, Object> envelope) throws IOException {
        Path responseFile = dir.resolve("response.json");
        Files.writeString(responseFile, jsonMapper.writeValueAsString(envelope));

        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path script = dir.resolve(windows ? "fake-claude.bat" : "fake-claude.sh");
        String content = windows
                ? "@echo off\r\ntype \"" + responseFile + "\"\r\n"
                : "#!/bin/sh\ncat \"" + responseFile + "\"\n";
        Files.writeString(script, content);
        if (!windows) {
            script.toFile().setExecutable(true);
        }
        return script;
    }
}
