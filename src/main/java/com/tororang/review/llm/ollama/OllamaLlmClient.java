package com.tororang.review.llm.ollama;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tororang.review.core.config.ReviewMode;
import com.tororang.review.core.llm.LlmClient;
import com.tororang.review.core.llm.LlmClientException;
import com.tororang.review.core.llm.LlmUsage;
import com.tororang.review.core.llm.ReviewRequest;
import com.tororang.review.core.llm.ReviewResult;
import com.tororang.review.core.llm.TestGenRequest;
import com.tororang.review.core.llm.TestGenResult;
import com.tororang.review.core.model.FindingCandidate;
import com.tororang.review.core.model.Severity;
import com.tororang.review.core.model.Source;
import com.tororang.review.core.rule.RuleDefinition;
import com.tororang.review.core.util.ProcessExecutor;
import com.tororang.review.core.util.ProcessOutcome;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 로컬 Ollama(https://ollama.com)로 diff 리뷰만 지원하는 {@link LlmClient} 구현체. 범위는
 * "로컬 CLI 실행 전용"이다(GitHub Actions 쪽은 ClaudeCodeCliClient를 그대로 쓴다) — GitHub가
 * 호스팅하는 러너는 사용자 PC의 Ollama(localhost)에 네트워크로 닿을 수 없기 때문이다.
 *
 * <p>Claude Code CLI와 달리 Ollama는 Read/Grep/Glob 같은 도구 호출 루프가 없다. 그래서 이
 * 클라이언트는 에이전트 루프를 재구현하지 않고, 대신 ① 변경된 코드(git diff) ② 적용 규칙
 * 원문(rulepack의 .md 파일 내용)을 프롬프트에 직접 통째로 넣어 한 번의 요청/응답으로 끝낸다.
 * 그래서 mode: full(레포 전체 탐색)은 지원하지 않는다.
 */
public class OllamaLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaLlmClient.class);

    private static final String PROMPT_INSTRUCTIONS = """
            당신은 코드 리뷰어입니다. 아래 "적용 규칙"만 기준으로 "변경된 Diff"에 나온 코드만 검토하세요.
            위반 사항이 있으면 JSON 배열로만 출력하세요(설명, 코드펜스, 그 외 텍스트 없이 배열만).
            배열의 각 항목은 다음 필드를 모두 포함해야 합니다:
            ruleId, severity(HIGH/MEDIUM/LOW/INFO 중 하나), source("LLM"), file(diff의 파일 경로),
            line(diff 기준 변경된 줄 번호), message, evidence(실제 코드 조각), suggestion.
            ruleId나 evidence를 채울 수 없는 지적은 배열에 넣지 마세요. 위반이 없으면 빈 배열 []만 출력하세요.
            """;

    private final String host;
    private final String model;
    private final ObjectMapper jsonMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public OllamaLlmClient() {
        this(firstNonBlank(System.getenv("OLLAMA_HOST"), "http://localhost:11434"), System.getenv("OLLAMA_MODEL"));
    }

    public OllamaLlmClient(String host, String model) {
        this.host = host;
        this.model = model;
    }

    private static String firstNonBlank(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    @Override
    public ReviewResult review(ReviewRequest request) {
        if (model == null || model.isBlank()) {
            throw new LlmClientException("OLLAMA_MODEL 환경변수가 필요합니다 (예: qwen2.5-coder:7b)");
        }
        if (request.mode() != ReviewMode.DIFF) {
            throw new LlmClientException("OllamaLlmClient는 diff 모드만 지원합니다 (mode: " + request.mode() + ")");
        }

        String diffText = fetchDiffText(request);
        String rulesText = readRulesText(request.activeRules());
        String prompt = PROMPT_INSTRUCTIONS
                + "\n## 적용 규칙\n" + rulesText
                + "\n## 변경된 Diff\n" + diffText;

        OllamaGenerateResponse response = callOllama(prompt, request.timeout());
        List<FindingCandidate> candidates = parseFindingCandidates(response.response());
        return new ReviewResult(candidates, response.response(), usageOf(response));
    }

    @Override
    public TestGenResult generateTests(TestGenRequest request) {
        throw new UnsupportedOperationException(
                "OllamaLlmClient는 테스트 생성(gentest)을 지원하지 않습니다 — diff 리뷰 전용입니다 "
                        + "(tester 에이전트는 Write 도구 호출 루프가 필요합니다)");
    }

    private String fetchDiffText(ReviewRequest request) {
        String diffBase = request.diffBase() == null || request.diffBase().isBlank() ? "origin/main" : request.diffBase();
        ProcessOutcome outcome = ProcessExecutor.run(request.workingDirectory(), Duration.ofMinutes(2),
                List.of("git", "diff", "--unified=3", diffBase + "...HEAD"));
        if (!outcome.success()) {
            log.warn("git diff --unified=3 {}...HEAD 실패(exit={}, timedOut={}) — 빈 diff로 진행합니다",
                    diffBase, outcome.exitCode(), outcome.timedOut());
            return "";
        }
        return outcome.output();
    }

    /** activeRules의 sourceFile(.md 경로)을 중복 없이 읽어서 그대로 이어붙인다. */
    private String readRulesText(List<RuleDefinition> activeRules) {
        if (activeRules == null || activeRules.isEmpty()) {
            return "(적용된 규칙 없음)";
        }
        Set<String> seenFiles = new LinkedHashSet<>();
        StringBuilder sb = new StringBuilder();
        for (RuleDefinition rule : activeRules) {
            if (!seenFiles.add(rule.sourceFile())) {
                continue;
            }
            try {
                sb.append(Files.readString(Path.of(rule.sourceFile()))).append('\n');
            } catch (java.io.IOException e) {
                throw new UncheckedIOException("failed to read rule file: " + rule.sourceFile(), e);
            }
        }
        return sb.toString();
    }

    private OllamaGenerateResponse callOllama(String prompt, Duration timeout) {
        RestTemplate restTemplate = restTemplateWithTimeout(timeout);
        OllamaGenerateRequest body = new OllamaGenerateRequest(model, prompt, "json", false);
        try {
            OllamaGenerateResponse response = restTemplate.postForObject(host + "/api/generate", body, OllamaGenerateResponse.class);
            if (response == null || response.response() == null) {
                throw new LlmClientException("Ollama 응답이 비었습니다 (host=" + host + ", model=" + model + ")");
            }
            return response;
        } catch (RestClientException e) {
            throw new LlmClientException("Ollama 서버(" + host + ")에 연결할 수 없습니다 — `ollama serve`가 실행 중인지, "
                    + "OLLAMA_HOST/OLLAMA_MODEL 설정을 확인하세요", e);
        }
    }

    /**
     * 로컬 모델은 응답 생성이 수십 초~분 단위로 걸릴 수 있어, request마다 주어진 timeout을 그대로
     * 연결/응답 타임아웃으로 쓴다. Spring의 HttpComponentsClientHttpRequestFactory 타임아웃 세터
     * 대신 Apache HttpClient5의 RequestConfig로 직접 설정한다(Spring 쪽 API가 버전마다 바뀜).
     */
    private RestTemplate restTemplateWithTimeout(Duration timeout) {
        Timeout hcTimeout = Timeout.of(timeout);
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(hcTimeout)
                .setResponseTimeout(hcTimeout)
                .build();
        CloseableHttpClient httpClient = HttpClients.custom().setDefaultRequestConfig(requestConfig).build();
        return new RestTemplate(new HttpComponentsClientHttpRequestFactory(httpClient));
    }

    private LlmUsage usageOf(OllamaGenerateResponse response) {
        Long durationMs = response.totalDuration() == null ? null : response.totalDuration() / 1_000_000;
        return new LlmUsage(durationMs, response.promptEvalCount(), response.evalCount(), 0.0);
    }

    private List<FindingCandidate> parseFindingCandidates(String resultText) {
        String jsonArray = extractJsonArray(resultText);
        RawFinding[] rawFindings;
        try {
            rawFindings = jsonMapper.readValue(jsonArray, RawFinding[].class);
        } catch (Exception e) {
            throw new LlmClientException("Ollama 출력이 Findings JSON 배열이 아닙니다: " + truncate(jsonArray), e);
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

    /**
     * Ollama도 "format: json"을 줘도 코드펜스나 짧은 설명을 섞어 보낼 수 있다(ClaudeCodeCliClient와
     * 같은 이유의 방어적 파싱).
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OllamaGenerateRequest(String model, String prompt, String format, boolean stream) {
    }

    /** Ollama /api/generate 응답은 snake_case JSON이라 @JsonProperty로 명시적으로 매핑한다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OllamaGenerateResponse(
            String model,
            String response,
            Boolean done,
            @JsonProperty("total_duration") Long totalDuration,
            @JsonProperty("prompt_eval_count") Long promptEvalCount,
            @JsonProperty("eval_count") Long evalCount
    ) {
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
