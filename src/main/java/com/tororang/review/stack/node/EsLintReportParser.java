package com.tororang.review.stack.node;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tororang.review.core.model.FindingCandidate;
import com.tororang.review.core.model.Severity;
import com.tororang.review.core.model.Source;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * ESLint {@code --format json} 출력 파싱.
 * {@code [{filePath, messages:[{ruleId, severity, message, line, ...}]}]}
 */
public final class EsLintReportParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public List<FindingCandidate> parse(Path jsonFile, Path repoRoot) {
        List<EsLintFileResult> files;
        try {
            files = MAPPER.readValue(jsonFile.toFile(),
                    MAPPER.getTypeFactory().constructCollectionType(List.class, EsLintFileResult.class));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read eslint report: " + jsonFile, e);
        }

        List<FindingCandidate> candidates = new ArrayList<>();
        for (EsLintFileResult file : files) {
            String relativePath = relativize(repoRoot, file.filePath());
            for (EsLintMessage msg : file.messages()) {
                String ruleId = isBlank(msg.ruleId()) ? "ESLINT" : msg.ruleId();
                int line = msg.line() == null || msg.line() < 1 ? 1 : msg.line();
                String message = isBlank(msg.message()) ? ruleId : msg.message();
                Severity severity = mapSeverity(msg.severity());

                candidates.add(new FindingCandidate(
                        ruleId,
                        severity,
                        Source.ESLINT,
                        relativePath,
                        line,
                        message,
                        message,
                        null
                ));
            }
        }
        return candidates;
    }

    private Severity mapSeverity(int eslintSeverity) {
        return eslintSeverity >= 2 ? Severity.HIGH : Severity.MEDIUM;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * ESLint가 내는 filePath는 절대경로다. XmlReports.relativize와 같은 방식으로
     * repoRoot 기준 상대경로로 바꾼다 (Windows에서도 문자열 접두어 비교로 처리).
     */
    private String relativize(Path repoRoot, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return "";
        }
        String normalizedRaw = rawPath.replace('\\', '/');
        String normalizedRoot = repoRoot.toString().replace('\\', '/');
        if (!normalizedRoot.isEmpty() && normalizedRaw.startsWith(normalizedRoot)) {
            String stripped = normalizedRaw.substring(normalizedRoot.length());
            return stripped.startsWith("/") ? stripped.substring(1) : stripped;
        }
        return normalizedRaw;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EsLintFileResult(String filePath, List<EsLintMessage> messages) {
        private EsLintFileResult {
            messages = messages == null ? List.of() : List.copyOf(messages);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EsLintMessage(String ruleId, int severity, String message, Integer line) {
    }
}
