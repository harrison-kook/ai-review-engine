package com.tororang.review.stack.node;

import com.tororang.review.core.model.Finding;
import com.tororang.review.core.model.FindingCandidate;
import com.tororang.review.core.model.FindingsFilter;
import com.tororang.review.core.stack.CoverageReport;
import com.tororang.review.core.stack.MutationReport;
import com.tororang.review.core.stack.StackAdapter;
import com.tororang.review.core.stack.StepResult;
import com.tororang.review.core.stack.TestResult;
import com.tororang.review.core.stack.Workspace;
import com.tororang.review.core.util.ProcessOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 설계서 4.2/7.1의 두 번째 스택 어댑터. package.json 기반 Node.js 프로젝트(백엔드 Node.js,
 * React/Next.js/Vue.js 프론트엔드 포함)를 대상으로 한다. react/vue/next 여부를 어댑터가
 * 구분하지 않는다 — 대상 레포에 이미 설정된 ESLint를 그대로 실행해서 그 결과만 파싱한다
 * (GradleSpringStackAdapter.lint()가 설정된 정적분석 플러그인만 결과를 내는 것과 같은 원칙).
 *
 * <p>현재 범위는 소스 리뷰(lint)로 한정한다 — test()/coverage()/mutate()는 Jest/Vitest/
 * Istanbul/Stryker 연동이 추가되기 전까지 스텁(NONE/EMPTY)을 반환한다.
 */
@Component
public class NodeStackAdapter implements StackAdapter {

    private static final Logger log = LoggerFactory.getLogger(NodeStackAdapter.class);
    private static final Path ESLINT_REPORT_RELATIVE_PATH = Path.of(".ai-review-engine", "eslint-report.json");

    private final NpmRunner runner;
    private final EsLintReportParser eslintParser = new EsLintReportParser();

    public NodeStackAdapter() {
        this(new NpmRunner());
    }

    public NodeStackAdapter(NpmRunner runner) {
        this.runner = runner;
    }

    @Override
    public String id() {
        return "node";
    }

    @Override
    public boolean detect(Path repoRoot) {
        return Files.isRegularFile(repoRoot.resolve("package.json"));
    }

    @Override
    public StepResult build(Workspace ws) {
        Path repoRoot = ws.repoRoot();
        boolean hasLockfile = Files.isRegularFile(repoRoot.resolve("package-lock.json"));
        ProcessOutcome result = hasLockfile ? runner.npm(repoRoot, "ci") : runner.npm(repoRoot, "install");
        return new StepResult(result.success(), result.exitCode(), result.output());
    }

    @Override
    public List<Finding> lint(Workspace ws) {
        Path repoRoot = ws.repoRoot();
        Path reportPath = repoRoot.resolve(ESLINT_REPORT_RELATIVE_PATH);
        createParentDirs(reportPath);

        // eslint는 린트 위반이 있으면 비정상 종료 코드를 내므로(설정이 없어도 마찬가지),
        // gradlew check와 같은 이유로 종료 코드는 무시하고 리포트 파일이 생겼는지만 본다.
        ProcessOutcome result = runner.npx(repoRoot, "--no-install", "eslint", ".",
                "--format", "json", "--output-file", reportPath.toString());
        if (result.timedOut()) {
            log.warn("lint (eslint) timed out for {}", repoRoot);
        }

        List<FindingCandidate> candidates = new ArrayList<>();
        if (Files.isRegularFile(reportPath)) {
            candidates.addAll(eslintParser.parse(reportPath, repoRoot));
        } else {
            log.debug("eslint report not found, skipping: {}", reportPath);
        }

        return FindingsFilter.filter(candidates);
    }

    @Override
    public TestResult test(Workspace ws) {
        return TestResult.NONE;
    }

    @Override
    public CoverageReport coverage(Workspace ws) {
        return CoverageReport.EMPTY;
    }

    @Override
    public MutationReport mutate(Workspace ws) {
        return MutationReport.EMPTY;
    }

    private void createParentDirs(Path reportPath) {
        try {
            Files.createDirectories(reportPath.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException("failed to create eslint report directory: " + reportPath.getParent(), e);
        }
    }
}
