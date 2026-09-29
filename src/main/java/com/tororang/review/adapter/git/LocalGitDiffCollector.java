package com.tororang.review.adapter.git;

import com.tororang.review.core.adapter.DiffScope;
import com.tororang.review.core.adapter.DiffSource;
import com.tororang.review.core.util.ProcessExecutor;
import com.tororang.review.core.util.ProcessOutcome;
import com.tororang.review.core.util.UnifiedDiffParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GitHub PR 없이도 로컬 git 히스토리만으로 diff 범위를 계산한다 (설계서 10장 "CLI 어댑터").
 * {@code git diff --unified=0 <base>...HEAD}를 돌려서 unified diff를 얻고, 파일별로 잘라
 * {@link UnifiedDiffParser}로 새 파일 기준 변경 라인을 뽑는다. GitHub API 없이 동작하므로
 * GitHub Actions가 아닌 CI(또는 로컬 pre-commit류 사용)에서도 diff 모드를 쓸 수 있다.
 */
public class LocalGitDiffCollector implements DiffSource {

    private static final Logger log = LoggerFactory.getLogger(LocalGitDiffCollector.class);

    private final Path repoRoot;
    private final String baseRef;
    private final Duration timeout;
    private final UnifiedDiffParser diffParser = new UnifiedDiffParser();

    public LocalGitDiffCollector(Path repoRoot, String baseRef) {
        this(repoRoot, baseRef, Duration.ofMinutes(2));
    }

    public LocalGitDiffCollector(Path repoRoot, String baseRef, Duration timeout) {
        this.repoRoot = repoRoot;
        this.baseRef = baseRef;
        this.timeout = timeout;
    }

    @Override
    public DiffScope fetchChangedLines() {
        ProcessOutcome outcome = ProcessExecutor.run(repoRoot, timeout,
                List.of("git", "diff", "--unified=0", baseRef + "...HEAD"));
        if (!outcome.success()) {
            log.warn("git diff --unified=0 {}...HEAD 실패(exit={}, timedOut={}) — 빈 DiffScope로 진행합니다: {}",
                    baseRef, outcome.exitCode(), outcome.timedOut(), truncate(outcome.output()));
            return DiffScope.EMPTY;
        }
        return parse(outcome.output());
    }

    private DiffScope parse(String rawDiff) {
        Map<String, Set<Integer>> changedLinesByFile = new LinkedHashMap<>();
        for (String fileBlock : splitByFile(rawDiff)) {
            String filePath = extractNewFilePath(fileBlock);
            if (filePath == null) {
                continue; // 삭제된 파일 등 새 파일 경로가 없는 경우
            }
            int hunkStart = fileBlock.indexOf("\n@@");
            String hunkText = hunkStart >= 0 ? fileBlock.substring(hunkStart + 1) : "";
            changedLinesByFile.put(filePath, diffParser.parseAddedLines(hunkText));
        }
        return new DiffScope(changedLinesByFile);
    }

    private List<String> splitByFile(String rawDiff) {
        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : rawDiff.split("\n", -1)) {
            if (line.startsWith("diff --git ") && current.length() > 0) {
                blocks.add(current.toString());
                current = new StringBuilder();
            }
            current.append(line).append('\n');
        }
        if (current.length() > 0) {
            blocks.add(current.toString());
        }
        return blocks;
    }

    private String extractNewFilePath(String fileBlock) {
        for (String line : fileBlock.split("\n")) {
            if (line.startsWith("+++ ")) {
                String path = line.substring(4).strip();
                if ("/dev/null".equals(path)) {
                    return null;
                }
                return path.startsWith("b/") ? path.substring(2) : path;
            }
        }
        return null;
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }
}