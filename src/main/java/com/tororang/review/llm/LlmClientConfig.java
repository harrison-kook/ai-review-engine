package com.tororang.review.llm;

import com.tororang.review.core.llm.LlmClient;
import com.tororang.review.llm.ollama.OllamaLlmClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * REVIEW_LLM_BACKEND 환경변수로 LlmClient 구현체를 고른다 (기본값: claude).
 * ClaudeCodeCliClient/OllamaLlmClient를 둘 다 @Component로 스캔하면 Spring이 LlmClient 빈
 * 중복으로 기동에 실패하므로, 이 설정 클래스가 둘 중 하나만 빈으로 등록하는 유일한 지점이다.
 */
@Configuration
public class LlmClientConfig {

    @Bean
    public LlmClient llmClient() {
        String backend = System.getenv("REVIEW_LLM_BACKEND");
        if ("ollama".equalsIgnoreCase(backend)) {
            return new OllamaLlmClient();
        }
        return new ClaudeCodeCliClient();
    }
}
