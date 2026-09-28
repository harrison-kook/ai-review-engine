package com.tororang.review.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private final String claudeExecutable;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    public ClaudeCodeCliClient() {
        this("claude");
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
        throw new UnsupportedOperationException(
                "tester 에이전트는 로드맵 2단계에서 구현 예정 (review-rulepack의 agents/tester.md 참고)");
    }

    private String extractResultText(String rawStdout) {
        JsonNode envelope;
        try {
            envelope = jsonMapper.readTree(rawStdout);
        } catch (Exception e) {
            throw new LlmClientException("claude CLI가 유효한 JSON을 반환하지 않았습니다: " + truncate(rawStdout), e);
        }

        JsonNode isError = envelope.get("is_error");
        if (isError != null && isError.asBoolean(false)) {
            throw new LlmClientException("claude CLI가 오류를 반환했습니다: " + truncate(rawStdout));
        }

        JsonNode result = envelope.get("result");
        if (result == null || result.isNull()) {
            throw new LlmClientException("claude CLI 응답에 result 필드가 없습니다: " + truncate(rawStdout));
        }
        return result.asText();
    }

    private List<FindingCandidate> parseFindingCandidates(String resultText) {
        String jsonArray = stripCodeFences(resultText);
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

    private String stripCodeFences(String text) {
        String trimmed = text.strip();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).strip();
            }
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
}
