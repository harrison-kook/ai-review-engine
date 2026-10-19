package com.tororang.review.core.e2e;

import com.sun.net.httpserver.HttpServer;
import com.tororang.review.core.config.E2eConfig;
import com.tororang.review.core.pipeline.E2eResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class E2eRunnerTest {

    private final E2eRunner runner = new E2eRunner();
    private HttpServer fakeService;

    @AfterEach
    void stopFakeService() {
        if (fakeService != null) {
            fakeService.stop(0);
        }
    }

    @Test
    void returnsDisabledResultWithoutStartingAnythingWhenNotConfigured(@TempDir Path repoRoot) {
        E2eResult result = runner.run(repoRoot, E2eConfig.DISABLED);

        assertThat(result).isEqualTo(E2eResult.DISABLED);
    }

    @Test
    void reportsEnvFailureWhenServiceNeverBecomesHealthy(@TempDir Path repoRoot) {
        // 아무것도 리스닝하지 않는 포트를 healthcheck 대상으로 줘서 타임아웃을 유도한다.
        E2eConfig config = new E2eConfig(true, null, List.of(
                new E2eConfig.ServiceSpec("backend", "sleep 30", null, "http://127.0.0.1:1/health", 1)
        ), "echo ok", 10);

        E2eResult result = runner.run(repoRoot, config);

        assertThat(result.enabled()).isTrue();
        assertThat(result.success()).isFalse();
        assertThat(result.envFailureReason()).contains("backend").contains("did not become healthy");
        assertThat(result.totalTests()).isZero();
    }

    @Test
    void runsTestCommandAndParsesJunitReportAfterServiceIsHealthy(@TempDir Path repoRoot) throws IOException {
        fakeService = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeService.createContext("/health", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        fakeService.start();
        String healthcheckUrl = "http://127.0.0.1:" + fakeService.getAddress().getPort() + "/health";

        // test_command가 리포트를 만드는 걸 미리 해둔다 — 실제로는 playwright가 만든다. 복잡한
        // 멀티라인 XML을 sh -c 커맨드 문자열로 흘려보내지 않는 이유: Windows의 ProcessBuilder는
        // argv를 단일 커맨드라인 문자열로 재구성하는데, 따옴표/개행이 섞인 긴 문자열에서 그
        // 재인코딩이 깨지기 쉽다(실제로 겪음) — 운영 대상인 Linux 샌드박스에서는 문제 없지만,
        // 이 테스트는 Windows 개발 환경에서도 안정적으로 돌아야 하므로 파일은 Java로 직접 쓴다.
        Path reportPath = repoRoot.resolve(".ai-review-engine").resolve("e2e-junit.xml");
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, """
                <testsuite name="e2e" tests="2" failures="1">
                  <testcase classname="product-variant-pricing.spec.ts" name="sorts large to small">
                  </testcase>
                  <testcase classname="seller-product-lifecycle.spec.ts" name="publishes draft product">
                    <failure message="price mismatch">stack trace</failure>
                  </testcase>
                </testsuite>
                """);

        E2eConfig config = new E2eConfig(true, null, List.of(
                new E2eConfig.ServiceSpec("fake-backend", "sleep 30", null, healthcheckUrl, 10)
        ), "true", 30);

        E2eResult result = runner.run(repoRoot, config);

        assertThat(result.enabled()).isTrue();
        assertThat(result.envFailureReason()).isNull();
        assertThat(result.totalTests()).isEqualTo(2);
        assertThat(result.failedTests()).isEqualTo(1);
        assertThat(result.success()).isFalse();
        assertThat(result.cases()).extracting("methodName")
                .containsExactlyInAnyOrder("sorts large to small", "publishes draft product");
    }
}
