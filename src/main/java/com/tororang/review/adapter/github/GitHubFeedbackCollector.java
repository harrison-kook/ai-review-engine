package com.tororang.review.adapter.github;

import com.tororang.review.core.adapter.CommentFeedback;
import com.tororang.review.core.adapter.FeedbackCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 레포의 최근 PR들을 순회하며 review-bot이 남긴 인라인 코멘트(fingerprint+ruleId 마커가 있는
 * 것)의 👍/👎 반응을 모은다 (설계서 8장 "오탐 피드백 루프"). 코멘트마다 reactions API를 추가
 * 호출해야 해서 비용이 크므로, PR 개수를 상한으로 제한한다 — 전체 히스토리를 한 번에 훑는
 * 용도가 아니라 주기적(예: 2주마다) 배치 작업을 염두에 둔 것이다.
 */
public class GitHubFeedbackCollector implements FeedbackCollector {

    private static final Logger log = LoggerFactory.getLogger(GitHubFeedbackCollector.class);
    private static final int PAGE_SIZE = 100;

    private final RestTemplate restTemplate;
    private final String owner;
    private final String repo;
    private final int maxPullRequests;

    public GitHubFeedbackCollector(RestTemplate restTemplate, String owner, String repo, int maxPullRequests) {
        this.restTemplate = restTemplate;
        this.owner = owner;
        this.repo = repo;
        this.maxPullRequests = maxPullRequests;
    }

    @Override
    public List<CommentFeedback> collect() {
        List<CommentFeedback> feedback = new ArrayList<>();
        for (int prNumber : fetchRecentPullRequestNumbers()) {
            for (ReviewComment comment : fetchReviewComments(prNumber)) {
                Optional<String> fingerprint = extractMarker(comment.body(), GitHubPrCommentPublisher.FINGERPRINT_MARKER_PREFIX);
                Optional<String> ruleId = extractMarker(comment.body(), GitHubPrCommentPublisher.RULE_ID_MARKER_PREFIX);
                if (fingerprint.isEmpty() || ruleId.isEmpty()) {
                    continue; // review-bot이 남긴 코멘트가 아님(사람이 쓴 일반 코멘트 등)
                }
                Reaction[] reactions = fetchReactions(comment.id());
                int up = 0;
                int down = 0;
                for (Reaction reaction : reactions) {
                    if ("+1".equals(reaction.content())) {
                        up++;
                    } else if ("-1".equals(reaction.content())) {
                        down++;
                    }
                }
                feedback.add(new CommentFeedback(ruleId.get(), fingerprint.get(), up, down));
            }
        }
        return feedback;
    }

    private List<Integer> fetchRecentPullRequestNumbers() {
        List<Integer> numbers = new ArrayList<>();
        int page = 1;
        while (numbers.size() < maxPullRequests) {
            String url = "https://api.github.com/repos/%s/%s/pulls?state=all&per_page=%d&page=%d"
                    .formatted(owner, repo, PAGE_SIZE, page);
            PullRequestSummary[] prs;
            try {
                prs = restTemplate.getForObject(url, PullRequestSummary[].class);
            } catch (RestClientException e) {
                throw new GitHubApiException("failed to fetch pull requests: " + url, e);
            }
            if (prs == null || prs.length == 0) {
                break;
            }
            for (PullRequestSummary pr : prs) {
                numbers.add(pr.number());
                if (numbers.size() >= maxPullRequests) {
                    break;
                }
            }
            if (prs.length < PAGE_SIZE) {
                break;
            }
            page++;
        }
        return numbers;
    }

    private ReviewComment[] fetchReviewComments(int prNumber) {
        String url = "https://api.github.com/repos/%s/%s/pulls/%d/comments?per_page=%d"
                .formatted(owner, repo, prNumber, PAGE_SIZE);
        try {
            ReviewComment[] result = restTemplate.getForObject(url, ReviewComment[].class);
            return result != null ? result : new ReviewComment[0];
        } catch (RestClientException e) {
            log.warn("failed to fetch review comments for PR #{}: {}", prNumber, e.getMessage());
            return new ReviewComment[0];
        }
    }

    private Reaction[] fetchReactions(long commentId) {
        String url = "https://api.github.com/repos/%s/%s/pulls/comments/%d/reactions?per_page=%d"
                .formatted(owner, repo, commentId, PAGE_SIZE);
        try {
            Reaction[] result = restTemplate.getForObject(url, Reaction[].class);
            return result != null ? result : new Reaction[0];
        } catch (RestClientException e) {
            log.warn("failed to fetch reactions for comment {}: {}", commentId, e.getMessage());
            return new Reaction[0];
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
        int end = body.indexOf(" -->", contentStart);
        if (end < 0) {
            return Optional.empty();
        }
        return Optional.of(body.substring(contentStart, end).strip());
    }

    private record PullRequestSummary(int number) {
    }

    private record ReviewComment(long id, String body) {
    }

    private record Reaction(String content) {
    }
}