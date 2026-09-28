package com.tororang.review.adapter.github;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubActionsContextTest {

    @Test
    void parsesPullRequestContextFromEventFile(@TempDir Path dir) throws IOException {
        Path eventFile = dir.resolve("event.json");
        Files.writeString(eventFile, """
                {
                  "pull_request": {
                    "number": 42,
                    "head": { "sha": "abc123" }
                  }
                }
                """);

        Map<String, String> env = Map.of(
                "GITHUB_REPOSITORY", "our-org/our-repo",
                "GITHUB_TOKEN", "ghp_test",
                "GITHUB_EVENT_PATH", eventFile.toString()
        );

        Optional<GitHubActionsContext> context = GitHubActionsContext.fromEnvironment(env::get);

        assertThat(context).isPresent();
        assertThat(context.get().pullRequest()).isEqualTo(new PullRequestRef("our-org", "our-repo", 42));
        assertThat(context.get().headSha()).isEqualTo("abc123");
        assertThat(context.get().token()).isEqualTo("ghp_test");
    }

    @Test
    void fallsBackToGhTokenWhenGithubTokenMissing(@TempDir Path dir) throws IOException {
        Path eventFile = dir.resolve("event.json");
        Files.writeString(eventFile, """
                {"pull_request": {"number": 1, "head": {"sha": "sha1"}}}
                """);

        Map<String, String> env = Map.of(
                "GITHUB_REPOSITORY", "our-org/our-repo",
                "GH_TOKEN", "gh-fallback",
                "GITHUB_EVENT_PATH", eventFile.toString()
        );

        Optional<GitHubActionsContext> context = GitHubActionsContext.fromEnvironment(env::get);

        assertThat(context).isPresent();
        assertThat(context.get().token()).isEqualTo("gh-fallback");
    }

    @Test
    void emptyWhenNotAPullRequestEvent(@TempDir Path dir) throws IOException {
        Path eventFile = dir.resolve("event.json");
        Files.writeString(eventFile, "{\"ref\": \"refs/heads/main\"}");

        Map<String, String> env = Map.of(
                "GITHUB_REPOSITORY", "our-org/our-repo",
                "GITHUB_TOKEN", "ghp_test",
                "GITHUB_EVENT_PATH", eventFile.toString()
        );

        assertThat(GitHubActionsContext.fromEnvironment(env::get)).isEmpty();
    }

    @Test
    void emptyWhenRequiredEnvVarsMissing() {
        assertThat(GitHubActionsContext.fromEnvironment(key -> null)).isEmpty();
    }

    @Test
    void prefersExplicitPrNumberAndShaOverEventFile() {
        // workflow_run으로 report 단계가 분리된 경우, GITHUB_EVENT_PATH는 pull_request 이벤트가
        // 아니므로(=pull_request 필드 없음) PR_NUMBER/PR_HEAD_SHA를 명시적으로 넘겨받아야 한다.
        Map<String, String> env = Map.of(
                "GITHUB_REPOSITORY", "our-org/our-repo",
                "GITHUB_TOKEN", "ghp_test",
                "PR_NUMBER", "42",
                "PR_HEAD_SHA", "deadbeef"
        );

        Optional<GitHubActionsContext> context = GitHubActionsContext.fromEnvironment(env::get);

        assertThat(context).isPresent();
        assertThat(context.get().pullRequest()).isEqualTo(new PullRequestRef("our-org", "our-repo", 42));
        assertThat(context.get().headSha()).isEqualTo("deadbeef");
    }

    @Test
    void fallsBackToEventFileWhenExplicitVarsAbsent(@TempDir Path dir) throws IOException {
        Path eventFile = dir.resolve("event.json");
        Files.writeString(eventFile, "{\"pull_request\": {\"number\": 7, \"head\": {\"sha\": \"sha7\"}}}");

        Map<String, String> env = Map.of(
                "GITHUB_REPOSITORY", "our-org/our-repo",
                "GITHUB_TOKEN", "ghp_test",
                "GITHUB_EVENT_PATH", eventFile.toString()
        );

        Optional<GitHubActionsContext> context = GitHubActionsContext.fromEnvironment(env::get);

        assertThat(context).isPresent();
        assertThat(context.get().pullRequest().number()).isEqualTo(7);
    }
}
