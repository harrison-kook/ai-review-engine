package com.tororang.review.renderer.history;

import com.tororang.review.core.pipeline.ReviewRunSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewHistoryStoreTest {

    private ReviewRunSummary summary(String ruleId) {
        return new ReviewRunSummary("2026-10-15T00:00:00Z", "/repo", "DIFF", "claude",
                Map.of(ruleId, 1), Map.of("HIGH", 1), 1, false, 1.5, null, 1200L, 0.01);
    }

    @Test
    void readAllReturnsEmptyWhenHistoryFileMissing(@TempDir Path dir) {
        assertThat(ReviewHistoryStore.readAll(dir.resolve("missing.json"))).isEmpty();
    }

    @Test
    void appendAccumulatesAcrossMultipleCalls(@TempDir Path dir) {
        Path historyPath = dir.resolve("history.json");

        ReviewHistoryStore.append(historyPath, summary("SEC-001"));
        ReviewHistoryStore.append(historyPath, summary("JPA-003"));

        List<ReviewRunSummary> history = ReviewHistoryStore.readAll(historyPath);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).findingCountsByRuleId()).containsKey("SEC-001");
        assertThat(history.get(1).findingCountsByRuleId()).containsKey("JPA-003");
    }
}
