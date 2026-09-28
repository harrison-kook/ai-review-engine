package com.tororang.review.stack.gradlespring;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GradleSpringStackAdapterTest {

    private final GradleSpringStackAdapter adapter = new GradleSpringStackAdapter();

    @Test
    void detectsGradleGroovyProject(@TempDir Path repoRoot) throws IOException {
        Files.writeString(repoRoot.resolve("build.gradle"), "plugins { id 'java' }");

        assertThat(adapter.detect(repoRoot)).isTrue();
    }

    @Test
    void detectsGradleKotlinProject(@TempDir Path repoRoot) throws IOException {
        Files.writeString(repoRoot.resolve("build.gradle.kts"), "plugins { java }");

        assertThat(adapter.detect(repoRoot)).isTrue();
    }

    @Test
    void doesNotDetectNonGradleProject(@TempDir Path repoRoot) {
        assertThat(adapter.detect(repoRoot)).isFalse();
    }

    @Test
    void idIsGradleSpring() {
        assertThat(adapter.id()).isEqualTo("gradle-spring");
    }
}
