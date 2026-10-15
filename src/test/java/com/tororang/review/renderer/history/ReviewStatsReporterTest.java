package com.tororang.review.renderer.history;

import com.tororang.review.core.pipeline.ReviewRunSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewStatsReporterTest {

    @Test
    void writesPlaceholderWhenHistoryIsEmpty(@TempDir Path dir) throws IOException {
        Path outputPath = dir.resolve("stats.md");

        new ReviewStatsReporter().write(List.of(), outputPath);

        assertThat(Files.readString(outputPath)).contains("아직 기록된 실행이 없습니다");
    }

    @Test
    void aggregatesRuleFrequencyAcrossRuns(@TempDir Path dir) throws IOException {
        Path outputPath = dir.resolve("stats.md");
        ReviewRunSummary run1 = new ReviewRunSummary("2026-10-15T00:00:00Z", "/repo", "DIFF", "ollama",
                Map.of("SEC-001", 2), Map.of("HIGH", 2), 2, false, 1.0, null, 900L, 0.0);
        ReviewRunSummary run2 = new ReviewRunSummary("2026-10-16T00:00:00Z", "/repo", "DIFF", "claude",
                Map.of("SEC-001", 1, "JPA-003", 3), Map.of("HIGH", 3, "MEDIUM", 1), 4, true, -0.5, 82.0, 1100L, 0.02);

        new ReviewStatsReporter().write(List.of(run1, run2), outputPath);
        String content = Files.readString(outputPath);

        assertThat(content).contains("누적 실행 수: 2건");
        assertThat(content).contains("| SEC-001 | 3 |"); // 2 + 1 합산, JPA-003(3)보다 먼저 나와야 함(내림차순 아니면 실패)
        assertThat(content).contains("| JPA-003 | 3 |");
        assertThat(content).contains("claude");
        assertThat(content).contains("ollama");
        assertThat(content).contains("누적 비용: 0.0200 USD");
        assertThat(content).contains("82.0");
    }
}
