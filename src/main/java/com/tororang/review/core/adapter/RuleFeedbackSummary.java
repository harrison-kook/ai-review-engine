package com.tororang.review.core.adapter;

/**
 * 규칙 ID 하나에 대한 피드백 집계. downvoteRate = thumbsDown / totalComments(%) —
 * 이 규칙이 지적한 코멘트 중 얼마나 자주 👎를 받았는지. 반응이 아예 없는 코멘트는
 * "동의"로도 "오탐"으로도 치지 않는다(침묵을 동의로 해석하지 않는다).
 */
public record RuleFeedbackSummary(String ruleId, int totalComments, int thumbsUp, int thumbsDown, double downvoteRate) {

    public static RuleFeedbackSummary of(String ruleId, int totalComments, int thumbsUp, int thumbsDown) {
        double rate = totalComments == 0 ? 0.0 : (100.0 * thumbsDown) / totalComments;
        return new RuleFeedbackSummary(ruleId, totalComments, thumbsUp, thumbsDown, rate);
    }
}