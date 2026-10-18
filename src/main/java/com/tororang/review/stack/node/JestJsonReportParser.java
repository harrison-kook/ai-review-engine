package com.tororang.review.stack.node;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tororang.review.core.stack.TestCaseResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Jest {@code --json --outputFile=...} / Vitest {@code --reporter=json --outputFile=...} 출력
 * 파싱. Vitest의 json 리포터는 툴링 호환을 위해 Jest와 같은 스키마를 따른다(공식 문서에
 * "Jest의 --json을 기반으로 함"이라고 명시돼 있다), 그래서 파서 하나로 두 러너를 모두 지원한다.
 *
 * <p>{@code {testResults:[{name, assertionResults:[{fullName, status, failureMessages}]}]}}
 */
public final class JestJsonReportParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public List<TestCaseResult> parse(Path jsonFile, Path repoRoot) {
        JestReport report;
        try {
            report = MAPPER.readValue(jsonFile.toFile(), JestReport.class);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read jest/vitest json report: " + jsonFile, e);
        }

        List<TestCaseResult> cases = new ArrayList<>();
        for (JestFileResult file : report.testResults()) {
            String className = relativize(repoRoot, file.name());
            for (JestAssertionResult assertion : file.assertionResults()) {
                boolean passed = !"failed".equals(assertion.status());
                String failureMessage = passed ? null : String.join("\n", assertion.failureMessages());
                cases.add(new TestCaseResult(className, assertion.fullName(), passed, failureMessage));
            }
        }
        return cases;
    }

    /**
     * Jest/Vitest가 내는 testResults[].name은 절대경로다. EsLintReportParser.relativize와
     * 같은 방식으로 repoRoot 기준 상대경로로 바꾼다 (Windows에서도 문자열 접두어 비교로 처리).
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
    private record JestReport(List<JestFileResult> testResults) {
        private JestReport {
            testResults = testResults == null ? List.of() : List.copyOf(testResults);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record JestFileResult(String name, List<JestAssertionResult> assertionResults) {
        private JestFileResult {
            assertionResults = assertionResults == null ? List.of() : List.copyOf(assertionResults);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record JestAssertionResult(String fullName, String status, List<String> failureMessages) {
        private JestAssertionResult {
            failureMessages = failureMessages == null ? List.of() : List.copyOf(failureMessages);
        }
    }
}
