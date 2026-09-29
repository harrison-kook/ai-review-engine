package com.tororang.review.adapter.github;

import com.tororang.review.core.adapter.CommentFeedback;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GitHubFeedbackCollectorTest {

    @Test
    void collectsThumbsUpAndDownOnlyForMarkedComments() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();

        server.expect(requestTo("https://api.github.com/repos/our-org/our-repo/pulls?state=all&per_page=100&page=1"))
                .andExpect(method(GET))
                .andRespond(withSuccess("[{\"number\": 42}]", MediaType.APPLICATION_JSON));

        String commentsBody = """
                [
                  {"id": 1, "body": "**[HIGH] SEC-002** ...\\n\\n<!-- review-bot:fingerprint:fp-1 -->\\n<!-- review-bot:ruleId:SEC-002 -->"},
                  {"id": 2, "body": "그냥 사람이 단 댓글, 마커 없음"}
                ]
                """;
        server.expect(requestTo("https://api.github.com/repos/our-org/our-repo/pulls/42/comments?per_page=100"))
                .andExpect(method(GET))
                .andRespond(withSuccess(commentsBody, MediaType.APPLICATION_JSON));

        server.expect(requestTo("https://api.github.com/repos/our-org/our-repo/pulls/comments/1/reactions?per_page=100"))
                .andExpect(method(GET))
                .andRespond(withSuccess("[{\"content\":\"-1\"},{\"content\":\"-1\"},{\"content\":\"+1\"}]", MediaType.APPLICATION_JSON));

        GitHubFeedbackCollector collector = new GitHubFeedbackCollector(restTemplate, "our-org", "our-repo", 10);

        List<CommentFeedback> result = collector.collect();

        assertThat(result).hasSize(1);
        CommentFeedback feedback = result.get(0);
        assertThat(feedback.ruleId()).isEqualTo("SEC-002");
        assertThat(feedback.fingerprint()).isEqualTo("fp-1");
        assertThat(feedback.thumbsDown()).isEqualTo(2);
        assertThat(feedback.thumbsUp()).isEqualTo(1);

        server.verify();
    }

    @Test
    void stopsAtMaxPullRequests() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();

        server.expect(requestTo("https://api.github.com/repos/our-org/our-repo/pulls?state=all&per_page=100&page=1"))
                .andExpect(method(GET))
                .andRespond(withSuccess("[{\"number\": 1}, {\"number\": 2}, {\"number\": 3}]", MediaType.APPLICATION_JSON));

        server.expect(requestTo("https://api.github.com/repos/our-org/our-repo/pulls/1/comments?per_page=100"))
                .andExpect(method(GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/repos/our-org/our-repo/pulls/2/comments?per_page=100"))
                .andExpect(method(GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        GitHubFeedbackCollector collector = new GitHubFeedbackCollector(restTemplate, "our-org", "our-repo", 2);

        collector.collect();

        server.verify(); // PR #3의 comments 호출이 없어야 함(등록 안 했으므로, 있었다면 verify가 실패)
    }
}