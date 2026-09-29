package com.tororang.review.renderer.sarif;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tororang.review.core.model.Finding;
import com.tororang.review.core.model.Severity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Findings를 SARIF 2.1.0으로 변환한다 (설계서 7.4/10장). GitHub Code Scanning 탭에
 * {@code github/codeql-action/upload-sarif}로 업로드하면 브랜치/PR별로 지적사항을
 * 추적할 수 있다 — PR 코멘트와 달리 히스토리가 남고 다른 보안 스캐너 결과와 한 화면에서 본다.
 */
public final class SarifWriter {

    private static final String SCHEMA_URL =
            "https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/Schemata/sarif-schema-2.1.0.json";
    private static final String TOOL_NAME = "ai-review-engine";

    private final ObjectMapper mapper = new ObjectMapper();

    public void write(List<Finding> findings, Path outputPath) {
        ObjectNode root = mapper.createObjectNode();
        root.put("$schema", SCHEMA_URL);
        root.put("version", "2.1.0");

        ArrayNode runs = root.putArray("runs");
        ObjectNode run = runs.addObject();
        ObjectNode driver = run.putObject("tool").putObject("driver");
        driver.put("name", TOOL_NAME);
        appendRules(driver.putArray("rules"), findings);
        appendResults(run.putArray("results"), findings);

        try {
            if (outputPath.getParent() != null) {
                Files.createDirectories(outputPath.getParent());
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), root);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write sarif report: " + outputPath, e);
        }
    }

    private void appendRules(ArrayNode rules, List<Finding> findings) {
        Set<String> seen = new LinkedHashSet<>();
        for (Finding finding : findings) {
            if (seen.add(finding.ruleId())) {
                ObjectNode rule = rules.addObject();
                rule.put("id", finding.ruleId());
                rule.putObject("shortDescription").put("text", finding.ruleId());
            }
        }
    }

    private void appendResults(ArrayNode results, List<Finding> findings) {
        for (Finding finding : findings) {
            ObjectNode result = results.addObject();
            result.put("ruleId", finding.ruleId());
            result.put("level", toSarifLevel(finding.severity()));
            result.putObject("message").put("text", finding.message());

            ObjectNode physicalLocation = result.putArray("locations").addObject().putObject("physicalLocation");
            physicalLocation.putObject("artifactLocation").put("uri", finding.file());
            physicalLocation.putObject("region").put("startLine", finding.line());

            result.putObject("partialFingerprints").put("primaryFingerprint", finding.fingerprint());
        }
    }

    private String toSarifLevel(Severity severity) {
        return switch (severity) {
            case HIGH -> "error";
            case MEDIUM -> "warning";
            case LOW, INFO -> "note";
        };
    }
}