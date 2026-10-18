package com.tororang.review.stack.node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class NodeStackAdapterTest {

    private final NodeStackAdapter adapter = new NodeStackAdapter();

    @Test
    void detectsNodeProject(@TempDir Path repoRoot) throws IOException {
        Files.writeString(repoRoot.resolve("package.json"), "{\"name\":\"sample\"}");

        assertThat(adapter.detect(repoRoot)).isTrue();
    }

    @Test
    void doesNotDetectNonNodeProject(@TempDir Path repoRoot) {
        assertThat(adapter.detect(repoRoot)).isFalse();
    }

    @Test
    void detectsNodeProjectInImmediateSubdirectory(@TempDir Path repoRoot) throws IOException {
        // 모노레포: 루트는 build.gradle(다른 스택)이고, frontend/ 서브디렉터리에 package.json이 있는 경우.
        Files.writeString(repoRoot.resolve("build.gradle"), "plugins { id 'java' }");
        Path frontend = repoRoot.resolve("frontend");
        Files.createDirectories(frontend);
        Files.writeString(frontend.resolve("package.json"), "{\"name\":\"frontend\"}");

        assertThat(adapter.detect(repoRoot)).isTrue();
    }

    @Test
    void ignoresHiddenDirectoriesWhenScanningSubdirectories(@TempDir Path repoRoot) throws IOException {
        Path hidden = repoRoot.resolve(".git");
        Files.createDirectories(hidden);
        Files.writeString(hidden.resolve("package.json"), "{}");

        assertThat(adapter.detect(repoRoot)).isFalse();
    }

    @Test
    void doesNotDetectWhenPackageJsonIsTwoLevelsDeep(@TempDir Path repoRoot) throws IOException {
        Path nested = repoRoot.resolve("apps").resolve("frontend");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("package.json"), "{}");

        assertThat(adapter.detect(repoRoot)).isFalse();
    }

    @Test
    void idIsNode() {
        assertThat(adapter.id()).isEqualTo("node");
    }

    @Test
    void detectsVitestFromDevDependencies(@TempDir Path repoRoot) throws IOException {
        Files.writeString(repoRoot.resolve("package.json"), """
                { "devDependencies": { "vitest": "^2.0.0" } }
                """);

        assertThat(adapter.detectTestRunner(repoRoot)).isEqualTo(NodeStackAdapter.NodeTestRunner.VITEST);
    }

    @Test
    void detectsJestFromDependencies(@TempDir Path repoRoot) throws IOException {
        Files.writeString(repoRoot.resolve("package.json"), """
                { "dependencies": { "jest": "^29.0.0" } }
                """);

        assertThat(adapter.detectTestRunner(repoRoot)).isEqualTo(NodeStackAdapter.NodeTestRunner.JEST);
    }

    @Test
    void prefersVitestWhenBothAreConfigured(@TempDir Path repoRoot) throws IOException {
        Files.writeString(repoRoot.resolve("package.json"), """
                { "devDependencies": { "jest": "^29.0.0", "vitest": "^2.0.0" } }
                """);

        assertThat(adapter.detectTestRunner(repoRoot)).isEqualTo(NodeStackAdapter.NodeTestRunner.VITEST);
    }

    @Test
    void detectsNoTestRunnerWhenNeitherIsConfigured(@TempDir Path repoRoot) throws IOException {
        Files.writeString(repoRoot.resolve("package.json"), """
                { "dependencies": { "react": "^18.0.0" } }
                """);

        assertThat(adapter.detectTestRunner(repoRoot)).isEqualTo(NodeStackAdapter.NodeTestRunner.NONE);
    }

    @Test
    void detectsNoTestRunnerWhenPackageJsonIsMissing(@TempDir Path repoRoot) {
        assertThat(adapter.detectTestRunner(repoRoot)).isEqualTo(NodeStackAdapter.NodeTestRunner.NONE);
    }
}
