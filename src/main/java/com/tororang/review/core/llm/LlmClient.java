package com.tororang.review.core.llm;

/**
 * 설계서 7.2. 1~2단계는 ClaudeCodeCliClient(ProcessBuilder로 `claude -p` 호출)로 구현하고,
 * 3단계 이후 AnthropicApiClient(Java SDK 직접 호출)로 전환을 검토한다.
 */
public interface LlmClient {

    ReviewResult review(ReviewRequest request);

    TestGenResult generateTests(TestGenRequest request);
}
