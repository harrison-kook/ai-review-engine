package com.tororang.review.core.pipeline;

import org.springframework.boot.ApplicationArguments;

import java.nio.file.Path;
import java.util.List;

/**
 * CLI 실행 인자를 정규화한 커맨드.
 * --repo=<path>       대상 레포 루트 (기본값: 현재 디렉토리)
 * --rulepack=<path>   룰팩 디렉토리 (기본값: ./rulepack)
 * --config=<path>     .review.yml 경로 (기본값: <repo>/.review.yml)
 * --findings=<path>   BUILD 단계 산출물(중간 Findings JSON) 경로 (기본값: <repo>/build/review-findings.json)
 * --phase=build|report|all  샌드박스 분리 실행 단계 (기본값: all, 설계서 1.3 참고)
 *
 * phase가 나뉘는 이유: build/lint는 대상 레포의 신뢰할 수 없는 코드를 실행하므로 시크릿 없이
 * 네트워크 차단 컨테이너에서, LLM 리뷰/PR 코멘트는 시크릿과 네트워크가 필요하지만 대상 레포
 * 코드를 실행하지는 않는 별도 프로세스에서 돌린다.
 */
public record ReviewCommand(Path repoRoot, Path rulepackDir, Path configPath, Path findingsPath, Phase phase) {

    public enum Phase {
        BUILD, REPORT, ALL
    }

    public static ReviewCommand from(ApplicationArguments args) {
        Path repoRoot = pathOption(args, "repo", Path.of("."));
        Path rulepackDir = pathOption(args, "rulepack", Path.of("./rulepack"));
        Path configPath = pathOption(args, "config", repoRoot.resolve(".review.yml"));
        Path findingsPath = pathOption(args, "findings", repoRoot.resolve("build/review-findings.json"));
        Phase phase = phaseOption(args);
        return new ReviewCommand(repoRoot, rulepackDir, configPath, findingsPath, phase);
    }

    private static Phase phaseOption(ApplicationArguments args) {
        String raw = firstValue(args, "phase");
        if (raw == null || raw.isBlank()) {
            return Phase.ALL;
        }
        return switch (raw.trim().toLowerCase()) {
            case "build" -> Phase.BUILD;
            case "report" -> Phase.REPORT;
            case "all" -> Phase.ALL;
            default -> throw new IllegalArgumentException("unknown phase: " + raw);
        };
    }

    private static Path pathOption(ApplicationArguments args, String name, Path defaultValue) {
        String value = firstValue(args, name);
        return value == null ? defaultValue : Path.of(value);
    }

    private static String firstValue(ApplicationArguments args, String name) {
        if (!args.containsOption(name)) {
            return null;
        }
        List<String> values = args.getOptionValues(name);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }
}
