package com.tororang.review.core.rule;

import com.tororang.review.core.model.Severity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * review-rulepack의 rules/&lt;profile&gt;/*.md 를 스캔해서 규칙 ID와 기본 심각도를 추출한다.
 * 규칙 형식은 review-rulepack의 5.1 작성 규약을 따른다:
 * <pre>
 * ## JPA-003: 반복문 내 지연로딩 접근 금지 (N+1)
 * - 심각도: HIGH
 * </pre>
 */
public final class RuleCatalog {

    private static final Pattern RULE_HEADER = Pattern.compile("^##\\s+([A-Z]+-\\d{3}):");
    private static final Pattern SEVERITY_LINE = Pattern.compile("^-\\s*심각도:\\s*(HIGH|MEDIUM|LOW|INFO)\\s*$");

    public List<RuleDefinition> scanProfile(Path rulepackDir, String profile) {
        return scanDirectory(rulepackDir.resolve("rules").resolve(profile));
    }

    /**
     * 프로필 구분 없이 주어진 디렉터리를 그대로(재귀적으로) 스캔한다. 대상 레포 안에 두는
     * 레포 로컬 규칙(`.review-rules/rules/`)처럼 profile 개념이 없는 소스에 쓴다.
     */
    public List<RuleDefinition> scanDirectory(Path rulesDir) {
        if (!Files.isDirectory(rulesDir)) {
            return List.of();
        }
        List<RuleDefinition> result = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(rulesDir)) {
            paths.filter(p -> p.toString().endsWith(".md"))
                    .sorted()
                    .forEach(file -> result.addAll(parseFile(file)));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to scan rule directory: " + rulesDir, e);
        }
        return result;
    }

    private List<RuleDefinition> parseFile(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read rule file: " + file, e);
        }

        List<RuleDefinition> rules = new ArrayList<>();
        String currentRuleId = null;
        Severity currentSeverity = null;

        for (String line : lines) {
            Matcher headerMatcher = RULE_HEADER.matcher(line);
            if (headerMatcher.find()) {
                flush(rules, currentRuleId, currentSeverity, file);
                currentRuleId = headerMatcher.group(1);
                currentSeverity = null;
                continue;
            }
            if (currentRuleId != null && currentSeverity == null) {
                Matcher severityMatcher = SEVERITY_LINE.matcher(line.strip());
                if (severityMatcher.matches()) {
                    currentSeverity = Severity.valueOf(severityMatcher.group(1));
                }
            }
        }
        flush(rules, currentRuleId, currentSeverity, file);
        return rules;
    }

    private void flush(List<RuleDefinition> rules, String ruleId, Severity severity, Path file) {
        if (ruleId == null) {
            return;
        }
        if (severity == null) {
            throw new IllegalStateException("rule " + ruleId + " in " + file + " has no 심각도 line");
        }
        rules.add(new RuleDefinition(ruleId, severity, file.toString()));
    }
}
