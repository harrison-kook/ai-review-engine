package com.tororang.review.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tororang.review.core.llm.GenTestStatus;
import com.tororang.review.core.llm.LlmClientException;
import com.tororang.review.core.llm.ReviewRequest;
import com.tororang.review.core.llm.ReviewResult;
import com.tororang.review.core.llm.TestGenRequest;
import com.tororang.review.core.llm.TestGenResult;
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
    void ignoresUnknownFieldsLikeFingerprintFromLlmOutput(@TempDir Path dir) throws IOException {
        // 실사용 중 실제로 발생한 사례: 프롬프트가 fingerprint를 넣지 말라고 해도 LLM이 넣는 경우가
        // 있다. 엔진이 fingerprint를 직접 계산하므로 모르는 필드는 무시하고 파싱해야 한다.
        String findingsJson = """
                [
                  {"ruleId":"SEC-002","severity":"HIGH","source":"LLM","file":"PaymentClient.java",
                   "line":5,"message":"m","evidence":"e","suggestion":"s","fingerprint":"should-be-ignored"}
                ]
                """;
        Path fakeClaude = writeFakeClaude(dir, Map.of(
                "is_error", false,
                "result", findingsJson
        ));

        ClaudeCodeCliClient client = new ClaudeCodeCliClient(fakeClaude.toString());
        ReviewResult result = client.review(new ReviewRequest(dir, "/review", Duration.ofSeconds(30)));

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).ruleId()).isEqualTo("SEC-002");
    }

    @Test
    void ignoresProseSentenceBeforeCodeFence(@TempDir Path dir) throws IOException {
        // 실사용 중 실제로 발생한 사례: "JSON만 출력하라"는 지시에도 확인 문장을 코드펜스 앞에 붙임.
        String prefaced = "Findings confirmed against source. Per the `/review` output contract, "
                + "final output is the Findings JSON array only:\n\n```json\n"
                + "[{\"ruleId\":\"SEC-002\",\"severity\":\"HIGH\",\"source\":\"LLM\","
                + "\"file\":\"PaymentClient.java\",\"line\":5,\"message\":\"m\",\"evidence\":\"e\",\"suggestion\":\"s\"}]"
                + "\n```";
        Path fakeClaude = writeFakeClaude(dir, Map.of(
                "is_error", false,
                "result", prefaced
        ));

        ClaudeCodeCliClient client = new ClaudeCodeCliClient(fakeClaude.toString());
        ReviewResult result = client.review(new ReviewRequest(dir, "/review", Duration.ofSeconds(30)));

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).ruleId()).isEqualTo("SEC-002");
    }

    @Test
    void ignoresProseSurroundingRawJsonArrayWithoutFences(@TempDir Path dir) throws IOException {
        String prosed = "Here are the findings: "
                + "[{\"ruleId\":\"SEC-001\",\"severity\":\"HIGH\",\"source\":\"LLM\","
                + "\"file\":\"A.java\",\"line\":1,\"message\":\"m\",\"evidence\":\"e\",\"suggestion\":\"s\"}]"
                + " That's all.";
        Path fakeClaude = writeFakeClaude(dir, Map.of(
                "is_error", false,
                "result", prosed
        ));

        ClaudeCodeCliClient client = new ClaudeCodeCliClient(fakeClaude.toString());
        ReviewResult result = client.review(new ReviewRequest(dir, "/review", Duration.ofSeconds(30)));

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).ruleId()).isEqualTo("SEC-001");
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
    void ignoresWarningLinePrintedBeforeJsonEnvelope(@TempDir Path dir) throws IOException {
        // 실제 claude CLI가 "Warning: no stdin data received in 3s, ..." 같은 진단 메시지를
        // --output-format json 인데도 JSON 앞에 stdout으로 섞어 낼 때가 있다 (실사용 중 확인됨).
        String envelope = jsonMapper.writeValueAsString(Map.of(
                "is_error", false,
                "result", "[{\"ruleId\":\"SEC-001\",\"severity\":\"HIGH\",\"source\":\"LLM\","
                        + "\"file\":\"A.java\",\"line\":1,\"message\":\"m\",\"evidence\":\"e\",\"suggestion\":\"s\"}]"
        ));
        String noisy = "Warning: no stdin data received in 3s, proceeding without it.\n" + envelope;

        Path responseFile = dir.resolve("response.json");
        Files.writeString(responseFile, noisy);
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path script = dir.resolve(windows ? "fake-claude.bat" : "fake-claude.sh");
        String content = windows
                ? "@echo off\r\ntype \"" + responseFile + "\"\r\n"
                : "#!/bin/sh\ncat \"" + responseFile + "\"\n";
        Files.writeString(script, content);
        if (!windows) {
            script.toFile().setExecutable(true);
        }

        ClaudeCodeCliClient client = new ClaudeCodeCliClient(script.toString());
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

    @Test
    void parsesGeneratedTestCasesWithAllStatuses(@TempDir Path dir) throws IOException {
        String genTestJson = """
                [
                  {"tcId":"TC-PAY-001","status":"generated","className":"com.example.PaymentServiceTest",
                   "methodName":"duplicateApprove_isIdempotent","testFilePath":"src/test/java/com/example/PaymentServiceTest.java"},
                  {"tcId":"TC-API-001","status":"already_covered","className":"com.example.OrderControllerTest",
                   "methodName":"createOrder_missingField_returns400","testFilePath":"src/test/java/com/example/OrderControllerTest.java"},
                  {"tcId":"TC-SVC-002","status":"skipped","reason":"대상 클래스가 레포에 없음"}
                ]
                """;
        Path fakeClaude = writeFakeClaude(dir, Map.of(
                "is_error", false,
                "result", genTestJson
        ));

        ClaudeCodeCliClient client = new ClaudeCodeCliClient(fakeClaude.toString());
        TestGenResult result = client.generateTests(new TestGenRequest(dir, "/gen-test", Duration.ofSeconds(30)));

        assertThat(result.generatedTests()).hasSize(3);
        assertThat(result.generatedTests().get(0).status()).isEqualTo(GenTestStatus.GENERATED);
        assertThat(result.generatedTests().get(0).className()).isEqualTo("com.example.PaymentServiceTest");
        assertThat(result.generatedTests().get(1).status()).isEqualTo(GenTestStatus.ALREADY_COVERED);
        assertThat(result.generatedTests().get(2).status()).isEqualTo(GenTestStatus.SKIPPED);
        assertThat(result.generatedTests().get(2).reason()).isEqualTo("대상 클래스가 레포에 없음");
    }

    @Test
    void defaultsUnknownGenTestStatusToSkipped(@TempDir Path dir) throws IOException {
        String genTestJson = "[{\"tcId\":\"TC-X\",\"status\":\"bogus\"}]";
        Path fakeClaude = writeFakeClaude(dir, Map.of(
                "is_error", false,
                "result", genTestJson
        ));

        ClaudeCodeCliClient client = new ClaudeCodeCliClient(fakeClaude.toString());
        TestGenResult result = client.generateTests(new TestGenRequest(dir, "/gen-test", Duration.ofSeconds(30)));

        assertThat(result.generatedTests().get(0).status()).isEqualTo(GenTestStatus.SKIPPED);
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
