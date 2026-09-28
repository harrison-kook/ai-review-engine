package com.tororang.review.renderer.prcomment;

import com.tororang.review.core.adapter.PrCommentPublisher;
import com.tororang.review.core.config.Gate;
import com.tororang.review.core.config.Limits;
import com.tororang.review.core.config.Overrides;
import com.tororang.review.core.config.ReviewConfig;
import com.tororang.review.core.config.ReviewMode;
import com.tororang.review.core.model.Finding;
import com.tororang.review.core.model.Severity;
import com.tororang.review.core.model.Source;
import com.tororang.review.core.renderer.RenderContext;
import com.tororang.review.core.renderer.ReviewReport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PrCommentRendererTest {

    private Finding finding(String ruleId, Severity severity, String fingerprint) {
        return new Finding(ruleId, severity, Source.LLM, "A.java", 1, "msg", "evidence", null, fingerprint);
    }

    private ReviewConfig config(Gate gate) {
        return new ReviewConfig("org/repo@v0.1.0", List.of("common"), ReviewMode.DIFF,
                List.of(), List.of(), Overrides.EMPTY, Limits.EMPTY, gate);
    }

    private static class FakePublisher implements PrCommentPublisher {
        final Set<String> existing;
        final List<Finding> posted = new ArrayList<>();
        String summaryBody;
        int summaryCallCount = 0;

        FakePublisher(Set<String> existing) {
            this.existing = existing;
        }

        @Override
        public Set<String> existingFindingFingerprints() {
            return existing;
        }

        @Override
        public void postInlineComment(Finding finding) {
            posted.add(finding);
        }

        @Override
        public void upsertSummaryComment(String summaryBody) {
            this.summaryBody = summaryBody;
            this.summaryCallCount++;
        }
    }

    @Test
    void skipsAlreadyPostedFindingsByFingerprint() {
        Finding alreadyPosted = finding("SEC-001", Severity.HIGH, "fp-1");
        Finding fresh = finding("JPA-003", Severity.MEDIUM, "fp-2");
        FakePublisher publisher = new FakePublisher(new HashSet<>(Set.of("fp-1")));

        ReviewReport report = new ReviewReport(List.of(alreadyPosted, fresh), config(Gate.DISABLED));
        new PrCommentRenderer(publisher).render(report, RenderContext.EMPTY);

        assertThat(publisher.posted).extracting(Finding::fingerprint).containsExactly("fp-2");
    }

    @Test
    void alwaysUpsertsExactlyOneSummaryComment() {
        FakePublisher publisher = new FakePublisher(Set.of());
        ReviewReport report = new ReviewReport(List.of(finding("SEC-001", Severity.HIGH, "fp-1")), config(Gate.DISABLED));

        new PrCommentRenderer(publisher).render(report, RenderContext.EMPTY);

        assertThat(publisher.summaryCallCount).isEqualTo(1);
        assertThat(publisher.summaryBody).contains("전체 지적: 1건").contains("HIGH 1");
    }

    @Test
    void summaryReportsGateFailure() {
        FakePublisher publisher = new FakePublisher(Set.of());
        ReviewReport report = new ReviewReport(
                List.of(finding("SEC-001", Severity.HIGH, "fp-1")),
                config(new Gate(Severity.HIGH)));

        new PrCommentRenderer(publisher).render(report, RenderContext.EMPTY);

        assertThat(publisher.summaryBody).contains("FAIL");
    }

    @Test
    void summaryReportsGatePassWhenNoSevereFindings() {
        FakePublisher publisher = new FakePublisher(Set.of());
        ReviewReport report = new ReviewReport(
                List.of(finding("STYLE-010", Severity.LOW, "fp-1")),
                config(new Gate(Severity.HIGH)));

        new PrCommentRenderer(publisher).render(report, RenderContext.EMPTY);

        assertThat(publisher.summaryBody).contains("PASS");
    }
}
