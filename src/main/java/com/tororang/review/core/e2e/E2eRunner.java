package com.tororang.review.core.e2e;

import com.tororang.review.core.config.E2eConfig;
import com.tororang.review.core.pipeline.E2eResult;
import com.tororang.review.core.stack.TestCaseResult;
import com.tororang.review.core.util.ProcessExecutor;
import com.tororang.review.core.util.ProcessOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.xml.sax.SAXException;

/**
 * .review.yml의 e2e.services를 순서대로 기동하고 healthcheck가 통과하면 test_command를
 * 실행한다 (docs/E2E_PIPELINE_DESIGN.md 5장). Phase A 파일럿(같은 문서 10장)에서 실제로
 * 검증된 전제를 그대로 따른다:
 *
 * <ul>
 *   <li>DB/백엔드/프런트를 전부 <b>같은 컨테이너 안 프로세스</b>로 띄운다고 가정한다 — 이
 *   클래스는 어떤 샌드박스에서 도는지 모르지만, {@code sh -c}로 셸 문법(&&, 파이프 등)을 쓸 수
 *   있는 Linux 컨테이너를 전제로 한다 (NpmRunner/GradleWrapperRunner와 달리 Windows 로컬
 *   개발 지원은 하지 않는다 — E2E는 샌드박스 전용 기능이라서다).</li>
 *   <li>healthcheck는 단순 GET 2xx 여부로만 판단한다(Phase A에서 쓴 {@code /actuator/health}와
 *   같은 패턴).</li>
 * </ul>
 */
public final class E2eRunner {

    private static final Logger log = LoggerFactory.getLogger(E2eRunner.class);
    private static final Path JUNIT_REPORT_RELATIVE_PATH = Path.of(".ai-review-engine", "e2e-junit.xml");
    private static final Duration HEALTHCHECK_POLL_INTERVAL = Duration.ofSeconds(2);

    private final HttpClient httpClient = HttpClient.newHttpClient();

    public E2eResult run(Path repoRoot, E2eConfig config) {
        if (!config.enabled()) {
            return E2eResult.DISABLED;
        }

        List<Process> started = new ArrayList<>();
        try {
            for (E2eConfig.ServiceSpec service : config.services()) {
                Path serviceDir = resolveWorkingDirectory(repoRoot, service.workingDirectory(), config.workingDirectory());
                log.info("starting e2e service '{}': {}", service.name(), service.start());
                started.add(startBackground(serviceDir, service.name(), service.start()));

                boolean healthy = pollHealthcheck(service.healthcheckUrl(), Duration.ofSeconds(service.timeoutSeconds()));
                if (!healthy) {
                    String reason = "service '" + service.name() + "' did not become healthy within "
                            + service.timeoutSeconds() + "s (healthcheck: " + service.healthcheckUrl() + ")";
                    log.warn(reason);
                    return new E2eResult(true, false, 0, 0, List.of(), reason, null);
                }
                log.info("e2e service '{}' is healthy", service.name());
            }

            return runTestCommand(repoRoot, config);
        } finally {
            // 성공/실패/타임아웃 어떤 경로로 빠져나가든 띄운 서비스는 반드시 정리한다
            // (docs/E2E_PIPELINE_DESIGN.md 5장 — 좀비 프로세스/포트 점유 방지).
            for (Process process : started) {
                killTree(process);
            }
        }
    }

    private E2eResult runTestCommand(Path repoRoot, E2eConfig config) {
        Path testDir = resolveWorkingDirectory(repoRoot, null, config.workingDirectory());
        ProcessOutcome result = ProcessExecutor.run(
                testDir, Duration.ofSeconds(config.timeoutSeconds()), List.of("sh", "-c", config.testCommand()));
        if (result.timedOut()) {
            return new E2eResult(true, false, 0, 0, List.of(),
                    "test_command timed out after " + config.timeoutSeconds() + "s", result.output());
        }

        Path reportPath = testDir.resolve(JUNIT_REPORT_RELATIVE_PATH);
        if (!Files.isRegularFile(reportPath)) {
            String envFailureReason = result.success()
                    ? null
                    : "test_command exited with code " + result.exitCode() + " and no JUnit report was produced";
            return new E2eResult(true, result.success(), 0, 0, List.of(), envFailureReason, result.output());
        }

        List<TestCaseResult> cases = parseJunitReport(reportPath);
        int failed = (int) cases.stream().filter(c -> !c.passed()).count();
        return new E2eResult(true, failed == 0, cases.size(), failed, cases, null, result.output());
    }

