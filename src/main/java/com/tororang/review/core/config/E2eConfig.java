package com.tororang.review.core.config;

import java.util.List;

/**
 * .review.yml의 e2e 섹션 (docs/E2E_PIPELINE_DESIGN.md 4장). lint/test와 달리 "무엇을 어떻게
 * 기동하는지"는 대상 레포마다 다르고 엔진이 추측할 방법이 없으므로, 대상 레포가 명시적으로
 * opt-in 설정해야 한다 — 설정이 없으면(enabled=false) 완전히 스킵한다.
 */
public record E2eConfig(
        boolean enabled,
        String workingDirectory,
        List<ServiceSpec> services,
        String testCommand,
        int timeoutSeconds
) {
    public static final E2eConfig DISABLED = new E2eConfig(false, null, List.of(), null, 0);

    public E2eConfig {
        services = services == null ? List.of() : List.copyOf(services);
    }

    /**
     * E2E가 의존하는 서비스 하나(백엔드, 프런트 등). {@code start}로 기동하고
     * {@code healthcheckUrl}이 {@code timeoutSeconds} 안에 2xx를 응답할 때까지 기다린다.
     * {@code workingDirectory}가 없으면 {@link E2eConfig#workingDirectory()}를 쓴다.
     */
    public record ServiceSpec(
            String name,
            String start,
            String workingDirectory,
            String healthcheckUrl,
            int timeoutSeconds
    ) {
    }
}
