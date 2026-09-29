package com.tororang.review.core.pipeline;

import org.springframework.boot.ApplicationArguments;

import java.nio.file.Path;
import java.util.List;

/**
 * CLI 실행 인자를 정규화한 커맨드.
 * --repo=<path>              대상 레포 루트 (기본값: 현재 디렉토리)
 * --rulepack=<path>          룰팩 디렉토리 (기본값: ./rulepack)
 * --config=<path>            .review.yml 경로 (기본값: <repo>/.review.yml)
 * --findings=<path>          BUILD 단계 산출물(중간 Findings JSON) 경로 (기본값: <repo>/build/review-findings.json)
 * --gentest=<path>           GENTEST 단계 산출물(TC-ID ↔ 테스트 매핑) 경로 (기본값: <repo>/build/gen-test-mapping.json)
 * --testreport=<path>        TESTRUN 단계 산출물(TC-ID별 최종 판정) 경로 (기본값: <repo>/build/testcase-report.json)
 * --baseline-coverage=<path> BUILD 단계 산출물(생성 테스트 추가 전 커버리지) 경로 (기본값: <repo>/build/baseline-coverage.json)
 * --coverage-delta=<path>    TESTRUN 단계 산출물(커버리지 증감) 경로 (기본값: <repo>/build/coverage-delta.json)
 * --mutation-report=<path>   TESTRUN 단계 산출물(PIT 뮤테이션 스코어) 경로 (기본값: <repo>/build/mutation-report.json)
 * --github-repo=owner/repo   FEEDBACK 단계 대상 GitHub 레포 (로컬 --repo와 무관, 필수)
 * --max-prs=<n>              FEEDBACK 단계에서 훑을 최근 PR 개수 상한 (기본값: 200)
 * --diff-base=<ref>          로컬 git diff 기반 diff 모드의 비교 기준 ref (기본값: origin/main)
 * --phase=build|gentest|testrun|report|feedback|all  샌드박스 분리 실행 단계 (기본값: all, 설계서 1.3 참고)
 *
 * phase가 나뉘는 이유:
 * - build/testrun: 대상 레포의 코드를 실제로 컴파일·실행하므로 시크릿 없이 네트워크 차단
 *   컨테이너에서 돈다 (testrun은 tester가 새로 쓴 테스트까지 포함해서 실행하지만, 그 실행
 *   자체는 여전히 "대상 레포 코드 실행"이므로 같은 원칙을 적용한다).
 * - gentest/report: LLM 호출이 필요해 시크릿/네트워크가 있지만, 대상 레포 코드를 읽기만
 *   하고(gentest는 새 테스트 파일을 쓰기도 한다) 실행하지는 않는다.
 * - feedback: 로컬 레포/룰팩과 무관하게 GitHub API만으로 동작하는 배치 작업 (설계서 8장
 *   오탐 피드백 루프). repoRoot/rulepackDir 등은 쓰지 않는다.
 */
public record ReviewCommand(
        Path repoRoot,
        Path rulepackDir,
        Path configPath,
        Path findingsPath,
        Path genTestPath,
        Path testCaseReportPath,
        Path baselineCoveragePath,
        Path coverageDeltaPath,
        Path mutationReportPath,
        String githubRepo,
        int maxFeedbackPullRequests,
        String diffBase,
        Phase phase
) {

    public enum Phase {
        BUILD, GENTEST, TESTRUN, REPORT, FEEDBACK, ALL
    }

    public static ReviewCommand from(ApplicationArguments args) {
        Path repoRoot = pathOption(args, "repo", Path.of("."));
        Path rulepackDir = pathOption(args, "rulepack", Path.of("./rulepack"));
        Path configPath = pathOption(args, "config", repoRoot.resolve(".review.yml"));
        Path findingsPath = pathOption(args, "findings", repoRoot.resolve("build/review-findings.json"));
        Path genTestPath = pathOption(args, "gentest", repoRoot.resolve("build/gen-test-mapping.json"));
        Path testCaseReportPath = pathOption(args, "testreport", repoRoot.resolve("build/testcase-report.json"));
        Path baselineCoveragePath = pathOption(args, "baseline-coverage", repoRoot.resolve("build/baseline-coverage.json"));
        Path coverageDeltaPath = pathOption(args, "coverage-delta", repoRoot.resolve("build/coverage-delta.json"));
        Path mutationReportPath = pathOption(args, "mutation-report", repoRoot.resolve("build/mutation-report.json"));
        String githubRepo = firstValue(args, "github-repo");
        int maxFeedbackPullRequests = parseIntOption(args, "max-prs", 200);
        String diffBase = firstValueOrDefault(args, "diff-base", "origin/main");
        Phase phase = phaseOption(args);
        return new ReviewCommand(repoRoot, rulepackDir, configPath, findingsPath, genTestPath, testCaseReportPath,
                baselineCoveragePath, coverageDeltaPath, mutationReportPath, githubRepo, maxFeedbackPullRequests,
                diffBase, phase);
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
            case "feedback" -> Phase.FEEDBACK;
            case "all" -> Phase.ALL;
            default -> throw new IllegalArgumentException("unknown phase: " + raw);
        };
    }

    private static Path pathOption(ApplicationArguments args, String name, Path defaultValue) {
        String value = firstValue(args, name);
        return value == null ? defaultValue : Path.of(value);
    }

    private static int parseIntOption(ApplicationArguments args, String name, int defaultValue) {
        String value = firstValue(args, name);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid --" + name + ": " + value);
        }
    }

    private static String firstValueOrDefault(ApplicationArguments args, String name, String defaultValue) {
        String value = firstValue(args, name);
        return value == null ? defaultValue : value;
    }

    private static String firstValue(ApplicationArguments args, String name) {
        if (!args.containsOption(name)) {
            return null;
        }
        List<String> values = args.getOptionValues(name);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }
}