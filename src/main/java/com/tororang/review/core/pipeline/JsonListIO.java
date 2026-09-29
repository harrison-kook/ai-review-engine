package com.tororang.review.core.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 파이프라인 단계(별도 프로세스/컨테이너일 수 있다) 사이에서 리스트를 JSON 파일로 주고받는
 * 공통 유틸. Finding, GeneratedTestCase, TestCaseReport 모두 이 유틸로 직렬화한다.
 */
public final class JsonListIO {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonListIO() {
    }

    public static <T> void write(Path path, List<T> items) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), items);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write json list: " + path, e);
        }
    }

    public static <T> List<T> read(Path path, Class<T> itemType) {
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        try {
            CollectionType listType = MAPPER.getTypeFactory().constructCollectionType(List.class, itemType);
            return MAPPER.readValue(path.toFile(), listType);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read json list: " + path, e);
        }
    }
}
