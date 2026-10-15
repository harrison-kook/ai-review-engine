package com.tororang.review.core.pipeline;

import java.util.Map;

/**
 * 리뷰 1회 실행 결과를 통계용으로 요약한 레코드. REPORT/ALL 단계가 끝날 때마다 하나씩
 * 누적 저장되며({@code --history} 경로), {@code --phase=stats}가 이 레코드들을 모아
 * 규칙별 위반 빈도/심각도 추이/LLM 응답시간/커버리지·뮤테이션 추이를 집계한다.
 * coverageDeltaPercentagePoints/mutationScorePercent/llmDurationMs/llmCostUsd는 해당 단계를
 * 거치지 않았거나 백엔드가 제공하지 않으면 null일 수 있다.
 * timestamp는 java.time.Instant가 아니라 ISO-8601 String이다 — JsonListIO가 쓰는 평범한
 * ObjectMapper(jackson-datatype-jsr310 미등록)로는 Instant를 직렬화할 수 없기 때문이다.
 */
public record ReviewRunSummary(
        String timestamp,
        String repoRoot,
        String mode,
        String llmBackend,
        Map<String, Integer> findingCountsByRuleId,
        Map<String, Integer> findingCountsBySeverity,
        int totalFindings,
        boolean gateFailed,
        Double coverageDeltaPercentagePoints,
        Double mutationScorePercent,
        Long llmDurationMs,
        Double llmCostUsd
) {
}
