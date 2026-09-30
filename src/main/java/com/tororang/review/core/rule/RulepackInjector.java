package com.tororang.review.core.rule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

/**
 * 설계서 1.1: 룰팩은 대상 레포 밖에서 관리하고, 실행 시 clone한 레포 안에 .claude/ 형태로 복사해
 * 넣고 실행한다. profiles에 해당하는 규칙만 복사하며, 비활성화된 규칙(overrides.disable)은
 * 파일 단위가 아니라 규칙 단위이므로 여기서는 걸러내지 않는다 — 최종 채택 여부는
 * {@link RuleMerger}의 결과(결정적 비교용)로 판단한다.
 */
public final class RulepackInjector {

    /** 레포 로컬 규칙(레포 안 {@code .review-rules/})을 주입할 때 쓰는 고정 프로필명. */
    private static final String LOCAL_RULES_PROFILE = "_local";

    public void inject(Path rulepackDir, Path repoRoot, List<String> profiles) {
        Path claudeDir = repoRoot.resolve(".claude");
        copyFileIfExists(rulepackDir.resolve("CLAUDE.md"), claudeDir.resolve("CLAUDE.md"));
        copyDirIfExists(rulepackDir.resolve("agents"), claudeDir.resolve("agents"));
        copyDirIfExists(rulepackDir.resolve("commands"), claudeDir.resolve("commands"));
        copyDirIfExists(rulepackDir.resolve("schema"), claudeDir.resolve("rulepack/schema"));
        for (String profile : profiles) {
            copyDirIfExists(rulepackDir.resolve("rules").resolve(profile), claudeDir.resolve("rulepack/rules").resolve(profile));
            copyDirIfExists(rulepackDir.resolve("testcases").resolve(profile), claudeDir.resolve("rulepack/testcases").resolve(profile));
        }

        // 레포 로컬 규칙: 대상 레포 자체 안의 .review-rules/ — 그 레포 밖으로 절대 나가지 않고,
        // 레포 자체의 접근 권한이 곧 규칙의 접근 권한이 된다 (RuleMerger.LOCAL_RULES_DIR_NAME 참고).
        Path localRulesDir = repoRoot.resolve(RuleMerger.LOCAL_RULES_DIR_NAME);
        copyDirIfExists(localRulesDir.resolve("rules"), claudeDir.resolve("rulepack/rules").resolve(LOCAL_RULES_PROFILE));
        copyDirIfExists(localRulesDir.resolve("testcases"), claudeDir.resolve("rulepack/testcases").resolve(LOCAL_RULES_PROFILE));
    }

    private void copyFileIfExists(Path source, Path target) {
        if (!Files.isRegularFile(source)) {
            return;
        }
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to copy " + source + " -> " + target, e);
        }
    }

    private void copyDirIfExists(Path sourceDir, Path targetDir) {
        if (!Files.isDirectory(sourceDir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(sourceDir)) {
            for (Path source : paths.toList()) {
                Path relative = sourceDir.relativize(source);
                Path target = targetDir.resolve(relative.toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to copy " + sourceDir + " -> " + targetDir, e);
        }
    }
}
