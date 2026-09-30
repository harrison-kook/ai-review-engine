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
 * 병합 순서: profiles 배열 순서(common → 스택 → domain → team 등) → 레포 로컬 규칙
 * (`.review-rules/`) → .review.yml overrides. 뒤에 나온 것이 앞선 것을 덮어쓴다 (설계서 2장,
 * 6장). 레포 로컬 규칙은 대상 레포 자체 안에 두는 규칙으로, 그 레포의 접근 권한이 곧 규칙의
 * 접근 권한이 된다 — 여러 레포가 공유할 필요 없는, 그 레포에만 해당하는 내부 정책에 쓴다.
 */
public final class RuleMerger {

    /** 대상 레포 안에서 레포 로컬 규칙을 찾는 고정 경로. */
    public static final String LOCAL_RULES_DIR_NAME = ".review-rules";

    private final RuleCatalog catalog;

    public RuleMerger() {
        this(new RuleCatalog());
    }

    public RuleMerger(RuleCatalog catalog) {
        this.catalog = catalog;
    }

    public List<RuleDefinition> merge(Path rulepackDir, Path repoRoot, ReviewConfig config) {
        Map<String, RuleDefinition> merged = new LinkedHashMap<>();
        for (String profile : config.profiles()) {
            for (RuleDefinition rule : catalog.scanProfile(rulepackDir, profile)) {
                merged.put(rule.ruleId(), rule);
            }
        }

        Path localRulesDir = repoRoot.resolve(LOCAL_RULES_DIR_NAME).resolve("rules");
        for (RuleDefinition rule : catalog.scanDirectory(localRulesDir)) {
            merged.put(rule.ruleId(), rule);
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
