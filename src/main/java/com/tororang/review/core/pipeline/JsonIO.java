package com.tororang.review.core.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@link JsonListIO}와 대응하는 단일 객체용 IO. CoverageDelta처럼 리스트가 아닌 값을
 * 파이프라인 단계 사이에서 파일로 주고받을 때 쓴다.
 */
public final class JsonIO {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonIO() {
    }

    public static <T> void write(Path path, T value) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write json: " + path, e);
        }
    }

    public static <T> T read(Path path, Class<T> type, T defaultValue) {
        if (!Files.isRegularFile(path)) {
            return defaultValue;
        }
        try {
            return MAPPER.readValue(path.toFile(), type);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read json: " + path, e);
        }
    }
}