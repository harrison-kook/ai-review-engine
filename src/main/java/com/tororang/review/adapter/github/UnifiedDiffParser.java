package com.tororang.review.adapter.github;

import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub PR files API의 "patch" 필드(unified diff hunk 텍스트)에서 새 파일 기준으로
 * 추가/변경된 라인 번호를 뽑아낸다. 삭제된 라인은 새 파일에 없으므로 포함하지 않는다.
 */
public final class UnifiedDiffParser {

    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");

    public Set<Integer> parseAddedLines(String patch) {
        Set<Integer> addedLines = new TreeSet<>();
        if (patch == null || patch.isBlank()) {
            return addedLines;
        }

        int newLine = 0;
        for (String rawLine : patch.split("\n", -1)) {
            if (rawLine.startsWith("@@")) {
                Matcher matcher = HUNK_HEADER.matcher(rawLine);
                if (matcher.find()) {
                    newLine = Integer.parseInt(matcher.group(1));
                }
                continue;
            }
            if (rawLine.startsWith("\\")) {
                // "\ No newline at end of file" 등 메타 라인, 카운트에 영향 없음
                continue;
            }
            if (rawLine.startsWith("+")) {
                addedLines.add(newLine);
                newLine++;
            } else if (rawLine.startsWith("-")) {
                // 삭제된 라인: 새 파일에 존재하지 않으므로 newLine을 증가시키지 않는다
            } else {
                // 컨텍스트 라인
                newLine++;
            }
        }
        return addedLines;
    }
}
