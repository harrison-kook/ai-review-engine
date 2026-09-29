package com.tororang.review.core.adapter;

import java.util.List;

/**
 * 레포 전체(여러 PR에 걸쳐)에서 리뷰 코멘트에 달린 반응을 모은다. 1단계 구현체는
 * adapter.github.GitHubFeedbackCollector.
 */
public interface FeedbackCollector {

    List<CommentFeedback> collect();
}