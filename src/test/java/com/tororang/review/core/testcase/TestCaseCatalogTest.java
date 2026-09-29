package com.tororang.review.core.testcase;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestCaseCatalogTest {

    private final TestCaseCatalog catalog = new TestCaseCatalog();

    @Test
    void scansTcIdsAndTitles(@TempDir Path rulepackDir) throws IOException {
        Path domainDir = rulepackDir.resolve("testcases/domain");
        Files.createDirectories(domainDir);
        Files.writeString(domainDir.resolve("payment-flow.md"), """
                # 도메인 · 결제 플로우 테스트케이스

                ## TC-PAY-001: 동일 주문번호 중복 승인 요청 시 멱등 처리
                - 대상 계층: Service
                - 우선순위: P1

                ## TC-PAY-002: 전체 취소 후 상태 전이 확인
                - 대상 계층: Service
                - 우선순위: P1
                """);

        List<TestCaseDefinition> definitions = catalog.scanProfile(rulepackDir, "domain");

        assertThat(definitions).extracting(TestCaseDefinition::tcId).containsExactly("TC-PAY-001", "TC-PAY-002");
        assertThat(definitions.get(0).title()).isEqualTo("동일 주문번호 중복 승인 요청 시 멱등 처리");
    }

    @Test
    void scanProfilesMergesMultipleProfilesInOrder(@TempDir Path rulepackDir) throws IOException {
        writeTc(rulepackDir, "common", "api-contract.md", "TC-API-001", "필수 필드 누락");
        writeTc(rulepackDir, "domain", "payment-flow.md", "TC-PAY-001", "중복 승인");

        List<TestCaseDefinition> definitions = catalog.scanProfiles(rulepackDir, List.of("common", "domain"));

        assertThat(definitions).extracting(TestCaseDefinition::tcId).containsExactly("TC-API-001", "TC-PAY-001");
    }

    @Test
    void returnsEmptyListWhenProfileDirMissing(@TempDir Path rulepackDir) {
        assertThat(catalog.scanProfile(rulepackDir, "does-not-exist")).isEmpty();
    }

    private void writeTc(Path rulepackDir, String profile, String fileName, String tcId, String title) throws IOException {
        Path dir = rulepackDir.resolve("testcases").resolve(profile);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(fileName), "## %s: %s\n- 대상 계층: Service\n".formatted(tcId, title));
    }
}
