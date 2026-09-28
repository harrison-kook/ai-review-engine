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
                parseGate(root.get("gate"))
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
        JsonNode failOn = node.get("fail_on");
        if (failOn == null || failOn.isNull()) {
            return Gate.DISABLED;
        }
        return new Gate(Severity.valueOf(failOn.asText().toUpperCase()));
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
