package com.tororang.review.renderer.prcomment;

import com.tororang.review.core.adapter.PrCommentPublisher;
import com.tororang.review.core.model.Finding;
import com.tororang.review.core.model.Severity;
import com.tororang.review.core.renderer.FindingsRenderer;
import com.tororang.review.core.renderer.RenderContext;
import com.tororang.review.core.renderer.ReviewReport;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * 설계서 8장 소음 관리를 지킨다: fingerprint로 중복 방지, 요약 코멘트는 1개만 유지/갱신,
 * 인라인 코멘트는 변경된 라인에만(이미 diff 범위로 필터된 findings만 들어온다고 가정).
 */
public class PrCommentRenderer implements FindingsRenderer {

    private final PrCommentPublisher publisher;

    public PrCommentRenderer(PrCommentPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void render(ReviewReport report, RenderContext ctx) {
        Set<String> alreadyPosted = publisher.existingFindingFingerprints();

        int newlyPosted = 0;
        for (Finding finding : report.findings()) {
            if (alreadyPosted.contains(finding.fingerprint())) {
                continue;
            }
            publisher.postInlineComment(finding);
            newlyPosted++;
        }

        publisher.upsertSummaryComment(buildSummary(report, newlyPosted));
    }

    private String buildSummary(ReviewReport report, int newlyPosted) {
        Map<Severity, Long> counts = new EnumMap<>(Severity.class);
        for (Severity severity : Severity.values()) {
            counts.put(severity, 0L);
        }
        for (Finding finding : report.findings()) {
            counts.merge(finding.severity(), 1L, Long::sum);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 코드 리뷰 요약\n\n");
        sb.append("- 전체 지적: ").append(report.findings().size()).append("건")
                .append(" (HIGH ").append(counts.get(Severity.HIGH))
                .append(" · MEDIUM ").append(counts.get(Severity.MEDIUM))
                .append(" · LOW ").append(counts.get(Severity.LOW))
                .append(" · INFO ").append(counts.get(Severity.INFO))
                .append(")\n");
        sb.append("- 이번 실행에서 새로 등록된 코멘트: ").append(newlyPosted).append("건\n");
        if (report.config().gate().isEnabled()) {
            sb.append("- Gate(`fail_on: ").append(report.config().gate().failOn()).append("`): ")
                    .append(report.gateFailed() ? "**FAIL**" : "PASS").append("\n");
        }
        return sb.toString();
    }
}
