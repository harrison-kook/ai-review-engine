package com.tororang.review.core.pipeline;

import com.tororang.review.core.config.ReviewConfig;
import com.tororang.review.core.config.ReviewConfigLoader;
import com.tororang.review.core.llm.GenTestStatus;
import com.tororang.review.core.llm.GeneratedTestCase;
import com.tororang.review.core.llm.LlmClient;
import com.tororang.review.core.llm.ReviewRequest;
import com.tororang.review.core.llm.ReviewResult;
import com.tororang.review.core.llm.TestGenRequest;
import com.tororang.review.core.llm.TestGenResult;
import com.tororang.review.core.model.Finding;
import com.tororang.review.core.model.FindingsFilter;
import com.tororang.review.core.renderer.ReviewReport;
import com.tororang.review.core.rule.RuleDefinition;
import com.tororang.review.core.rule.RuleMerger;
import com.tororang.review.core.rule.RulepackInjector;
import com.tororang.review.core.stack.CoverageReport;
import com.tororang.review.core.stack.MutationReport;
import com.tororang.review.core.stack.StackAdapter;
import com.tororang.review.core.stack.StepResult;
import com.tororang.review.core.stack.TestCaseResult;
import com.tororang.review.core.stack.TestResult;
import com.tororang.review.core.stack.Workspace;
import com.tororang.review.core.testcase.TestCaseCatalog;
import com.tororang.review.core.testcase.TestCaseDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 설계서 3장 파이프라인을 두 단계로 나눈다 (설계서 1.3의 샌드박스/시크릿 분리 원칙):
 *
 * <p>{@link #runBuildPhase}: 대상 레포의 신뢰할 수 없는 코드를 실행하는 유일한 단계
 * (StackAdapter.build/lint, 즉 gradlew). 시크릿을 전혀 필요로 하지 않으며, 룰팩/LLM/GitHub
 * 어떤 것도 건드리지 않는다 — 네트워크 차단 샌드박스 컨테이너에서 실행하기 위함이다.
 * 결과는 findingsPath에 JSON으로 저장하고 프로세스를 종료한다.
 *
 * <p>{@link #runReportPhase}: 룰팩 주입 + LLM 리뷰. 시크릿(Anthropic API 키)과 네트워크가
 * 필요하지만, 대상 레포의 코드를 실행하지 않고 파일을 읽기만 한다(agents/reviewer.md의
 * Read/Grep/Glob 전용 도구 제한과 대응).
 *
 * <p>테스트 생성/실행도 같은 원칙으로 나뉜다: {@link #runGenTestPhase}(시크릿 있음, 코드
 * 실행 없음 — tester가 테스트 파일만 Write)와 {@link #runTestRunPhase}(시크릿 없음, 대상
 * 레포 코드를 실제로 컴파일·실행). 후자가 genTestPath의 매핑과 실제 JUnit 결과를 TC-ID
 * 단위로 대조해서 {@link TestCaseReport} 목록(testCaseReportPath)을 만든다.
 *
 * <p>{@link #run}은 네 단계(build → gentest → testrun → report)를 한 프로세스에서 순차
 * 실행한다 — 로컬 개발/CLI 즉석 실행처럼 샌드박스 격리가 필요 없는 경우를 위한 편의
 * 메서드다. CI에서는 단계별로 별도 컨테이너 실행으로 나눠 호출해야 한다
 * (docker/README.md 참고).
 */
@Component
public class ReviewPipeline {

    private static final Logger log = LoggerFactory.getLogger(ReviewPipeline.class);

    private final List<StackAdapter> stackAdapters;
    private final LlmClient llmClient;
    private final ReviewConfigLoader configLoader = new ReviewConfigLoader();
    private final RuleMerger ruleMerger = new RuleMerger();
    private final RulepackInjector rulepackInjector = new RulepackInjector();
    private final TestCaseCatalog testCaseCatalog = new TestCaseCatalog();

    public ReviewPipeline(List<StackAdapter> stackAdapters, LlmClient llmClient) {
        this.stackAdapters = stackAdapters;
        this.llmClient = llmClient;
    }

    public void runBuildPhase(ReviewCommand command) {
        Workspace workspace = new Workspace(command.repoRoot());
        StackAdapter stackAdapter = detectStackAdapter(command.repoRoot());
        log.info("detected stack adapter: {}", stackAdapter.id());

        StepResult buildResult = stackAdapter.build(workspace);
        if (!buildResult.success()) {
            throw new BuildFailedException("build failed (exit=" + buildResult.exitCode() + "):\n" + buildResult.output());
        }

        List<Finding> deterministicFindings = stackAdapter.lint(workspace);
        log.info("deterministic findings: {}", deterministicFindings.size());

        FindingsIO.write(command.findingsPath(), deterministicFindings);

        // 커버리지 게이트의 baseline: tester가 테스트를 추가하기 전, 기존 테스트만으로 측정한다.
        // 기존 테스트가 실패하더라도 빌드 자체를 막지는 않는다 — 대상 레포의 기존 상태 문제이지
        // 우리가 추가한 테스트와는 무관하다.
        TestResult baselineTestResult = stackAdapter.test(workspace);
        log.info("baseline tests: {}건 (실패 {}건)", baselineTestResult.totalTests(), baselineTestResult.failedTests());
        CoverageReport baselineCoverage = stackAdapter.coverage(workspace);
        JsonIO.write(command.baselineCoveragePath(), baselineCoverage);
    }

    public ReviewReport runReportPhase(ReviewCommand command) {
        ReviewConfig config = configLoader.load(command.configPath());

        rulepackInjector.inject(command.rulepackDir(), command.repoRoot(), config.profiles());
        List<RuleDefinition> activeRules = ruleMerger.merge(command.rulepackDir(), command.repoRoot(), config);
        log.info("active rules after merge: {}", activeRules.size());

        List<Finding> deterministicFindings = FindingsIO.read(command.findingsPath());

        ReviewResult llmResult = llmClient.review(new ReviewRequest(command.repoRoot()));
        List<Finding> llmFindings = FindingsFilter.filter(llmResult.candidates());
        log.info("llm findings after filter: {}", llmFindings.size());

        List<Finding> allFindings = new ArrayList<>(deterministicFindings);
        allFindings.addAll(llmFindings);

        return new ReviewReport(allFindings, config);
    }

    /**
     * tester 에이전트(agents/tester.md)를 호출해 testcases/*.md 시나리오에 대한 테스트 코드를
     * 작성하게 한다. 시크릿/네트워크가 필요하지만 대상 레포 코드를 실행하지는 않는다(Write만
     * 허용, Bash 없음). 매핑 결과만 genTestPath에 저장한다 — 실행은 {@link #runTestRunPhase}가
     * 별도 프로세스에서 한다.
     */
    public void runGenTestPhase(ReviewCommand command) {
        ReviewConfig config = configLoader.load(command.configPath());
        rulepackInjector.inject(command.rulepackDir(), command.repoRoot(), config.profiles());

        TestGenResult result = llmClient.generateTests(new TestGenRequest(command.repoRoot()));
        log.info("tester가 다룬 TC 수: {}", result.generatedTests().size());

        JsonListIO.write(command.genTestPath(), result.generatedTests());
    }

    /**
     * tester가 작성한 테스트를 포함해 전체 테스트를 실행하고(StackAdapter.test, 결정적), TC-ID별
     * 최종 판정을 만든다. build 단계와 같은 이유로 시크릿 없이 네트워크 차단 상태로 돌아야 한다.
     */
    public TestRunResult runTestRunPhase(ReviewCommand command) {
        ReviewConfig config = configLoader.load(command.configPath());
        List<TestCaseDefinition> catalog = testCaseCatalog.scanProfiles(command.rulepackDir(), config.profiles());
        List<GeneratedTestCase> mappings = JsonListIO.read(command.genTestPath(), GeneratedTestCase.class);

        Workspace workspace = new Workspace(command.repoRoot());
        StackAdapter stackAdapter = detectStackAdapter(command.repoRoot());
        TestResult testResult = stackAdapter.test(workspace);
        log.info("실행된 테스트: {}건 (실패 {}건)", testResult.totalTests(), testResult.failedTests());

        List<TestCaseReport> reports = correlate(catalog, mappings, testResult.cases());
        JsonListIO.write(command.testCaseReportPath(), reports);

        CoverageReport afterCoverage = stackAdapter.coverage(workspace);
        CoverageReport baselineCoverage = JsonIO.read(command.baselineCoveragePath(), CoverageReport.class, CoverageReport.EMPTY);
        CoverageDelta coverageDelta = CoverageDelta.of(baselineCoverage, afterCoverage);
        JsonIO.write(command.coverageDeltaPath(), coverageDelta);
        log.info("커버리지: {}% -> {}% (Δ{}pp)",
                baselineCoverage.lineCoveragePercent(), afterCoverage.lineCoveragePercent(), coverageDelta.deltaPercentagePoints());

        // PIT은 느리다 — .review.yml이 뮤테이션 게이트를 명시적으로 켰을 때만 돌린다.
        MutationReport mutationReport = config.gate().hasMutationThreshold()
                ? stackAdapter.mutate(workspace)
                : MutationReport.EMPTY;
        JsonIO.write(command.mutationReportPath(), mutationReport);
        if (config.gate().hasMutationThreshold()) {
            log.info("뮤테이션 스코어: {}% ({}/{} killed)",
                    mutationReport.mutationScorePercent(), mutationReport.killedMutations(), mutationReport.totalMutations());
        }

        return new TestRunResult(reports, coverageDelta, mutationReport);
    }

    private List<TestCaseReport> correlate(
            List<TestCaseDefinition> catalog,
            List<GeneratedTestCase> mappings,
            List<TestCaseResult> executed
    ) {
        Map<String, GeneratedTestCase> mappingByTcId = mappings.stream()
                .collect(Collectors.toMap(GeneratedTestCase::tcId, m -> m, (a, b) -> a));

        List<TestCaseReport> reports = new ArrayList<>();
        for (TestCaseDefinition def : catalog) {
            GeneratedTestCase mapping = mappingByTcId.get(def.tcId());
            if (mapping == null || mapping.status() == GenTestStatus.SKIPPED) {
                String reason = mapping != null ? mapping.reason() : "tester가 이 TC를 다루지 않음";
                reports.add(new TestCaseReport(def.tcId(), def.title(), TestCaseReport.Status.NOT_GENERATED,
                        null, null, reason));
                continue;
            }

            Optional<TestCaseResult> matched = executed.stream()
                    .filter(r -> r.className().equals(mapping.className()) && r.methodName().equals(mapping.methodName()))
                    .findFirst();

            if (matched.isPresent()) {
                TestCaseResult result = matched.get();
                reports.add(new TestCaseReport(def.tcId(), def.title(),
                        result.passed() ? TestCaseReport.Status.PASSED : TestCaseReport.Status.FAILED,
                        mapping.className(), mapping.methodName(), result.failureMessage()));
            } else {
                reports.add(new TestCaseReport(def.tcId(), def.title(), TestCaseReport.Status.FAILED,
                        mapping.className(), mapping.methodName(),
                        "테스트 실행 결과에서 찾을 수 없음 (컴파일 실패 등)"));
            }
        }
        return reports;
    }

    /**
     * 편의 메서드: 로컬 CLI 실행처럼 샌드박스 분리가 필요 없을 때 네 단계를 한 프로세스에서
     * 순차 실행한다. CI에서는 build/gentest/testrun/report를 별도 컨테이너 실행으로 나눠야 한다
     * (docker/README.md 참고).
     */
    public PipelineResult run(ReviewCommand command) {
        runBuildPhase(command);
        runGenTestPhase(command);
        TestRunResult testRunResult = runTestRunPhase(command);
        ReviewReport reviewReport = runReportPhase(command);
        return new PipelineResult(reviewReport, testRunResult.testCaseReports(),
                testRunResult.coverageDelta(), testRunResult.mutationReport());
    }

    private StackAdapter detectStackAdapter(Path repoRoot) {
        return stackAdapters.stream()
                .filter(adapter -> adapter.detect(repoRoot))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no stack adapter detected for " + repoRoot));
    }
}
