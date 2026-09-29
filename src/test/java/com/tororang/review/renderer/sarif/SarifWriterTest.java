package com.tororang.review.renderer.sarif;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tororang.review.core.model.Finding;
import com.tororang.review.core.model.Severity;
import com.tororang.review.core.model.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SarifWriterTest {

    private final SarifWriter writer = new SarifWriter();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void writesValidSarifStructureWithRulesAndResults(@TempDir Path dir) throws IOException {
        List<Finding> findings = List.of(
                new Finding("SEC-002", Severity.HIGH, Source.LLM, "src/main/java/PaymentClient.java", 5,
                        "API 키 하드코딩", "private String apiKey = ...", "제안", "fp-1"),
                new Finding("STYLE-010", Severity.LOW, Source.CHECKSTYLE, "src/main/java/Foo.java", 10,
                        "라인 길이 초과", "long line", null, "fp-2"),
                // 같은 ruleId가 두 번 나와도 rules 배열에는 한 번만 있어야 한다
                new Finding("SEC-002", Severity.HIGH, Source.LLM, "src/main/java/Other.java", 1,
                        "다른 하드코딩", "evidence2", null, "fp-3")
        );

        Path output = dir.resolve("review-report.sarif");
        writer.write(findings, output);

        JsonNode root = mapper.readTree(output.toFile());

        assertThat(root.get("version").asText()).isEqualTo("2.1.0");
        assertThat(root.has("$schema")).isTrue();

        JsonNode run = root.get("runs").get(0);
        JsonNode driver = run.get("tool").get("driver");
        assertThat(driver.get("name").asText()).isEqualTo("ai-review-engine");

        JsonNode rules = driver.get("rules");
        assertThat(rules).hasSize(2);
        assertThat(rules.get(0).get("id").asText()).isEqualTo("SEC-002");
        assertThat(rules.get(1).get("id").asText()).isEqualTo("STYLE-010");

        JsonNode results = run.get("results");
        assertThat(results).hasSize(3);

        JsonNode first = results.get(0);
        assertThat(first.get("ruleId").asText()).isEqualTo("SEC-002");
        assertThat(first.get("level").asText()).isEqualTo("error");
        assertThat(first.get("message").get("text").asText()).isEqualTo("API 키 하드코딩");
        JsonNode location = first.get("locations").get(0).get("physicalLocation");
        assertThat(location.get("artifactLocation").get("uri").asText()).isEqualTo("src/main/java/PaymentClient.java");
        assertThat(location.get("region").get("startLine").asInt()).isEqualTo(5);
        assertThat(first.get("partialFingerprints").get("primaryFingerprint").asText()).isEqualTo("fp-1");

        assertThat(results.get(1).get("level").asText()).isEqualTo("note"); // LOW
    }

    @Test
    void mapsSeverityToSarifLevelsCorrectly(@TempDir Path dir) throws IOException {
        List<Finding> findings = List.of(
                new Finding("A-001", Severity.HIGH, Source.LLM, "F.java", 1, "m", "e", null, "fp-h"),
                new Finding("A-002", Severity.MEDIUM, Source.LLM, "F.java", 2, "m", "e", null, "fp-m"),
                new Finding("A-003", Severity.LOW, Source.LLM, "F.java", 3, "m", "e", null, "fp-l"),
                new Finding("A-004", Severity.INFO, Source.LLM, "F.java", 4, "m", "e", null, "fp-i")
        );

        Path output = dir.resolve("review-report.sarif");
        writer.write(findings, output);

        JsonNode results = mapper.readTree(output.toFile()).get("runs").get(0).get("results");
        assertThat(results.get(0).get("level").asText()).isEqualTo("error");
        assertThat(results.get(1).get("level").asText()).isEqualTo("warning");
        assertThat(results.get(2).get("level").asText()).isEqualTo("note");
        assertThat(results.get(3).get("level").asText()).isEqualTo("note");
    }

    @Test
    void writesEmptyRunsWhenNoFindings(@TempDir Path dir) throws IOException {
        Path output = dir.resolve("review-report.sarif");
        writer.write(List.of(), output);

        JsonNode run = mapper.readTree(output.toFile()).get("runs").get(0);
        assertThat(run.get("tool").get("driver").get("rules")).isEmpty();
        assertThat(run.get("results")).isEmpty();
    }
}