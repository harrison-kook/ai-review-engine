package com.tororang.review.adapter.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

/**
 * GitHub Actions PR 워크플로에서 표준으로 제공하는 환경변수/이벤트 파일에서
 * PR 식별 정보를 읽어낸다. 값이 하나라도 없으면(로컬 CLI 실행 등) empty를 반환하고,
 * 호출자는 PR 코멘트 렌더링을 건너뛰면 된다.
 *
 * <p>PR_NUMBER/PR_HEAD_SHA를 우선 확인하는 이유: fork PR 안전을 위해 build/report를
 * workflow_run으로 분리하면(templates/github-workflows 참고) report 단계의
 * GITHUB_EVENT_PATH는 workflow_run 이벤트(=pull_request 필드 없음)를 가리킨다.
 * 이 경우 build 단계에서 넘겨받은 PR 번호/SHA를 명시적 환경변수로 주입해야 한다.
 * 분리하지 않고 pull_request 트리거 하나로 직접 도는 경우엔 이 값들이 없어도
 * GITHUB_EVENT_PATH에서 그대로 읽는다.
 */
public record GitHubActionsContext(PullRequestRef pullRequest, String headSha, String token) {

    public static Optional<GitHubActionsContext> fromEnvironment() {
        return fromEnvironment(System::getenv);
    }

    static Optional<GitHubActionsContext> fromEnvironment(Function<String, String> env) {
        String repository = env.apply("GITHUB_REPOSITORY");
        String token = firstNonBlank(env.apply("GITHUB_TOKEN"), env.apply("GH_TOKEN"));
        if (isBlank(repository) || isBlank(token)) {
            return Optional.empty();
        }

        String[] parts = repository.split("/", 2);
        if (parts.length != 2) {
            return Optional.empty();
        }

        Optional<GitHubActionsContext> fromExplicitVars = fromExplicitPrVars(env, parts, token);
        if (fromExplicitVars.isPresent()) {
            return fromExplicitVars;
        }
        return fromEventFile(env, parts, token);
    }

    private static Optional<GitHubActionsContext> fromExplicitPrVars(Function<String, String> env, String[] repoParts, String token) {
        String number = env.apply("PR_NUMBER");
        String sha = env.apply("PR_HEAD_SHA");
        if (isBlank(number) || isBlank(sha)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new GitHubActionsContext(
                    new PullRequestRef(repoParts[0], repoParts[1], Integer.parseInt(number.trim())),
                    sha.trim(), token));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<GitHubActionsContext> fromEventFile(Function<String, String> env, String[] repoParts, String token) {
        String eventPath = env.apply("GITHUB_EVENT_PATH");
        if (isBlank(eventPath)) {
            return Optional.empty();
        }
        try {
            JsonNode event = new ObjectMapper().readTree(Path.of(eventPath).toFile());
            JsonNode pullRequestNode = event.get("pull_request");
            if (pullRequestNode == null) {
                return Optional.empty();
            }
            int number = pullRequestNode.get("number").asInt();
            String headSha = pullRequestNode.get("head").get("sha").asText();
            return Optional.of(new GitHubActionsContext(new PullRequestRef(repoParts[0], repoParts[1], number), headSha, token));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String firstNonBlank(String a, String b) {
        return isBlank(a) ? b : a;
    }
}
