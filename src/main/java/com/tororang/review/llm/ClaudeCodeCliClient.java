package com.tororang.review.llm;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tororang.review.core.llm.GenTestStatus;
import com.tororang.review.core.llm.GeneratedTestCase;
import com.tororang.review.core.llm.LlmClient;
import com.tororang.review.core.llm.LlmClientException;
import com.tororang.review.core.llm.ReviewRequest;
import com.tororang.review.core.llm.ReviewResult;
import com.tororang.review.core.llm.TestGenRequest;
import com.tororang.review.core.llm.TestGenResult;
import com.tororang.review.core.model.FindingCandidate;
import com.tororang.review.core.model.Severity;
import com.tororang.review.core.model.Source;
import com.tororang.review.core.util.ProcessExecutor;
import com.tororang.review.core.util.ProcessOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 설계서 7.2. `claude -p "/review" --output-format json` 을 ProcessBuilder로 호출하고
 * 결과 JSON에서 reviewer 에이전트가 낸 Findings 배열을 꺼낸다.
 * agents/commands 구조(review-rulepack)를 그대로 활용하며, 이 클라이언트는 프롬프트 내용을
 * 직접 만들지 않는다 — 어떤 슬래시 커맨드를 어디서 실행할지만 결정한다.
 */
@Component
public class ClaudeCodeCliClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeCliClient.class);

    /** agents/reviewer.md의 tools 프론트매터와 맞춘다: Read, Grep, Glob (읽기 전용). */
    private static final String REVIEWER_ALLOWED_TOOLS = "Read,Grep,Glob";
    /** agents/tester.md의 tools 프론트매터와 맞춘다: Write는 있지만 Bash는 없다 — 테스트를 실행하지 않는다. */
    private static final String TESTER_ALLOWED_TOOLS = "Read,Grep,Glob,Write";

    private final String claudeExecutable;
    // LLM 출력은 프롬프트를 아무리 엄격히 써도 예상 밖 필드가 섞일 수 있다(실사용 중 fingerprint를
    // 직접 채워 보낸 사례 확인). 엔진이 필요한 필드만 뽑아 쓰므로 모르는 필드는 무시한다.
    private final ObjectMapper jsonMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public ClaudeCodeCliClient() {
        // Windows npm 전역 설치는 claude를 POSIX 쉘 스크립트로 만든다 — ProcessBuilder(네이티브
        // CreateProcess)는 이를 직접 실행하지 못하므로 claude.cmd를 써야 한다 (GradleWrapperRunner의
        // gradlew/gradlew.bat 분기와 동일한 이유).
        this(System.getProperty("os.name", "").toLowerCase().contains("win") ? "claude.cmd" : "claude");
    }

    public ClaudeCodeCliClient(String claudeExecutable) {
        this.claudeExecutable = claudeExecutable;
    }

    @Override
    public ReviewResult review(ReviewRequest request) {
        List<String> command = List.of(
                claudeExecutable,
                "-p", request.command(),
                "--output-format", "json",
                "--allowedTools", REVIEWER_ALLOWED_TOOLS
        );

        ProcessOutcome outcome = ProcessExecutor.run(request.workingDirectory(), request.timeout(), command);
        if (outcome.timedOut()) {
            throw new LlmClientException("claude CLI timed out after " + request.timeout());
        }

        String resultText = extractResultText(outcome.output());
        List<FindingCandidate> candidates = parseFindingCandidates(resultText);
        return new ReviewResult(candidates, resultText);
    }

    @Override
    public TestGenResult generateTests(TestGenRequest request) {
        List<String> command = List.of(
                claudeExecutable,
                "-p", request.command(),
                "--output-format", "json",
                "--allowedTools", TESTER_ALLOWED_TOOLS
        );

        ProcessOutcome outcome = ProcessExecutor.run(request.workingDirectory(), request.timeout(), command);
        if (outcome.timedOut()) {
            throw new LlmClientException("claude CLI timed out after " + request.timeout());
        }

        String resultText = extractResultText(outcome.output());
        List<GeneratedTestCase> generated = parseGeneratedTestCases(resultText);
        return new TestGenResult(generated, resultText);
    }

    private String extractResultText(String rawStdout) {
        JsonNode envelope;
        try {
            envelope = jsonMapper.readTree(stripLeadingNonJson(rawStdout));
        } catch (Exception e) {
            throw new LlmClientException("claude CLI가 유효한 JSON을 반환하지 않았습니다: " + truncate(rawStdout), e);
        }

        JsonNode isError = envelope.get("is_error");
        if (isError != null && isError.asBoolean(false)) {
            // 예외 메시지는 로그가 잘릴 수 있어 짧게 남기고, 진단용 전체 원문은 ERROR 레벨로 통째로 남긴다.
            log.error("claude CLI returned is_error=true, full output:\n{}", rawStdout);
            throw new LlmClientException("claude CLI가 오류를 반환했습니다 (전체 로그는 ERROR 레벨 참고): " + truncate(rawStdout));
        }

        JsonNode result = envelope.get("result");
        if (result == null || result.isNull()) {
            log.error("claude CLI response missing result field, full output:\n{}", rawStdout);
            throw new LlmClientException("claude CLI 응답에 result 필드가 없습니다: " + truncate(rawStdout));
        }
        return result.asText();
    }

    private List<FindingCandidate> parseFindingCandidates(String resultText) {
        String jsonArray = extractJsonArray(resultText);
        RawFinding[] rawFindings;
        try {
            rawFindings = jsonMapper.readValue(jsonArray, RawFinding[].class);
        } catch (Exception e) {
            throw new LlmClientException("reviewer 출력이 Findings JSON 배열이 아닙니다: " + truncate(jsonArray), e);
        }

        List<FindingCandidate> candidates = new ArrayList<>();
        for (RawFinding raw : rawFindings) {
            candidates.add(new FindingCandidate(
                    raw.ruleId(),
                    parseSeverity(raw.severity()),
                    parseSource(raw.source()),
                    raw.file(),
                    raw.line() != null ? raw.line() : 1,
                    raw.message(),
                    raw.evidence(),
                    raw.suggestion()
            ));
        }
        return candidates;
    }

    private List<GeneratedTestCase> parseGeneratedTestCases(String resultText) {
        String jsonArray = extractJsonArray(resultText);
        RawGeneratedTestCase[] raw;
        try {
            raw = jsonMapper.readValue(jsonArray, RawGeneratedTestCase[].class);
        } catch (Exception e) {
            throw new LlmClientException("tester 출력이 예상한 JSON 배열이 아닙니다: " + truncate(jsonArray), e);
        }

        List<GeneratedTestCase> result = new ArrayList<>();
        for (RawGeneratedTestCase item : raw) {
            result.add(new GeneratedTestCase(
                    item.tcId(),
                    parseGenTestStatus(item.status()),
                    item.className(),
                    item.methodName(),
                    item.testFilePath(),
                    item.reason()
            ));
        }
        return result;
    }

    private GenTestStatus parseGenTestStatus(String value) {
        if (value == null || value.isBlank()) {
            log.warn("tester 출력에 status가 없어 SKIPPED로 처리합니다");
            return GenTestStatus.SKIPPED;
        }
        try {
            return GenTestStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("알 수 없는 status '{}', SKIPPED로 대체", value);
            return GenTestStatus.SKIPPED;
        }
    }

    /**
     * claude CLI가 --output-format json 인데도 "Warning: no stdin data received..." 같은
     * 진단 메시지를 JSON 앞에 섞어 stdout으로 출력하는 경우가 있다. 첫 '{' 앞의 모든 내용을 버린다.
     */
    private String stripLeadingNonJson(String rawStdout) {
        int firstBrace = rawStdout.indexOf('{');
        return firstBrace <= 0 ? rawStdout : rawStdout.substring(firstBrace);
    }

    /**
     * 프롬프트가 "JSON만 출력하라"고 못박아도, 실사용 중 확인된 것처럼 LLM이 코드펜스 앞에
     * 짧은 확인 문장을 붙이는 경우가 있다. 텍스트 전체가 아니라 어디에든 있는 ```(json)? 펜스를
     * 찾아서 그 안쪽만 취하고, 펜스가 아예 없으면 첫 '['부터 마지막 ']'까지를 배열로 간주한다.
     */
    private String extractJsonArray(String text) {
        String trimmed = text.strip();

        int fenceStart = trimmed.indexOf("```");
        if (fenceStart >= 0) {
            int contentStart = trimmed.indexOf('\n', fenceStart);
            int fenceEnd = contentStart >= 0 ? trimmed.indexOf("```", contentStart) : -1;
            if (contentStart >= 0 && fenceEnd > contentStart) {
                return trimmed.substring(contentStart + 1, fenceEnd).strip();
            }
        }

        if (trimmed.startsWith("[")) {
            return trimmed;
        }

        int arrayStart = trimmed.indexOf('[');
        int arrayEnd = trimmed.lastIndexOf(']');
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            return trimmed.substring(arrayStart, arrayEnd + 1);
        }

        return trimmed;
    }

    private Severity parseSeverity(String value) {
        if (value == null || value.isBlank()) {
            return Severity.MEDIUM;
        }
        try {
            return Severity.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("알 수 없는 severity '{}', MEDIUM으로 대체", value);
            return Severity.MEDIUM;
        }
    }

    private Source parseSource(String value) {
        if (value == null || value.isBlank()) {
            return Source.LLM;
        }
        try {
            return Source.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Source.LLM;
        }
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 500 ? text.substring(0, 500) + "..." : text;
    }

    private record RawFinding(
            String ruleId,
            String severity,
            String source,
            String file,
            Integer line,
            String message,
            String evidence,
            String suggestion
    ) {
    }

    private record RawGeneratedTestCase(
            String tcId,
            String status,
            String className,
            String methodName,
            String testFilePath,
            String reason
    ) {
    }
}
