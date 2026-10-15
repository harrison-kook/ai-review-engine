package com.tororang.review.llm.ollama;

import com.sun.net.httpserver.HttpServer;
import com.tororang.review.core.config.ReviewMode;
import com.tororang.review.core.llm.LlmClientException;
import com.tororang.review.core.llm.ReviewRequest;
import com.tororang.review.core.llm.ReviewResult;
import com.tororang.review.core.llm.TestGenRequest;
import com.tororang.review.core.model.Severity;
import com.tororang.review.core.model.Source;
import com.tororang.review.core.rule.RuleDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 Ollama 서버 대신 JDK 내장 HttpServer로 /api/generate를 흉내 낸다
 * (ClaudeCodeCliClientTest가 claude CLI를 가짜 실행 파일로 흉내 내는 것과 같은 원칙).
 */
class OllamaLlmClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private int startServerReturning(String responseJson, AtomicReference<String> capturedRequestBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/generate", exchange -> {
            if (capturedRequestBody != null) {
                capturedRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            } else {
                exchange.getRequestBody().readAllBytes();
            }
            byte[] body = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server.getAddress().getPort();
    }

    private ReviewRequest diffRequest(Path dir, List<RuleDefinition> rules) {
        return new ReviewRequest(dir, "/review", Duration.ofSeconds(10), rules, ReviewMode.DIFF, "origin/main");
    }

    @Test
    void parsesFindingsAndUsageFromSuccessfulResponse(@TempDir Path dir) throws IOException {
        Path ruleFile = dir.resolve("sec.md");
        Files.writeString(ruleFile, "## SEC-001: x\n- 심각도: HIGH\n");

        String findingsJson = """
                [{"ruleId":"SEC-001","severity":"HIGH","source":"LLM","file":"A.java","line":5,
                  "message":"문제","evidence":"code","suggestion":"fix"}]
                """.strip();
        String responseJson = """
                {"model":"test-model","response":%s,"done":true,
                 "total_duration":1500000000,"prompt_eval_count":120,"eval_count":40}
                """.formatted("\"" + findingsJson.replace("\"", "\\\"").replace("\n", "") + "\"");

        AtomicReference<String> capturedBody = new AtomicReference<>();
        int port = startServerReturning(responseJson, capturedBody);
        OllamaLlmClient client = new OllamaLlmClient("http://localhost:" + port, "test-model");

        ReviewResult result = client.review(diffRequest(dir, List.of(new RuleDefinition("SEC-001", Severity.HIGH, ruleFile.toString()))));

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).ruleId()).isEqualTo("SEC-001");
        assertThat(result.candidates().get(0).source()).isEqualTo(Source.LLM);
        assertThat(result.usage().durationMs()).isEqualTo(1500L);
        assertThat(result.usage().promptTokens()).isEqualTo(120L);
        assertThat(result.usage().completionTokens()).isEqualTo(40L);
        assertThat(result.usage().costUsd()).isEqualTo(0.0);

        // 규칙 원문과 diff 섹션 헤더가 프롬프트에 그대로 들어갔는지 확인
        assertThat(capturedBody.get()).contains("SEC-001").contains("## 적용 규칙").contains("## 변경된 Diff");
    }

    @Test
    void handlesResponseWrappedInCodeFence(@TempDir Path dir) throws IOException {
        String responseJson = """
                {"response":"여기 결과입니다\\n```json\\n[]\\n```","done":true}
                """;
        int port = startServerReturning(responseJson, null);
        OllamaLlmClient client = new OllamaLlmClient("http://localhost:" + port, "test-model");

        ReviewResult result = client.review(diffRequest(dir, List.of()));

        assertThat(result.candidates()).isEmpty();
    }

    @Test
    void throwsWhenModelNotConfigured(@TempDir Path dir) {
        OllamaLlmClient client = new OllamaLlmClient("http://localhost:1", null);

        assertThatThrownBy(() -> client.review(diffRequest(dir, List.of())))
                .isInstanceOf(LlmClientException.class)
                .hasMessageContaining("OLLAMA_MODEL");
    }

    @Test
    void throwsWhenModeIsNotDiff(@TempDir Path dir) {
        OllamaLlmClient client = new OllamaLlmClient("http://localhost:1", "test-model");
        ReviewRequest request = new ReviewRequest(dir, "/review", Duration.ofSeconds(5), List.of(), ReviewMode.FULL, "origin/main");

        assertThatThrownBy(() -> client.review(request))
                .isInstanceOf(LlmClientException.class)
                .hasMessageContaining("diff");
    }

    @Test
    void wrapsConnectionFailureAsLlmClientException(@TempDir Path dir) {
        // 아무도 듣고 있지 않은 포트로 연결 시도
        OllamaLlmClient client = new OllamaLlmClient("http://localhost:1", "test-model");

        assertThatThrownBy(() -> client.review(diffRequest(dir, List.of())))
                .isInstanceOf(LlmClientException.class)
                .hasMessageContaining("Ollama 서버");
    }

    @Test
    void generateTestsIsUnsupported(@TempDir Path dir) {
        OllamaLlmClient client = new OllamaLlmClient("http://localhost:1", "test-model");

        assertThatThrownBy(() -> client.generateTests(new TestGenRequest(dir)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
