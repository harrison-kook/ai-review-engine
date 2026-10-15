package com.tororang.review.core.llm;

/**
 * LLM 호출 1회의 비용/소요시간 메타데이터. 필드는 모두 nullable — 백엔드(Claude CLI envelope,
 * Ollama API 응답)마다 제공 여부가 다르다. 로컬 모델(Ollama)은 costUsd가 항상 0.0이다.
 */
public record LlmUsage(Long durationMs, Long promptTokens, Long completionTokens, Double costUsd) {
}
