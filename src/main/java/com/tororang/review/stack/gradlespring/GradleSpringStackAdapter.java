package com.tororang.review.stack.gradlespring;

import com.tororang.review.core.model.Finding;
import com.tororang.review.core.model.FindingCandidate;
import com.tororang.review.core.model.FindingsFilter;
import com.tororang.review.core.stack.CoverageReport;
import com.tororang.review.core.stack.MutationReport;
import com.tororang.review.core.stack.StackAdapter;
import com.tororang.review.core.stack.StepResult;
import com.tororang.review.core.stack.TestCaseResult;
import com.tororang.review.core.stack.TestResult;
import com.tororang.review.core.stack.Workspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 설계서 4.2/7.1의 첫 스택 어댑터. build.gradle(.kts) 기반 Java/Spring 프로젝트를 대상으로 한다.
 * lint()는 대상 레포에 실제로 설정된 정적분석 플러그인만 결과를 낸다 — 'check' 태스크를 돌리고
 * 표준 리포트 경로에 파일이 생겼을 때만 파싱한다. 어떤 플러그인이 적용됐는지 미리 가정하지 않는다.
 */
@Component
public class GradleSpringStackAdapter implements StackAdapter {

    private static final Logger log = LoggerFactory.getLogger(GradleSpringStackAdapter.class);

    private final GradleWrapperRunner runner;
    private final CheckstyleReportParser checkstyleParser = new CheckstyleReportParser();
    private final PmdReportParser pmdParser = new PmdReportParser();
    private final SpotBugsReportParser spotBugsParser = new SpotBugsReportParser();
    private final JUnitReportParser junitParser = new JUnitReportParser();
    private final JacocoReportParser jacocoParser = new JacocoReportParser();
    private final PitestReportParser pitestParser = new PitestReportParser();

    public GradleSpringStackAdapter() {
        this(new GradleWrapperRunner());
    }

    public GradleSpringStackAdapter(GradleWrapperRunner runner) {
        this.runner = runner;
    }

    @Override
    public String id() {
        return "gradle-spring";
    }

    @Override
    public boolean detect(Path repoRoot) {
        return Files.isRegularFile(repoRoot.resolve("build.gradle"))
                || Files.isRegularFile(repoRoot.resolve("build.gradle.kts"));
    }

    @Override
    public StepResult build(Workspace ws) {
        ProcessResult result = runner.run(ws.repoRoot(), "build", "-x", "test", "-x", "check");
        return new StepResult(result.success(), result.exitCode(), result.output());
    }

    @Override
    public List<Finding> lint(Workspace ws) {
        Path repoRoot = ws.repoRoot();
        ProcessResult result = runner.run(repoRoot, "check", "-x", "test", "--continue");
        if (result.timedOut()) {
            log.warn("lint (gradle check) timed out for {}", repoRoot);
        }

        List<FindingCandidate> candidates = new ArrayList<>();
        collectIfPresent(repoRoot.resolve("build/reports/checkstyle/main.xml"),
                path -> candidates.addAll(checkstyleParser.parse(path, repoRoot)));
        collectIfPresent(repoRoot.resolve("build/reports/pmd/main.xml"),
                path -> candidates.addAll(pmdParser.parse(path, repoRoot)));
        collectIfPresent(repoRoot.resolve("build/reports/spotbugs/main.xml"),
                path -> candidates.addAll(spotBugsParser.parse(path, repoRoot)));

        return FindingsFilter.filter(candidates);
    }

    @Override
    public TestResult test(Workspace ws) {
        Path repoRoot = ws.repoRoot();
        ProcessResult result = runner.run(repoRoot, "test", "--continue");

        Path testResultsDir = repoRoot.resolve("build/test-results/test");
        if (!Files.isDirectory(testResultsDir)) {
            return new TestResult(result.success(), 0, 0, List.of(), result.output());
        }

        List<TestCaseResult> cases = new ArrayList<>();
        try (var files = Files.list(testResultsDir)) {
            for (Path xml : files.filter(p -> p.toString().endsWith(".xml")).toList()) {
                cases.addAll(junitParser.parse(xml));
            }
        } catch (Exception e) {
            log.warn("failed to parse junit xml results in {}: {}", testResultsDir, e.getMessage());
        }

        int failed = (int) cases.stream().filter(c -> !c.passed()).count();
        return new TestResult(failed == 0, cases.size(), failed, cases, result.output());
    }

    @Override
    public CoverageReport coverage(Workspace ws) {
        Path repoRoot = ws.repoRoot();
        // jacoco 플러그인이 없으면 태스크 자체가 없어 실패하는데, 그 경우 아래에서
        // 리포트 파일이 없는 것으로 자연스럽게 걸러진다(lint()의 check와 같은 패턴).
        runner.run(repoRoot, "jacocoTestReport", "--continue");

        Path jacocoXml = repoRoot.resolve("build/reports/jacoco/test/jacocoTestReport.xml");
        if (!Files.isRegularFile(jacocoXml)) {
            return CoverageReport.EMPTY;
        }
        return jacocoParser.parse(jacocoXml);
    }

    @Override
    public MutationReport mutate(Workspace ws) {
        Path repoRoot = ws.repoRoot();
        // pitest 플러그인이 없으면 태스크 자체가 없어 실패하는데, 그 경우 아래에서
        // 리포트 파일을 못 찾는 것으로 자연스럽게 걸러진다(lint()/coverage()와 같은 패턴).
        // 뮤테이션 테스트는 원래 느리다 — target 레포가 이 플러그인을 켰다는 것 자체가
        // 그 비용을 감수하겠다는 선택이므로 여기서 추가로 시간 제한을 두지 않는다.
        runner.run(repoRoot, "pitest", "--continue");

        Path mutationsXml = findLatestMutationsReport(repoRoot);
        if (mutationsXml == null) {
            return MutationReport.EMPTY;
        }
        return pitestParser.parse(mutationsXml);
    }

    private Path findLatestMutationsReport(Path repoRoot) {
        Path pitestReportsDir = repoRoot.resolve("build/reports/pitest");
        if (!Files.isDirectory(pitestReportsDir)) {
            return null;
        }
        try (Stream<Path> paths = Files.walk(pitestReportsDir)) {
            return paths.filter(p -> p.getFileName().toString().equals("mutations.xml"))
                    .max(Comparator.comparing(this::lastModifiedOrEpoch))
                    .orElse(null);
        } catch (IOException e) {
            log.debug("failed to search pitest reports under {}: {}", pitestReportsDir, e.getMessage());
            return null;
        }
    }

    private FileTime lastModifiedOrEpoch(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException e) {
            return FileTime.fromMillis(0);
        }
    }

    private void collectIfPresent(Path reportFile, java.util.function.Consumer<Path> consumer) {
        if (Files.isRegularFile(reportFile)) {
            consumer.accept(reportFile);
        } else {
            log.debug("lint report not found, skipping: {}", reportFile);
        }
    }
}
