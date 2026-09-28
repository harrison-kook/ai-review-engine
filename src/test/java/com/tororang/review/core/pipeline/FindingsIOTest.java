package com.tororang.review.core.pipeline;

import com.tororang.review.core.model.Finding;
import com.tororang.review.core.model.Severity;
import com.tororang.review.core.model.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FindingsIOTest {

    @Test
    void roundTripsFindingsThroughJsonFile(@TempDir Path dir) {
        Path file = dir.resolve("nested/review-findings.json");
        List<Finding> findings = List.of(
                new Finding("JPA-003", Severity.HIGH, Source.CHECKSTYLE, "A.java", 10, "msg", "evidence", "suggestion", "fp-1"),
                new Finding("SEC-001", Severity.MEDIUM, Source.PMD, "B.java", 20, "msg2", "evidence2", null, "fp-2")
        );

        FindingsIO.write(file, findings);
        List<Finding> loaded = FindingsIO.read(file);

        assertThat(loaded).isEqualTo(findings);
    }

    @Test
    void returnsEmptyListWhenFileMissing(@TempDir Path dir) {
        List<Finding> loaded = FindingsIO.read(dir.resolve("does-not-exist.json"));

        assertThat(loaded).isEmpty();
    }
}
