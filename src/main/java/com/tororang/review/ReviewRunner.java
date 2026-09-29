package com.tororang.review;

import com.tororang.review.adapter.github.GitHubActionsContext;
import com.tororang.review.adapter.github.GitHubDiffCollector;
import com.tororang.review.adapter.github.GitHubPrCommentPublisher;
import com.tororang.review.adapter.github.GitHubRestTemplates;
import com.tororang.review.core.adapter.DiffScope;
import com.tororang.review.core.adapter.PrCommentPublisher;
import com.tororang.review.core.config.ReviewMode;
import com.tororang.review.core.model.Finding;
import com.tororang.review.core.pipeline.BuildFailedException;
import com.tororang.review.core.pipeline.FindingsIO;
import com.tororang.review.core.pipeline.JsonListIO;
import com.tororang.review.core.pipeline.PipelineResult;
import com.tororang.review.core.pipeline.ReviewCommand;
import com.tororang.review.core.pipeline.ReviewPipeline;
import com.tororang.review.core.pipeline.TestCaseReport;
import com.tororang.review.core.renderer.RenderContext;
import com.tororang.review.core.renderer.ReviewReport;
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
 * 1단계(리뷰) + 2단계(테스트 생성) 구현 범위(설계서 2장): GitHub Actions 어댑터 + PR 코멘트
 * 렌더러 + tester 에이전트, diff 모드.
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
                    List<TestCaseReport> reports = pipeline.runTestRunPhase(command);
                    logTestCaseReportsToConsole(reports);
                    System.exit(anyFailed(reports) ? 1 : 0);
                }
                case REPORT -> {
                    ReviewReport report = pipeline.runReportPhase(command);
                    List<TestCaseReport> testCaseReports = JsonListIO.read(command.testCaseReportPath(), TestCaseReport.class);
                    System.exit(finishReport(report, testCaseReports, command));
                }
                case ALL -> {
                    PipelineResult result = pipeline.run(command);
                    System.exit(finishReport(result.reviewReport(), result.testCaseReports(), command));
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
     * 남긴다. gate 실패 또는 테스트케이스 FAILED가 하나라도 있으면 exit code 1.
     */
    private int finishReport(ReviewReport report, List<TestCaseReport> testCaseReports, ReviewCommand command) {
        Optional<GitHubActionsContext> ghContext = GitHubActionsContext.fromEnvironment();
        if (ghContext.isPresent()) {
            report = renderToGitHub(report, ghContext.get());
        } else {
            logFindingsToConsole(report);
            logTestCaseReportsToConsole(testCaseReports);
        }
        FindingsIO.write(command.findingsPath(), report.findings());

        markdownReportWriter.write(report, testCaseReports, command.repoRoot().resolve("build/review-report.md"));
        log.info("리포트 파일: {}", command.repoRoot().resolve("build/review-report.md"));

        return (report.gateFailed() || anyFailed(testCaseReports)) ? 1 : 0;
    }

    private boolean anyFailed(List<TestCaseReport> reports) {
        return reports.stream().anyMatch(r -> r.status() == TestCaseReport.Status.FAILED);
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
