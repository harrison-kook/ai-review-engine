package com.tororang.review.core.adapter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ruleId별로 {@link CommentFeedback}을 묶어 {@link RuleFeedbackSummary} 목록을 만든다.
 * 오탐률(downvoteRate)이 높은 순으로 정렬한다 — 룰 튜닝 우선순위를 바로 알 수 있게.
 */
public final class FeedbackAggregator {

    private FeedbackAggregator() {
    }

    public static List<RuleFeedbackSummary> aggregate(List<CommentFeedback> feedback) {
        Map<String, List<CommentFeedback>> byRuleId = new LinkedHashMap<>();
        for (CommentFeedback item : feedback) {
            byRuleId.computeIfAbsent(item.ruleId(), k -> new ArrayList<>()).add(item);
        }

        List<RuleFeedbackSummary> summaries = new ArrayList<>();
        for (Map.Entry<String, List<CommentFeedback>> entry : byRuleId.entrySet()) {
            int total = entry.getValue().size();
            int up = entry.getValue().stream().mapToInt(CommentFeedback::thumbsUp).sum();
            int down = entry.getValue().stream().mapToInt(CommentFeedback::thumbsDown).sum();
            summaries.add(RuleFeedbackSummary.of(entry.getKey(), total, up, down));
        }

        summaries.sort(Comparator.comparingDouble(RuleFeedbackSummary::downvoteRate).reversed());
        return summaries;
    }
}