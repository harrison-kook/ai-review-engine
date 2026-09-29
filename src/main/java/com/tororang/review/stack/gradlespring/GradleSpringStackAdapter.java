package com.tororang.review.stack.gradlespring;

import com.tororang.review.core.model.Finding;
import com.tororang.review.core.model.FindingCandidate;
import com.tororang.review.core.model.FindingsFilter;
import com.tororang.review.core.stack.CoverageReport;
import com.tororang.review.core.stack.StackAdapter;
import com.tororang.review.core.stack.StepResult;
import com.tororang.review.core.stack.TestCaseResult;
import com.tororang.review.core.stack.TestResult;
import com.tororang.review.core.stack.Workspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
        Path jacocoXml = repoRoot.resolve("build/reports/jacoco/test/jacocoTestReport.xml");
        if (!Files.isRegularFile(jacocoXml)) {
            return CoverageReport.EMPTY;
        }

        Document doc = XmlReports.parse(jacocoXml);
        NodeList counters = doc.getDocumentElement().getElementsByTagName("counter");
        for (int i = 0; i < counters.getLength(); i++) {
            Element counter = (Element) counters.item(i);
            if ("LINE".equals(counter.getAttribute("type"))) {
                int missed = parseIntAttr(counter, "missed");
                int covered = parseIntAttr(counter, "covered");
                double percent = (missed + covered) == 0 ? 0.0 : (100.0 * covered) / (missed + covered);
                return new CoverageReport(percent, covered, missed);
            }
        }
        return CoverageReport.EMPTY;
    }

    private void collectIfPresent(Path reportFile, java.util.function.Consumer<Path> consumer) {
        if (Files.isRegularFile(reportFile)) {
            consumer.accept(reportFile);
        } else {
            log.debug("lint report not found, skipping: {}", reportFile);
        }
    }

    private int parseIntAttr(Element element, String attr) {
        String value = element.getAttribute(attr);
        try {
            return value.isBlank() ? 0 : (int) Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
