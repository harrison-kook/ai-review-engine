package com.tororang.review.stack.node;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.tororang.review.core.util.ProcessOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 설계서 4.2/7.1의 두 번째 스택 어댑터. package.json 기반 Node.js 프로젝트(백엔드 Node.js,
 * React/Next.js/Vue.js 프론트엔드 포함)를 대상으로 한다. react/vue/next 여부를 어댑터가
 * 구분하지 않는다 — 대상 레포에 이미 설정된 ESLint를 그대로 실행해서 그 결과만 파싱한다
 * (GradleSpringStackAdapter.lint()가 설정된 정적분석 플러그인만 결과를 내는 것과 같은 원칙).
 *
 * <p>레포 루트에 package.json이 없으면 바로 아래 서브디렉터리(깊이 1)를 한 번 더 찾아본다 —
 * 백엔드(Java/Spring 등)가 루트에 있고 프론트엔드가 {@code frontend/} 같은 서브디렉터리에
 * 별도 package.json으로 있는 모노레포를 지원하기 위함이다. {@code ReviewPipeline}이 레포당
 * 감지되는 모든 StackAdapter를 함께 실행하므로, 같은 레포에서 Gradle 어댑터와 이 어댑터가
 * 동시에 선택될 수 있다.
 *
 * <p>소스 리뷰(lint)에 더해 테스트 실행(Jest/Vitest)까지 지원한다 — 대상 레포의
 * {@code package.json}에 둘 중 하나가 의존성으로 잡혀 있으면 그 러너로 실행하고, 둘 다 없으면
 * {@link TestResult#NONE}을 돌려준다(설정 없는 플러그인은 결과를 안 내는 lint()와 같은 원칙).
 * {@code coverage()}/{@code mutate()}는 Istanbul/Stryker 연동이 추가되기 전까지 스텁(EMPTY)이다.
 */
@Component
public class NodeStackAdapter implements StackAdapter {

    private static final Logger log = LoggerFactory.getLogger(NodeStackAdapter.class);
    private static final Path ESLINT_REPORT_RELATIVE_PATH = Path.of(".ai-review-engine", "eslint-report.json");
    private static final Path TEST_REPORT_RELATIVE_PATH = Path.of(".ai-review-engine", "node-test-report.json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NpmRunner runner;
    private final EsLintReportParser eslintParser = new EsLintReportParser();
    private final JestJsonReportParser testReportParser = new JestJsonReportParser();

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
        return findNodeProjectRoot(repoRoot) != null;
    }

    @Override
    public StepResult build(Workspace ws) {
        Path nodeRoot = requireNodeProjectRoot(ws.repoRoot());
        boolean hasLockfile = Files.isRegularFile(nodeRoot.resolve("package-lock.json"));
        ProcessOutcome result = hasLockfile ? runner.npm(nodeRoot, "ci") : runner.npm(nodeRoot, "install");
        return new StepResult(result.success(), result.exitCode(), result.output());
    }

    @Override
    public List<Finding> lint(Workspace ws) {
        Path repoRoot = ws.repoRoot();
        Path nodeRoot = requireNodeProjectRoot(repoRoot);
        Path reportPath = nodeRoot.resolve(ESLINT_REPORT_RELATIVE_PATH);
        createParentDirs(reportPath);

        // eslint는 린트 위반이 있으면 비정상 종료 코드를 내므로(설정이 없어도 마찬가지),
        // gradlew check와 같은 이유로 종료 코드는 무시하고 리포트 파일이 생겼는지만 본다.
        ProcessOutcome result = runner.npx(nodeRoot, "--no-install", "eslint", ".",
                "--format", "json", "--output-file", reportPath.toString());
        if (result.timedOut()) {
            log.warn("lint (eslint) timed out for {}", nodeRoot);
        }

        List<FindingCandidate> candidates = new ArrayList<>();
        if (Files.isRegularFile(reportPath)) {
            // repoRoot 기준으로 상대화한다(nodeRoot가 아니라) — 서브디렉터리에 있어도
            // Finding.file이 레포 전체 관점의 경로(예: frontend/src/App.tsx)를 가리키도록.
            candidates.addAll(eslintParser.parse(reportPath, repoRoot));
        } else {
            log.debug("eslint report not found, skipping: {}", reportPath);
        }

        return FindingsFilter.filter(candidates);
    }

    @Override
    public TestResult test(Workspace ws) {
        Path repoRoot = ws.repoRoot();
        Path nodeRoot = requireNodeProjectRoot(repoRoot);

        NodeTestRunner testRunner = detectTestRunner(nodeRoot);
        if (testRunner == NodeTestRunner.NONE) {
            return TestResult.NONE;
        }

        Path reportPath = nodeRoot.resolve(TEST_REPORT_RELATIVE_PATH);
        createParentDirs(reportPath);

        // jest/vitest는 실패한 테스트가 있으면 비정상 종료 코드를 내므로, eslint/gradle check와
        // 같은 이유로 종료 코드는 무시하고 리포트 파일이 생겼는지만 본다.
        ProcessOutcome result = testRunner.run(runner, nodeRoot, reportPath);
        if (result.timedOut()) {
            log.warn("test ({}) timed out for {}", testRunner, nodeRoot);
        }

        if (!Files.isRegularFile(reportPath)) {
            log.debug("test report not found, skipping: {}", reportPath);
            return TestResult.NONE;
        }

        List<TestCaseResult> cases = testReportParser.parse(reportPath, repoRoot);
        int failed = (int) cases.stream().filter(c -> !c.passed()).count();
        return new TestResult(failed == 0, cases.size(), failed, cases, result.output());
    }

    @Override
    public CoverageReport coverage(Workspace ws) {
        return CoverageReport.EMPTY;
    }

    @Override
    public MutationReport mutate(Workspace ws) {
        return MutationReport.EMPTY;
    }

    /**
     * repoRoot 자체에 package.json이 있으면 그걸 쓰고, 없으면 바로 아래 서브디렉터리(깊이 1,
     * 숨김 디렉터리 제외) 중 package.json을 가진 첫 디렉터리를 쓴다. 후보가 여럿이면(예:
     * frontend/와 admin-frontend/가 둘 다 있는 경우) 이름 순으로 첫 번째만 선택하고 경고를
     * 남긴다 — 그 이상 깊은 모노레포 구조나 다중 프론트엔드는 아직 지원하지 않는다.
     */
    private Path findNodeProjectRoot(Path repoRoot) {
        if (Files.isRegularFile(repoRoot.resolve("package.json"))) {
            return repoRoot;
        }
        if (!Files.isDirectory(repoRoot)) {
            return null;
        }
        try (Stream<Path> children = Files.list(repoRoot)) {
            List<Path> candidates = children
                    .filter(Files::isDirectory)
                    .filter(dir -> !dir.getFileName().toString().startsWith("."))
                    .filter(dir -> Files.isRegularFile(dir.resolve("package.json")))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .toList();
            if (candidates.isEmpty()) {
                return null;
            }
            if (candidates.size() > 1) {
                log.warn("여러 서브디렉터리에서 package.json을 찾음, {}를 사용: {}", candidates.get(0), candidates);
            }
            return candidates.get(0);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to scan for package.json under " + repoRoot, e);
        }
    }

    private Path requireNodeProjectRoot(Path repoRoot) {
        Path nodeRoot = findNodeProjectRoot(repoRoot);
        if (nodeRoot == null) {
            throw new IllegalStateException("no package.json found under " + repoRoot);
        }
        return nodeRoot;
    }

    private void createParentDirs(Path reportPath) {
        try {
            Files.createDirectories(reportPath.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException("failed to create eslint report directory: " + reportPath.getParent(), e);
        }
    }

    /**
     * {@code package.json}의 dependencies/devDependencies를 보고 어떤 테스트 러너가 설치돼
     * 있는지 고른다. 둘 다 있으면 vitest를 우선한다(vitest는 jest API와 호환되도록 jest를
     * devDependencies에 같이 두는 레포가 있을 수 있어서, 실제로 쓰는 쪽을 더 신뢰할 수 있는
     * vitest 쪽으로 판단한다). 둘 다 없으면 테스트를 실행하지 않는다 — 설정 없는 정적분석
     * 플러그인은 결과를 안 내는 lint()와 같은 원칙이다.
     */
    NodeTestRunner detectTestRunner(Path nodeRoot) {
        PackageJson packageJson = readPackageJson(nodeRoot);
        if (packageJson == null) {
            return NodeTestRunner.NONE;
        }
        if (hasDependency(packageJson, "vitest")) {
            return NodeTestRunner.VITEST;
        }
        if (hasDependency(packageJson, "jest")) {
            return NodeTestRunner.JEST;
        }
        return NodeTestRunner.NONE;
    }

    private boolean hasDependency(PackageJson packageJson, String name) {
        return packageJson.dependencies().containsKey(name) || packageJson.devDependencies().containsKey(name);
    }

    private PackageJson readPackageJson(Path nodeRoot) {
        Path packageJsonPath = nodeRoot.resolve("package.json");
        try {
            return MAPPER.readValue(packageJsonPath.toFile(), PackageJson.class);
        } catch (IOException e) {
            log.warn("failed to read package.json: {} ({})", packageJsonPath, e.getMessage());
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PackageJson(Map<String, String> dependencies, Map<String, String> devDependencies) {
        private PackageJson {
            dependencies = dependencies == null ? Map.of() : Map.copyOf(dependencies);
            devDependencies = devDependencies == null ? Map.of() : Map.copyOf(devDependencies);
        }
    }

    enum NodeTestRunner {
        NONE {
            @Override
            ProcessOutcome run(NpmRunner runner, Path nodeRoot, Path reportPath) {
                throw new UnsupportedOperationException("NONE은 실행할 수 없다 — 호출 전에 감지 단계에서 걸러야 한다");
            }
        },
        VITEST {
            @Override
            ProcessOutcome run(NpmRunner runner, Path nodeRoot, Path reportPath) {
                return runner.npx(nodeRoot, "--no-install", "vitest", "run",
                        "--reporter=json", "--outputFile=" + reportPath);
            }
        },
        JEST {
            @Override
            ProcessOutcome run(NpmRunner runner, Path nodeRoot, Path reportPath) {
                return runner.npx(nodeRoot, "--no-install", "jest",
                        "--json", "--outputFile=" + reportPath);
            }
        };

        abstract ProcessOutcome run(NpmRunner runner, Path nodeRoot, Path reportPath);
    }
}
