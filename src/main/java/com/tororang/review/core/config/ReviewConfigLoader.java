package com.tororang.review.core.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.tororang.review.core.model.Severity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * .review.yml 을 읽어 {@link ReviewConfig}로 변환한다.
 * overrides 블록은 "disable"(고정 키)와 규칙 ID(동적 키)가 섞여 있어 POJO 자동 매핑 대신
 * JsonNode를 직접 순회해서 구성한다.
 */
public final class ReviewConfigLoader {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public ReviewConfig load(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new ReviewConfigException("review config file not found: " + path);
        }
        JsonNode root;
        try {
            root = yamlMapper.readTree(path.toFile());
        } catch (IOException e) {
            throw new ReviewConfigException("failed to read review config: " + path, e);
        }
        return new ReviewConfig(
                textOrNull(root, "rulepack"),
                stringList(root.get("profiles")),
                ReviewMode.from(textOrNull(root, "mode")),
                stringList(root.get("include")),
                stringList(root.get("exclude")),
                parseOverrides(root.get("overrides")),
                parseLimits(root.get("limits")),
                parseGate(root.get("gate")),
                parseE2e(root.get("e2e"))
        );
    }

    private Overrides parseOverrides(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Overrides.EMPTY;
        }
        List<String> disable = stringList(node.get("disable"));
        Map<String, Severity> severityOverrides = new HashMap<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            if ("disable".equals(entry.getKey())) {
                continue;
            }
            JsonNode severityNode = entry.getValue().get("severity");
            if (severityNode != null && !severityNode.isNull()) {
                severityOverrides.put(entry.getKey(), Severity.valueOf(severityNode.asText().toUpperCase()));
            }
        }
        return new Overrides(disable, severityOverrides);
    }

    private Limits parseLimits(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Limits.EMPTY;
        }
        JsonNode maxDiffLines = node.get("max_diff_lines");
        return new Limits(maxDiffLines != null && !maxDiffLines.isNull() ? maxDiffLines.asInt() : null);
    }

    private Gate parseGate(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Gate.DISABLED;
        }
        JsonNode failOnNode = node.get("fail_on");
        Severity failOn = (failOnNode == null || failOnNode.isNull())
                ? null
                : Severity.valueOf(failOnNode.asText().toUpperCase());

        JsonNode minCoverageNode = node.get("min_coverage_delta");
        Double minCoverageDelta = (minCoverageNode == null || minCoverageNode.isNull())
                ? null
                : minCoverageNode.asDouble();

        JsonNode minMutationNode = node.get("min_mutation_score");
        Double minMutationScore = (minMutationNode == null || minMutationNode.isNull())
                ? null
                : minMutationNode.asDouble();

        JsonNode e2eFailOnAnyNode = node.get("e2e_fail_on_any");
        boolean e2eFailOnAny = e2eFailOnAnyNode != null && !e2eFailOnAnyNode.isNull() && e2eFailOnAnyNode.asBoolean();

        return new Gate(failOn, minCoverageDelta, minMutationScore, e2eFailOnAny);
    }

    private E2eConfig parseE2e(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return E2eConfig.DISABLED;
        }

        JsonNode enabledNode = node.get("enabled");
        boolean enabled = enabledNode != null && !enabledNode.isNull() && enabledNode.asBoolean();

        List<E2eConfig.ServiceSpec> services = new ArrayList<>();
        JsonNode servicesNode = node.get("services");
        if (servicesNode != null && servicesNode.isArray()) {
            for (JsonNode serviceNode : servicesNode) {
                services.add(new E2eConfig.ServiceSpec(
                        textOrNull(serviceNode, "name"),
                        textOrNull(serviceNode, "start"),
                        textOrNull(serviceNode, "working_directory"),
                        textOrNull(serviceNode, "healthcheck_url"),
                        intOrDefault(serviceNode.get("timeout_seconds"), 120)
                ));
            }
        }

        return new E2eConfig(
                enabled,
                textOrNull(node, "working_directory"),
                services,
                textOrNull(node, "test_command"),
                intOrDefault(node.get("timeout_seconds"), 300)
        );
    }

    private int intOrDefault(JsonNode node, int defaultValue) {
        return (node == null || node.isNull()) ? defaultValue : node.asInt();
    }

    private String textOrNull(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private List<String> stringList(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        node.forEach(item -> result.add(item.asText()));
        return result;
    }
}
