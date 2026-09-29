package com.tororang.review;

import com.tororang.review.adapter.github.GitHubActionsContext;
import com.tororang.review.adapter.github.GitHubDiffCollector;
import com.tororang.review.adapter.github.GitHubPrCommentPublisher;
import com.tororang.review.adapter.github.GitHubRestTemplates;
import com.tororang.review.core.adapter.DiffScope;
import com.tororang.review.core.adapter.PrCommentPublisher;
import com.tororang.review.core.config.Gate;
import com.tororang.review.core.config.ReviewConfigLoader;
import com.tororang.review.core.config.ReviewMode;
import com.tororang.review.core.model.Finding;
import com.tororang.review.core.pipeline.BuildFailedException;
import com.tororang.review.core.pipeline.CoverageDelta;
import com.tororang.review.core.pipeline.FindingsIO;
import com.tororang.review.core.pipeline.JsonIO;
import com.tororang.review.core.pipeline.JsonListIO;
import com.tororang.review.core.pipeline.PipelineResult;
import com.tororang.review.core.pipeline.ReviewCommand;
import com.tororang.review.core.pipeline.ReviewPipeline;
import com.tororang.review.core.pipeline.TestCaseReport;
import com.tororang.review.core.pipeline.TestRunResult;
import com.tororang.review.core.renderer.RenderContext;
import com.tororang.review.core.renderer.ReviewReport;
import com.tororang.review.core.stack.MutationReport;
import com.tororang.review.renderer.markdown.MarkdownReportWriter;
import com.tororang.review.renderer.prcomment.PrCommentRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Optional;

/**
 * 1단계(리뷰) + 2단계(테스트 생성·커버리지/뮤테이션 게이트) 구현 범위(설계서 2장): GitHub
 * Actions 어댑터 + PR 코멘트 렌더러 + tester 에이전트, diff 모드.
 *
 * --phase=build|gentest|testrun|report|all 로 실행 단계를 나눈다 (설계서 1.3 샌드박스/시크릿 분리):
 * - build/testrun: 대상 레포 코드를 실제로 컴파일·실행. 시크릿 없이, 네트워크 차단 컨테이너에서.
 * - gentest/report: LLM 호출(리뷰/테스트 작성). 시크릿/네트워크 필요하지만 대상 레포 코드는 실행 안 함.
 * - all: 로컬 CLI 즉석 실행용으로 네 단계를 한 프로세스에서 순차 실행 (샌드박스 분리 없음).
 *
 * GITHUB_* 환경변수가 없으면(로컬 실행 등) PR 코멘트 단계를 건너뛰고 콘솔/파일로 남긴다 —
 * core.pipeline.ReviewPipeline 자체는 트리거를 모른다.
 *
 * test 프로파일에서는 비활성화한다. 그렇지 않으면 @SpringBootTest 컨텍스트 로딩 중
 * System.exit()가 호출되어 테스트 JVM이 즉시 종료된다.
 */
