package com.tororang.review.core.rule;

import com.tororang.review.core.config.Overrides;
import com.tororang.review.core.config.ReviewConfig;
import com.tororang.review.core.model.Severity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 병합 순서: profiles 배열 순서(common → 스택 → domain → team 등) → .review.yml overrides.
 * 뒤에 나온 프로파일/override가 앞선 것을 덮어쓴다 (설계서 6장).
 */
public final class RuleMerger {

    private final RuleCatalog catalog;

    public RuleMerger() {
        this(new RuleCatalog());
    }

    public RuleMerger(RuleCatalog catalog) {
        this.catalog = catalog;
    }

    public List<RuleDefinition> merge(Path rulepackDir, ReviewConfig config) {
        Map<String, RuleDefinition> merged = new LinkedHashMap<>();
        for (String profile : config.profiles()) {
            for (RuleDefinition rule : catalog.scanProfile(rulepackDir, profile)) {
                merged.put(rule.ruleId(), rule);
            }
        }

        Overrides overrides = config.overrides();
        overrides.disable().forEach(merged::remove);
        for (Map.Entry<String, Severity> entry : overrides.severityOverrides().entrySet()) {
            RuleDefinition existing = merged.get(entry.getKey());
            if (existing != null) {
                merged.put(entry.getKey(), new RuleDefinition(existing.ruleId(), entry.getValue(), existing.sourceFile()));
            }
        }

        return new ArrayList<>(merged.values());
    }
}
