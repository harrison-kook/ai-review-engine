package com.tororang.review.core.adapter;

import com.tororang.review.core.model.Finding;

import java.util.Set;

/**
 * PR에 인라인/요약 코멘트를 올리는 기능. 설계서 8장 "소음 관리"의 두 원칙을 구현체가 지켜야 한다:
 * - fingerprint로 이미 단 코멘트는 재작성하지 않는다 (existingFindingFingerprints로 중복 판단).
 * - 요약 코멘트는 1개만 유지하고 새로 달지 않고 갱신한다 (upsertSummaryComment).
 */
public interface PrCommentPublisher {

    Set<String> existingFindingFingerprints();

    void postInlineComment(Finding finding);

    void upsertSummaryComment(String summaryBody);
}
