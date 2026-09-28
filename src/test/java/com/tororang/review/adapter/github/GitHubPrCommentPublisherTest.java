package com.tororang.review.adapter.github;

import com.tororang.review.core.model.Finding;
import com.tororang.review.core.model.Severity;
import com.tororang.review.core.model.Source;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GitHubPrCommentPublisherTest {

    private final PullRequestRef pr = new PullRequestRef("our-org", "our-repo", 42);

    @Test
    void existingFindingFingerprintsExtractsMarkersFromReviewComments() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();

        String body = """
                [
                  {"id": 1, "body": "some comment\\n\\n<!-- review-bot:fingerprint:abc123 -->", "path": "A.java", "line": 10},
                  {"id": 2, "body": "human comment without marker", "path": "A.java", "line": 20}
                ]
                """;
        server.expect(requestTo("https://api.github.com/repos/our-org/our-repo/pulls/42/comments?per_page=100"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        GitHubPrCommentPublisher publisher = new GitHubPrCommentPublisher(restTemplate, pr, "sha123");

        Set<String> fingerprints = publisher.existingFindingFingerprints();

        assertThat(fingerprints).containsExactly("abc123");
        server.verify();
    }

    @Test
    void postInlineCommentSendsFindingDetailsWithFingerprintMarker() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();

        server.expect(requestTo("https://api.github.com/repos/our-org/our-repo/pulls/42/comments"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("JPA-003")))
                .andExpect(content().string(containsString("sha123")))
                .andExpect(content().string(containsString("review-bot:fingerprint:fp-1")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        GitHubPrCommentPublisher publisher = new GitHubPrCommentPublisher(restTemplate, pr, "sha123");
        Finding finding = new Finding("JPA-003", Severity.HIGH, Source.LLM,
                "src/main/java/OrderService.java", 87, "N+1 발생",
                "orders.forEach(o -> o.getItems().size());", "fetch join 사용", "fp-1");

        publisher.postInlineComment(finding);

        server.verify();
    }

    @Test
    void upsertSummaryCommentCreatesWhenNoneExists() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();

        server.expect(requestTo("https://api.github.com/repos/our-org/our-repo/issues/42/comments?per_page=100"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/repos/our-org/our-repo/issues/42/comments"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("review-bot:summary")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        GitHubPrCommentPublisher publisher = new GitHubPrCommentPublisher(restTemplate, pr, "sha123");
        publisher.upsertSummaryComment("## 요약");

        server.verify();
    }

    @Test
    void upsertSummaryCommentUpdatesExisting() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();

        String existing = """
                [{"id": 999, "body": "## 이전 요약\\n\\n<!-- review-bot:summary -->"}]
                """;
        server.expect(requestTo("https://api.github.com/repos/our-org/our-repo/issues/42/comments?per_page=100"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(existing, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/repos/our-org/our-repo/issues/comments/999"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(content().string(containsString("새 요약")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        GitHubPrCommentPublisher publisher = new GitHubPrCommentPublisher(restTemplate, pr, "sha123");
        publisher.upsertSummaryComment("## 새 요약");

        server.verify();
    }
}
