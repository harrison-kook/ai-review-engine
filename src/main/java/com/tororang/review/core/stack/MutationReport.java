package com.tororang.review.core.stack;

/**
 * PIT 뮤테이션 테스트 결과 (설계서 9장). mutationScorePercent = killedMutations / totalMutations * 100.
 * 테스트가 실제 결함을 잡아내는지(뮤테이션을 죽이는지) 검증하는 지표.
 */
public record MutationReport(double mutationScorePercent, int totalMutations, int killedMutations) {

    public static final MutationReport EMPTY = new MutationReport(0.0, 0, 0);
}