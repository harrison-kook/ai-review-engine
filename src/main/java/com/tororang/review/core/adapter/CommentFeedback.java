package com.tororang.review.core.adapter;

/**
 * 리뷰 코멘트 하나에 달린 반응 집계. 설계서 8장 "오탐 피드백 루프": 👎(thumbsDown) 반응을
 * 오탐 신호로 본다.
 */
public record CommentFeedback(String ruleId, String fingerprint, int thumbsUp, int thumbsDown) {
}