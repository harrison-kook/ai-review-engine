package com.tororang.review.adapter.git;

import com.tororang.review.core.adapter.DiffScope;
import com.tororang.review.core.util.ProcessExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 git 저장소를 만들어 진짜 `git diff` 출력 포맷으로 파싱 로직을 검증한다
 * (fixture 텍스트만으로는 실제 git 출력 포맷과 어긋날 위험이 있다 — 이번 세션에서
 * 비슷한 종류의 포맷 가정 버그를 몇 번 실제로 잡았기 때문에 라이브로 검증한다).
 */
class LocalGitDiffCollectorTest {

    @Test
    void detectsAddedLinesBetweenTwoCommits(@TempDir Path repo) throws IOException {
        initRepo(repo);

        Path file = repo.resolve("src/main/java/com/example/OrderService.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                package com.example;

                public class OrderService {
                    public void approve(String orderId) {
                        pgClient.approve(orderId);
                    }
                }
                """);
        commitAll(repo, "base");

        Files.writeString(file, """
                package com.example;

                public class OrderService {
                    public void approve(String orderId) {
                        if (alreadyApproved(orderId)) {
                            return;
                        }
                        pgClient.approve(orderId);
                    }
                }
                """);
        commitAll(repo, "add idempotency check");

        LocalGitDiffCollector collector = new LocalGitDiffCollector(repo, "HEAD~1");
        DiffScope scope = collector.fetchChangedLines();

        String relativePath = "src/main/java/com/example/OrderService.java";
        assertThat(scope.changedLinesByFile()).containsKey(relativePath);
        assertThat(scope.isInScope(relativePath, 5)).isTrue();
        assertThat(scope.isInScope(relativePath, 6)).isTrue();
        assertThat(scope.isInScope(relativePath, 1)).isFalse();
    }

    @Test
    void ignoresDeletedFiles(@TempDir Path repo) throws IOException {
        initRepo(repo);

        Path file = repo.resolve("Old.java");
        Files.writeString(file, "class Old {}\n");
        commitAll(repo, "add Old.java");

        Files.delete(file);
        commitAll(repo, "delete Old.java");

        LocalGitDiffCollector collector = new LocalGitDiffCollector(repo, "HEAD~1");
        DiffScope scope = collector.fetchChangedLines();

        assertThat(scope.changedLinesByFile()).doesNotContainKey("Old.java");
    }

    @Test
    void returnsEmptyScopeWhenBaseRefInvalid(@TempDir Path repo) throws IOException {
        initRepo(repo);
        Files.writeString(repo.resolve("A.java"), "class A {}\n");
        commitAll(repo, "add A");

        LocalGitDiffCollector collector = new LocalGitDiffCollector(repo, "does-not-exist-ref");

        assertThat(collector.fetchChangedLines()).isEqualTo(DiffScope.EMPTY);
    }

    private void initRepo(Path repo) {
        run(repo, "git", "init", "-q");
        run(repo, "git", "config", "user.email", "test@example.com");
        run(repo, "git", "config", "user.name", "Test");
        run(repo, "git", "config", "commit.gpgsign", "false");
    }

    private void commitAll(Path repo, String message) {
        run(repo, "git", "add", "-A");
        run(repo, "git", "commit", "-q", "-m", message);
    }

    private void run(Path repo, String... command) {
        var outcome = ProcessExecutor.run(repo, Duration.ofSeconds(30), List.of(command));
        if (!outcome.success()) {
            throw new IllegalStateException("command failed: " + String.join(" ", command) + "\n" + outcome.output());
        }
    }
}