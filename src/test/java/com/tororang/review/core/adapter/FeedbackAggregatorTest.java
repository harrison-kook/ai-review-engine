package com.tororang.review.core.adapter;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FeedbackAggregatorTest {

    @Test
    void aggregatesByRuleIdAndSortsByDownvoteRateDescending() {
        List<CommentFeedback> feedback = List.of(
                new CommentFeedback("SEC-002", "fp-1", 1, 0),
                new CommentFeedback("SEC-002", "fp-2", 0, 1),
                new CommentFeedback("JPA-003", "fp-3", 0, 1),
                new CommentFeedback("JPA-003", "fp-4", 0, 1),
                new CommentFeedback("JPA-003", "fp-5", 0, 0)
        );

        List<RuleFeedbackSummary> summaries = FeedbackAggregator.aggregate(feedback);

        assertThat(summaries).hasSize(2);
        // JPA-003: 2/3 downvoted = 66.7%, SEC-002: 1/2 downvoted = 50%
        assertThat(summaries.get(0).ruleId()).isEqualTo("JPA-003");
        assertThat(summaries.get(0).totalComments()).isEqualTo(3);
        assertThat(summaries.get(0).thumbsDown()).isEqualTo(2);
        assertThat(summaries.get(0).downvoteRate()).isCloseTo(66.67, org.assertj.core.data.Offset.offset(0.01));

        assertThat(summaries.get(1).ruleId()).isEqualTo("SEC-002");
        assertThat(summaries.get(1).downvoteRate()).isEqualTo(50.0);
    }

    @Test
    void returnsEmptyListForNoFeedback() {
        assertThat(FeedbackAggregator.aggregate(List.of())).isEmpty();
    }

    @Test
    void silenceIsNeitherUpvoteNorDownvote() {
        List<CommentFeedback> feedback = List.of(
                new CommentFeedback("STYLE-010", "fp-1", 0, 0),
                new CommentFeedback("STYLE-010", "fp-2", 0, 0)
        );

        List<RuleFeedbackSummary> summaries = FeedbackAggregator.aggregate(feedback);

        assertThat(summaries.get(0).downvoteRate()).isEqualTo(0.0);
        assertThat(summaries.get(0).totalComments()).isEqualTo(2);
    }
}