@Component
@Profile("!test")
public class ReviewRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReviewRunner.class);

    private final ReviewPipeline pipeline;
    private final MarkdownReportWriter markdownReportWriter = new MarkdownReportWriter();

    public ReviewRunner(ReviewPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Override
    public void run(ApplicationArguments args) {
        ReviewCommand command = ReviewCommand.from(args);

        try {
            switch (command.phase()) {
                case BUILD -> {
                    pipeline.runBuildPhase(command);
                    System.exit(0);
                }
                case GENTEST -> {
                    pipeline.runGenTestPhase(command);
                    System.exit(0);
                }
                case TESTRUN -> {
                    TestRunResult result = pipeline.runTestRunPhase(command);
                    logTestCaseReportsToConsole(result.testCaseReports());
                    logCoverageToConsole(result.coverageDelta());
                    logMutationToConsole(result.mutationReport());
                    Gate gate = loadGateOnly(command);
                    boolean failed = anyFailed(result.testCaseReports())
                            || coverageGateFailed(result.coverageDelta(), gate)
                            || mutationGateFailed(result.mutationReport(), gate);
                    System.exit(failed ? 1 : 0);
                }
                case REPORT -> {
                    ReviewReport report = pipeline.runReportPhase(command);
                    List<TestCaseReport> testCaseReports = JsonListIO.read(command.testCaseReportPath(), TestCaseReport.class);
                    CoverageDelta coverageDelta = JsonIO.read(command.coverageDeltaPath(), CoverageDelta.class, null);
                    MutationReport mutationReport = JsonIO.read(command.mutationReportPath(), MutationReport.class, MutationReport.EMPTY);
                    System.exit(finishReport(report, testCaseReports, coverageDelta, mutationReport, command));
                }
                case ALL -> {
                    PipelineResult result = pipeline.run(command);
                    System.exit(finishReport(result.reviewReport(), result.testCaseReports(),
                            result.coverageDelta(), result.mutationReport(), command));
                }
            }
        } catch (BuildFailedException e) {
            log.error(e.getMessage());
            System.exit(1);
        }
    }

    /**
     * REPORT/ALL 공통 마무리. GitHub 컨텍스트가 있으면 PR 코멘트를 남기고, 없으면(로컬 실행 등)
     * 결과가 그냥 사라지지 않도록 콘솔에 찍는다. findingsPath와 review-report.md에 최종 결과를
     * 남긴다. gate 실패(심각도/커버리지/뮤테이션 스코어 중 하나라도) 또는 테스트케이스 FAILED가
     * 하나라도 있으면 exit code 1.
     */
    private int finishReport(ReviewReport report, List<TestCaseReport> testCaseReports,
                              CoverageDelta coverageDelta, MutationReport mutationReport, ReviewCommand command) {
        Optional<GitHubActionsContext> ghContext = GitHubActionsContext.fromEnvironment();
        if (ghContext.isPresent()) {
            report = renderToGitHub(report, ghContext.get());
        } else {
            logFindingsToConsole(report);
            logTestCaseReportsToConsole(testCaseReports);
            logCoverageToConsole(coverageDelta);
            logMutationToConsole(mutationReport);
        }
        FindingsIO.write(command.findingsPath(), report.findings());

        markdownReportWriter.write(report, testCaseReports, coverageDelta, mutationReport,
                command.repoRoot().resolve("build/review-report.md"));
        log.info("리포트 파일: {}", command.repoRoot().resolve("build/review-report.md"));

        Gate gate = report.config().gate();
        boolean failed = report.gateFailed() || anyFailed(testCaseReports)
                || coverageGateFailed(coverageDelta, gate) || mutationGateFailed(mutationReport, gate);
        return failed ? 1 : 0;
    }

    /** TESTRUN 단독 실행(phase=testrun)에서는 ReviewReport가 없어 gate만 설정에서 따로 읽는다. */
    private Gate loadGateOnly(ReviewCommand command) {
        try {
            return new ReviewConfigLoader().load(command.configPath()).gate();
        } catch (Exception e) {
            log.warn("gate 설정을 읽지 못해 커버리지/뮤테이션 게이트를 건너뜁니다: {}", e.getMessage());
            return Gate.DISABLED;
        }
    }

    private boolean anyFailed(List<TestCaseReport> reports) {
        return reports.stream().anyMatch(r -> r.status() == TestCaseReport.Status.FAILED);
    }

    private boolean coverageGateFailed(CoverageDelta delta, Gate gate) {
        if (!gate.hasCoverageThreshold() || delta == null) {
            return false;
        }
        return delta.deltaPercentagePoints() < gate.minCoverageDelta();
    }

    private boolean mutationGateFailed(MutationReport mutationReport, Gate gate) {
        if (!gate.hasMutationThreshold() || mutationReport == null) {
            return false;
        }
        return mutationReport.mutationScorePercent() < gate.minMutationScore();
    }

    private void logFindingsToConsole(ReviewReport report) {
        log.info("GitHub Actions 컨텍스트가 없어 PR 코멘트를 건너뜁니다. Findings: {}건", report.findings().size());
        for (Finding finding : report.findings()) {
            log.info("[{}] {} — {}:{} — {}", finding.severity(), finding.ruleId(), finding.file(), finding.line(), finding.message());
            if (finding.suggestion() != null && !finding.suggestion().isBlank()) {
                log.info("    제안: {}", finding.suggestion());
            }
        }
    }

    private void logTestCaseReportsToConsole(List<TestCaseReport> reports) {
        for (TestCaseReport report : reports) {
            log.info("[{}] {} — {}", report.status(), report.tcId(), report.title());
            if (report.className() != null) {
                log.info("    테스트: {}#{}", report.className(), report.methodName());
            }
            if (report.message() != null && !report.message().isBlank()) {
                log.info("    {}", report.message());
            }
        }
    }

    private void logCoverageToConsole(CoverageDelta delta) {
        if (delta == null) {
            return;
        }
        log.info("커버리지: {}% -> {}% (Δ{}pp)",
                delta.baseline().lineCoveragePercent(), delta.after().lineCoveragePercent(), delta.deltaPercentagePoints());
    }

    private void logMutationToConsole(MutationReport mutationReport) {
        if (mutationReport == null || mutationReport.totalMutations() == 0) {
            return;
        }
        log.info("뮤테이션 스코어: {}% ({}/{} killed)",
                mutationReport.mutationScorePercent(), mutationReport.killedMutations(), mutationReport.totalMutations());
    }

    private ReviewReport renderToGitHub(ReviewReport report, GitHubActionsContext ctx) {
        RestTemplate restTemplate = GitHubRestTemplates.withToken(ctx.token());

        if (report.config().mode() == ReviewMode.DIFF) {
            DiffScope scope = new GitHubDiffCollector(restTemplate, ctx.pullRequest()).fetchChangedLines();
            List<Finding> scoped = report.findings().stream()
                    .filter(f -> scope.isInScope(f.file(), f.line()))
                    .toList();
            report = new ReviewReport(scoped, report.config());
        }

        PrCommentPublisher publisher = new GitHubPrCommentPublisher(restTemplate, ctx.pullRequest(), ctx.headSha());
        new PrCommentRenderer(publisher).render(report, RenderContext.EMPTY);
        return report;
    }
}