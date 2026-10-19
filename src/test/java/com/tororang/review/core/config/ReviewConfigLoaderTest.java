package com.tororang.review.core.config;

import com.tororang.review.core.model.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewConfigLoaderTest {

    private final ReviewConfigLoader loader = new ReviewConfigLoader();

    @Test
    void loadsFullExampleFromDesignDoc(@TempDir Path dir) throws IOException {
        Path configPath = writeConfig(dir, """
                rulepack: our-org/review-rulepack@v1.2.0
                profiles: [common, java-spring, payment, team]
                mode: diff
                include: ["src/main/**"]
                exclude: ["**/generated/**", "**/*Dto.java"]
                overrides:
                  JPA-003: { severity: MEDIUM }
                  disable: [STYLE-010]
                limits:
                  max_diff_lines: 1500
                gate:
                  fail_on: HIGH
                """);

        ReviewConfig config = loader.load(configPath);

        assertThat(config.rulepack()).isEqualTo("our-org/review-rulepack@v1.2.0");
        assertThat(config.profiles()).containsExactly("common", "java-spring", "payment", "team");
        assertThat(config.mode()).isEqualTo(ReviewMode.DIFF);
        assertThat(config.include()).containsExactly("src/main/**");
        assertThat(config.exclude()).containsExactly("**/generated/**", "**/*Dto.java");
        assertThat(config.overrides().disable()).containsExactly("STYLE-010");
        assertThat(config.overrides().severityOverrides()).containsEntry("JPA-003", Severity.MEDIUM);
        assertThat(config.limits().maxDiffLines()).isEqualTo(1500);
        assertThat(config.gate().failOn()).isEqualTo(Severity.HIGH);
    }

    @Test
    void loadsMinimalConfigWithDefaults(@TempDir Path dir) throws IOException {
        Path configPath = writeConfig(dir, """
                rulepack: our-org/review-rulepack@v0.1.0
                profiles: [common]
                mode: full
                """);

        ReviewConfig config = loader.load(configPath);

        assertThat(config.include()).isEmpty();
        assertThat(config.exclude()).isEmpty();
        assertThat(config.overrides().disable()).isEmpty();
        assertThat(config.overrides().severityOverrides()).isEmpty();
        assertThat(config.limits().hasMaxDiffLines()).isFalse();
        assertThat(config.gate().isEnabled()).isFalse();
        assertThat(config.e2e().enabled()).isFalse();
    }

    @Test
    void loadsE2eSectionFromDesignDoc(@TempDir Path dir) throws IOException {
        Path configPath = writeConfig(dir, """
                rulepack: our-org/review-rulepack@v0.1.0
                profiles: [common]
                mode: diff
                e2e:
                  enabled: true
                  working_directory: frontend
                  services:
                    - name: backend
                      start: "./gradlew bootRun --args='--spring.profiles.active=ci-e2e'"
                      healthcheck_url: "http://localhost:8080/actuator/health"
                      timeout_seconds: 200
                    - name: frontend
                      start: "npm run build && npm run start"
                      working_directory: frontend
                      healthcheck_url: "http://localhost:3000"
                      timeout_seconds: 60
                  test_command: "npm run test:e2e"
                  timeout_seconds: 300
                gate:
                  e2e_fail_on_any: true
                """);

        ReviewConfig config = loader.load(configPath);

        assertThat(config.e2e().enabled()).isTrue();
        assertThat(config.e2e().workingDirectory()).isEqualTo("frontend");
        assertThat(config.e2e().testCommand()).isEqualTo("npm run test:e2e");
        assertThat(config.e2e().timeoutSeconds()).isEqualTo(300);
        assertThat(config.e2e().services()).hasSize(2);

        E2eConfig.ServiceSpec backend = config.e2e().services().get(0);
        assertThat(backend.name()).isEqualTo("backend");
        assertThat(backend.healthcheckUrl()).isEqualTo("http://localhost:8080/actuator/health");
        assertThat(backend.timeoutSeconds()).isEqualTo(200);
        assertThat(backend.workingDirectory()).isNull();

        E2eConfig.ServiceSpec frontend = config.e2e().services().get(1);
        assertThat(frontend.workingDirectory()).isEqualTo("frontend");

        assertThat(config.gate().e2eFailOnAny()).isTrue();
    }

    @Test
    void e2eServiceTimeoutDefaultsTo120Seconds(@TempDir Path dir) throws IOException {
        Path configPath = writeConfig(dir, """
                rulepack: our-org/review-rulepack@v0.1.0
                profiles: [common]
                mode: diff
                e2e:
                  enabled: true
                  services:
                    - name: backend
                      start: "./gradlew bootRun"
                      healthcheck_url: "http://localhost:8080/actuator/health"
                  test_command: "npm run test:e2e"
                """);

        ReviewConfig config = loader.load(configPath);

        assertThat(config.e2e().services().get(0).timeoutSeconds()).isEqualTo(120);
        assertThat(config.e2e().timeoutSeconds()).isEqualTo(300);
    }

    @Test
    void rejectsMissingRulepack(@TempDir Path dir) throws IOException {
        Path configPath = writeConfig(dir, """
                profiles: [common]
                mode: diff
                """);

        assertThatThrownBy(() -> loader.load(configPath))
                .isInstanceOf(ReviewConfigException.class)
                .hasMessageContaining("rulepack");
    }

    @Test
    void rejectsUnknownMode(@TempDir Path dir) throws IOException {
        Path configPath = writeConfig(dir, """
                rulepack: our-org/review-rulepack@v0.1.0
                profiles: [common]
                mode: staging
                """);

        assertThatThrownBy(() -> loader.load(configPath))
                .isInstanceOf(ReviewConfigException.class)
                .hasMessageContaining("mode");
    }

    private Path writeConfig(Path dir, String yaml) throws IOException {
        Path path = dir.resolve(".review.yml");
        Files.writeString(path, yaml);
        return path;
    }
}
