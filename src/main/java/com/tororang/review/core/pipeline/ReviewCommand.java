package com.tororang.review.core.pipeline;

import org.springframework.boot.ApplicationArguments;

import java.nio.file.Path;
import java.util.List;

/**
 * CLI 실행 인자를 정규화한 커맨드.
 * --repo=<path>         대상 레포 루트 (기본값: 현재 디렉토리)
 * --rulepack=<path>     룰팩 디렉토리 (기본값: ./rulepack)
 * --config=<path>       .review.yml 경로 (기본값: <repo>/.review.yml)
 * --findings=<path>     BUILD 단계 산출물(중간 Findings JSON) 경로 (기본값: <repo>/build/review-findings.json)
 * --gentest=<path>      GENTEST 단계 산출물(TC-ID ↔ 테스트 매핑) 경로 (기본값: <repo>/build/gen-test-mapping.json)
 * --testreport=<path>   TESTRUN 단계 산출물(TC-ID별 최종 판정) 경로 (기본값: <repo>/build/testcase-report.json)
 * --phase=build|gentest|testrun|report|all  샌드박스 분리 실행 단계 (기본값: all, 설계서 1.3 참고)
 *
 * phase가 나뉘는 이유:
 * - build/testrun: 대상 레포의 코드를 실제로 컴파일·실행하므로 시크릿 없이 네트워크 차단
 *   컨테이너에서 돈다 (testrun은 tester가 새로 쓴 테스트까지 포함해서 실행하지만, 그 실행
 *   자체는 여전히 "대상 레포 코드 실행"이므로 같은 원칙을 적용한다).
 * - gentest/report: LLM 호출이 필요해 시크릿/네트워크가 있지만, 대상 레포 코드를 읽기만
 *   하고(gentest는 새 테스트 파일을 쓰기도 한다) 실행하지는 않는다.
 */
public record ReviewCommand(
        Path repoRoot,
        Path rulepackDir,
        Path configPath,
        Path findingsPath,
        Path genTestPath,
        Path testCaseReportPath,
        Phase phase
) {

    public enum Phase {
        BUILD, GENTEST, TESTRUN, REPORT, ALL
    }

    public static ReviewCommand from(ApplicationArguments args) {
        Path repoRoot = pathOption(args, "repo", Path.of("."));
        Path rulepackDir = pathOption(args, "rulepack", Path.of("./rulepack"));
        Path configPath = pathOption(args, "config", repoRoot.resolve(".review.yml"));
        Path findingsPath = pathOption(args, "findings", repoRoot.resolve("build/review-findings.json"));
        Path genTestPath = pathOption(args, "gentest", repoRoot.resolve("build/gen-test-mapping.json"));
        Path testCaseReportPath = pathOption(args, "testreport", repoRoot.resolve("build/testcase-report.json"));
        Phase phase = phaseOption(args);
        return new ReviewCommand(repoRoot, rulepackDir, configPath, findingsPath, genTestPath, testCaseReportPath, phase);
    }

    private static Phase phaseOption(ApplicationArguments args) {
        String raw = firstValue(args, "phase");
        if (raw == null || raw.isBlank()) {
            return Phase.ALL;
        }
        return switch (raw.trim().toLowerCase()) {
            case "build" -> Phase.BUILD;
            case "gentest" -> Phase.GENTEST;
            case "testrun" -> Phase.TESTRUN;
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
