package com.tororang.review.adapter.github;

import com.tororang.review.core.adapter.DiffScope;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestToUriTemplate;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.GET;

class GitHubDiffCollectorTest {

    @Test
    void fetchesChangedLinesAndSkipsRemovedFiles() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();

        String body = """
                [
                  {
                    "filename": "src/main/java/com/example/OrderService.java",
                    "status": "modified",
                    "patch": "@@ -10,7 +10,8 @@ public class OrderService {\\n     public class OrderService {\\n     public void approve(String orderId) {\\n-        pgClient.approve(orderId);\\n+        if (alreadyApproved(orderId)) return;\\n+        pgClient.approve(orderId);\\n     }"
                  },
                  {
                    "filename": "src/main/java/com/example/Deleted.java",
                    "status": "removed",
                    "patch": "@@ -1,3 +0,0 @@\\n-deleted line 1\\n-deleted line 2\\n-deleted line 3"
                  }
                ]
                """;

        server.expect(requestToUriTemplate(
                        "https://api.github.com/repos/{owner}/{repo}/pulls/{number}/files?per_page={size}&page={page}",
                        "our-org", "our-repo", 42, 100, 1))
                .andExpect(method(GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        GitHubDiffCollector collector = new GitHubDiffCollector(restTemplate, new PullRequestRef("our-org", "our-repo", 42));

        DiffScope scope = collector.fetchChangedLines();

        assertThat(scope.changedLinesByFile()).containsOnlyKeys("src/main/java/com/example/OrderService.java");
        assertThat(scope.isInScope("src/main/java/com/example/OrderService.java", 12)).isTrue();
        assertThat(scope.isInScope("src/main/java/com/example/OrderService.java", 13)).isTrue();
        assertThat(scope.isInScope("src/main/java/com/example/OrderService.java", 999)).isFalse();
        assertThat(scope.isInScope("src/main/java/com/example/Deleted.java", 1)).isFalse();

        server.verify();
    }
}
