package com.tororang.review.renderer.history;

import com.tororang.review.core.pipeline.ReviewRunSummary;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code --history} 파일에 쌓인 {@link ReviewRunSummary} 목록을 사람이 보기 좋은 Markdown
 * 통계 리포트로 만든다 ({@code --phase=stats}). MarkdownReportWriter와 같은 스타일.
 */
public final class ReviewStatsReporter {

    public void write(List<ReviewRunSummary> history, Path outputPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 리뷰 실행 통계\n\n");
        sb.append("생성 시각: ").append(Instant.now()).append("\n");
        sb.append("누적 실행 수: ").append(history.size()).append("건\n\n");

        if (history.isEmpty()) {
            sb.append("아직 기록된 실행이 없습니다. REPORT/ALL 단계를 한 번 이상 실행하면 `--history` 파일에 쌓입니다.\n");
            writeFile(sb.toString(), outputPath);
            return;
        }

        appendRuleFrequencySection(sb, history);
        appendSeverityTrendSection(sb, history);
        appendLlmUsageSection(sb, history);
        appendQualityTrendSection(sb, history);

        writeFile(sb.toString(), outputPath);
    }

    private void appendRuleFrequencySection(StringBuilder sb, List<ReviewRunSummary> history) {
        sb.append("## 규칙(ruleId)별 누적 위반 빈도\n\n");

        Map<String, Integer> totals = new LinkedHashMap<>();
        for (ReviewRunSummary summary : history) {
            summary.findingCountsByRuleId().forEach((ruleId, count) -> totals.merge(ruleId, count, Integer::sum));
        }

        if (totals.isEmpty()) {
            sb.append("지적 사항 없음.\n\n");
            return;
        }

        sb.append("| 규칙 ID | 누적 건수 |\n|---|---|\n");
        totals.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> sb.append("| ").append(e.getKey()).append(" | ").append(e.getValue()).append(" |\n"));
        sb.append("\n");
    }

    private void appendSeverityTrendSection(StringBuilder sb, List<ReviewRunSummary> history) {
        sb.append("## 실행별 심각도 건수 추이\n\n");
        sb.append("| 실행 시각 | 백엔드 | HIGH | MEDIUM | LOW | INFO | 합계 | Gate |\n|---|---|---|---|---|---|---|---|\n");
        for (ReviewRunSummary summary : history) {
            Map<String, Integer> bySeverity = summary.findingCountsBySeverity();
            sb.append("| ").append(summary.timestamp())
                    .append(" | ").append(summary.llmBackend())
                    .append(" | ").append(bySeverity.getOrDefault("HIGH", 0))
                    .append(" | ").append(bySeverity.getOrDefault("MEDIUM", 0))
                    .append(" | ").append(bySeverity.getOrDefault("LOW", 0))
                    .append(" | ").append(bySeverity.getOrDefault("INFO", 0))
                    .append(" | ").append(summary.totalFindings())
                    .append(" | ").append(summary.gateFailed() ? "FAIL" : "PASS")
                    .append(" |\n");
        }
        sb.append("\n");
    }

    private void appendLlmUsageSection(StringBuilder sb, List<ReviewRunSummary> history) {
        sb.append("## LLM 응답시간/비용 추이\n\n");
        sb.append("| 실행 시각 | 백엔드 | 응답시간(ms) | 비용(USD) |\n|---|---|---|---|\n");
        for (ReviewRunSummary summary : history) {
            sb.append("| ").append(summary.timestamp())
                    .append(" | ").append(summary.llmBackend())
                    .append(" | ").append(summary.llmDurationMs() == null ? "-" : summary.llmDurationMs())
                    .append(" | ").append(summary.llmCostUsd() == null ? "-" : summary.llmCostUsd())
                    .append(" |\n");
        }
        sb.append("\n");

        double totalCost = history.stream().map(ReviewRunSummary::llmCostUsd).filter(c -> c != null).mapToDouble(Double::doubleValue).sum();
        if (totalCost > 0) {
            sb.append("- 누적 비용: ").append(String.format("%.4f", totalCost)).append(" USD\n\n");
        }
    }

    private void appendQualityTrendSection(StringBuilder sb, List<ReviewRunSummary> history) {
        sb.append("## 커버리지/뮤테이션 스코어 추이\n\n");
        sb.append("| 실행 시각 | 커버리지 Δ(pp) | 뮤테이션 스코어(%) |\n|---|---|---|\n");
        for (ReviewRunSummary summary : history) {
            sb.append("| ").append(summary.timestamp())
                    .append(" | ").append(summary.coverageDeltaPercentagePoints() == null ? "-" : summary.coverageDeltaPercentagePoints())
                    .append(" | ").append(summary.mutationScorePercent() == null ? "-" : summary.mutationScorePercent())
                    .append(" |\n");
        }
        sb.append("\n");
    }

    private void writeFile(String content, Path outputPath) {
        try {
            if (outputPath.getParent() != null) {
                Files.createDirectories(outputPath.getParent());
            }
            Files.writeString(outputPath, content);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write stats report: " + outputPath, e);
        }
    }
}
