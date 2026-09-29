package com.tororang.review.adapter.github;

import com.tororang.review.core.adapter.PrCommentPublisher;
import com.tororang.review.core.model.Finding;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 설계서 8장 소음 관리를 GitHub REST API로 구현한다.
 * - 중복 방지: 기존 review comment 본문에 심어둔 fingerprint 마커를 읽어 이미 올라간 지적은 건너뛴다.
 * - 요약 코멘트 1개 유지: issue comment 중 summary 마커가 있는 것을 찾아 PATCH로 갱신하고,
 *   없으면 한 번만 생성한다.
 */
public class GitHubPrCommentPublisher implements PrCommentPublisher {

    static final String FINGERPRINT_MARKER_PREFIX = "<!-- review-bot:fingerprint:";
    static final String RULE_ID_MARKER_PREFIX = "<!-- review-bot:ruleId:";
    private static final String MARKER_SUFFIX = " -->";
    static final String SUMMARY_MARKER = "<!-- review-bot:summary -->";

    private final RestTemplate restTemplate;
    private final PullRequestRef pullRequest;
    private final String commitSha;

    public GitHubPrCommentPublisher(RestTemplate restTemplate, PullRequestRef pullRequest, String commitSha) {
        this.restTemplate = restTemplate;
        this.pullRequest = pullRequest;
        this.commitSha = commitSha;
    }

    @Override
    public Set<String> existingFindingFingerprints() {
        ReviewComment[] comments = fetchReviewComments();
        Set<String> fingerprints = new HashSet<>();
        for (ReviewComment comment : comments) {
            extractMarker(comment.body(), FINGERPRINT_MARKER_PREFIX).ifPresent(fingerprints::add);
        }
        return fingerprints;
    }

    @Override
    public void postInlineComment(Finding finding) {
        String url = "https://api.github.com/repos/%s/%s/pulls/%d/comments"
                .formatted(pullRequest.owner(), pullRequest.repo(), pullRequest.number());
        NewReviewComment payload = new NewReviewComment(buildInlineBody(finding), commitSha, finding.file(), finding.line());
        try {
            restTemplate.postForObject(url, payload, Void.class);
        } catch (RestClientException e) {
            throw new GitHubApiException("failed to post inline comment: " + url, e);
        }
    }

    @Override
    public void upsertSummaryComment(String summaryBody) {
        // 요약 코멘트를 다음 실행에서도 찾아 갱신할 수 있도록, 마커는 항상 이 어댑터가 직접 붙인다.
        String bodyWithMarker = summaryBody + "\n\n" + SUMMARY_MARKER;
        Optional<Long> existingId = findExistingSummaryCommentId();
        if (existingId.isPresent()) {
            updateIssueComment(existingId.get(), bodyWithMarker);
        } else {
            createIssueComment(bodyWithMarker);
        }
    }

    private String buildInlineBody(Finding finding) {
        String suggestion = (finding.suggestion() == null || finding.suggestion().isBlank())
                ? ""
                : "\n\n**제안**: " + finding.suggestion();
        return "**[%s] %s** (%s)\n\n%s\n\n```\n%s\n```%s\n\n%s%s%s\n%s%s%s".formatted(
                finding.severity(), finding.ruleId(), finding.source(),
                finding.message(), finding.evidence(), suggestion,
                FINGERPRINT_MARKER_PREFIX, finding.fingerprint(), MARKER_SUFFIX,
                RULE_ID_MARKER_PREFIX, finding.ruleId(), MARKER_SUFFIX
        );
    }

    private ReviewComment[] fetchReviewComments() {
        String url = "https://api.github.com/repos/%s/%s/pulls/%d/comments?per_page=100"
                .formatted(pullRequest.owner(), pullRequest.repo(), pullRequest.number());
        try {
            ReviewComment[] result = restTemplate.getForObject(url, ReviewComment[].class);
            return result != null ? result : new ReviewComment[0];
        } catch (RestClientException e) {
            throw new GitHubApiException("failed to fetch existing review comments: " + url, e);
        }
    }

    private Optional<Long> findExistingSummaryCommentId() {
        String url = "https://api.github.com/repos/%s/%s/issues/%d/comments?per_page=100"
                .formatted(pullRequest.owner(), pullRequest.repo(), pullRequest.number());
        IssueComment[] comments;
        try {
            comments = restTemplate.getForObject(url, IssueComment[].class);
        } catch (RestClientException e) {
            throw new GitHubApiException("failed to fetch issue comments: " + url, e);
        }
        if (comments == null) {
            return Optional.empty();
        }
        for (IssueComment comment : comments) {
            if (comment.body() != null && comment.body().contains(SUMMARY_MARKER)) {
                return Optional.of(comment.id());
            }
        }
        return Optional.empty();
    }

    private void createIssueComment(String body) {
        String url = "https://api.github.com/repos/%s/%s/issues/%d/comments"
                .formatted(pullRequest.owner(), pullRequest.repo(), pullRequest.number());
        try {
            restTemplate.postForObject(url, new NewIssueComment(body), Void.class);
        } catch (RestClientException e) {
            throw new GitHubApiException("failed to create summary comment: " + url, e);
        }
    }

    private void updateIssueComment(long commentId, String body) {
        String url = "https://api.github.com/repos/%s/%s/issues/comments/%d"
                .formatted(pullRequest.owner(), pullRequest.repo(), commentId);
        try {
            restTemplate.patchForObject(url, new NewIssueComment(body), Void.class);
        } catch (RestClientException e) {
            throw new GitHubApiException("failed to update summary comment: " + url, e);
        }
    }

    private Optional<String> extractMarker(String body, String markerPrefix) {
        if (body == null) {
            return Optional.empty();
        }
        int start = body.indexOf(markerPrefix);
        if (start < 0) {
            return Optional.empty();
        }
        int contentStart = start + markerPrefix.length();
        int end = body.indexOf(MARKER_SUFFIX, contentStart);
        if (end < 0) {
            return Optional.empty();
        }
        return Optional.of(body.substring(contentStart, end).strip());
    }

    private record ReviewComment(long id, String body, String path, Integer line) {
    }

    private record NewReviewComment(String body, String commit_id, String path, int line) {
    }

    private record IssueComment(long id, String body) {
    }

    private record NewIssueComment(String body) {
    }
}
