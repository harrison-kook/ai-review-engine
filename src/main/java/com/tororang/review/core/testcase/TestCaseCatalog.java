package com.tororang.review.core.testcase;

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
 * review-rulepack의 testcases/&lt;profile&gt;/*.md 를 스캔해서 TC-ID와 제목을 추출한다.
 * 형식은 5.2 작성 규약을 따른다:
 * <pre>
 * ## TC-PAY-001: 동일 주문번호 중복 승인 요청 시 멱등 처리
 * - 대상 계층: Service
 * </pre>
 */
public final class TestCaseCatalog {

    private static final Pattern TC_HEADER = Pattern.compile("^##\\s+(TC-[A-Z]+-\\d{3}):\\s*(.*)$");

    public List<TestCaseDefinition> scanProfile(Path rulepackDir, String profile) {
        Path profileDir = rulepackDir.resolve("testcases").resolve(profile);
        if (!Files.isDirectory(profileDir)) {
            return List.of();
        }
        List<TestCaseDefinition> result = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(profileDir)) {
            paths.filter(p -> p.toString().endsWith(".md"))
                    .sorted()
                    .forEach(file -> result.addAll(parseFile(file)));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to scan testcase profile: " + profileDir, e);
        }
        return result;
    }

    public List<TestCaseDefinition> scanProfiles(Path rulepackDir, List<String> profiles) {
        List<TestCaseDefinition> all = new ArrayList<>();
        for (String profile : profiles) {
            all.addAll(scanProfile(rulepackDir, profile));
        }
        return all;
    }

    private List<TestCaseDefinition> parseFile(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read testcase file: " + file, e);
        }

        List<TestCaseDefinition> definitions = new ArrayList<>();
        for (String line : lines) {
            Matcher matcher = TC_HEADER.matcher(line);
            if (matcher.find()) {
                definitions.add(new TestCaseDefinition(matcher.group(1), matcher.group(2).strip(), file.toString()));
            }
        }
        return definitions;
    }
}
