package com.tororang.review.stack.node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class NodeStackAdapterTest {

    private final NodeStackAdapter adapter = new NodeStackAdapter();

    @Test
    void detectsNodeProject(@TempDir Path repoRoot) throws IOException {
        Files.writeString(repoRoot.resolve("package.json"), "{\"name\":\"sample\"}");

        assertThat(adapter.detect(repoRoot)).isTrue();
    }

    @Test
    void doesNotDetectNonNodeProject(@TempDir Path repoRoot) {
        assertThat(adapter.detect(repoRoot)).isFalse();
    }

    @Test
    void idIsNode() {
        assertThat(adapter.id()).isEqualTo("node");
    }
}