    /**
     * JUnit XML(surefire 호환 포맷) 파싱. {@code stack.gradlespring.JUnitReportParser}와 거의
     * 같은 로직이지만 일부러 재사용하지 않는다 — core 패키지는 구현(stack.*) 패키지에 의존하면
     * 안 된다는 아키텍처 규칙(ArchitectureTest) 때문이다. 그 파서의 "메서드명 끝 () 제거" 보정은
     * Gradle/JUnit5 전용 특이사항이라 Playwright 등 다른 러너의 출력에는 필요 없어서 그대로
     * 들고 오지 않았다.
     */
    private List<TestCaseResult> parseJunitReport(Path xmlFile) {
        Document document = parseXml(xmlFile);
        List<TestCaseResult> results = new ArrayList<>();
        NodeList testcaseNodes = document.getElementsByTagName("testcase");
        for (int i = 0; i < testcaseNodes.getLength(); i++) {
            Element testcase = (Element) testcaseNodes.item(i);
            String methodName = testcase.getAttribute("name");
            String className = testcase.getAttribute("classname");

            String failureMessage = firstFailureMessage(testcase, "failure");
            if (failureMessage == null) {
                failureMessage = firstFailureMessage(testcase, "error");
            }

            results.add(new TestCaseResult(className, methodName, failureMessage == null, failureMessage));
        }
        return results;
    }

    private String firstFailureMessage(Element testcase, String tagName) {
        NodeList nodes = testcase.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return null;
        }
        Element failure = (Element) nodes.item(0);
        String message = failure.getAttribute("message");
        if (message != null && !message.isBlank()) {
            return message;
        }
        String text = failure.getTextContent();
        return text == null || text.isBlank() ? "실패(사유 없음)" : text.strip();
    }

    /** 신뢰할 수 없는 대상 레포 소스에서 파생된 XML이므로 DTD/외부 엔티티는 항상 비활성화한다(XXE 방지). */
    private Document parseXml(Path xmlFile) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            return builder.parse(xmlFile.toFile());
        } catch (ParserConfigurationException | SAXException e) {
            throw new IllegalStateException("failed to parse e2e junit report: " + xmlFile, e);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read e2e junit report: " + xmlFile, e);
        }
    }

    private Path resolveWorkingDirectory(Path repoRoot, String serviceDir, String defaultDir) {
        String dir = serviceDir != null ? serviceDir : defaultDir;
        return dir == null ? repoRoot : repoRoot.resolve(dir);
    }

    private Process startBackground(Path workingDir, String name, String command) {
        Path logFile = workingDir.resolve(".ai-review-engine").resolve(name + ".log");
        try {
            Files.createDirectories(logFile.getParent());
            ProcessBuilder builder = new ProcessBuilder("sh", "-c", command)
                    .directory(workingDir.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(logFile.toFile());
            return builder.start();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to start e2e service '" + name + "': " + command, e);
        }
    }

    private boolean pollHealthcheck(String url, Duration timeout) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build();
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            try {
                HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return true;
                }
            } catch (IOException e) {
                // 아직 리스닝 전일 수 있다 — 재시도.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            sleep(HEALTHCHECK_POLL_INTERVAL);
        }
        return false;
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Process.destroyForcibly()는 Windows에서 직접 자식만 죽인다(ProcessExecutor와 같은 이유로
     * 트리 전체를 순회한다) — 다만 이 클래스는 애초에 Linux 샌드박스 전용이라 트리 킬 자체는
     * 방어적으로만 의미가 있다.
     */
    private void killTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
