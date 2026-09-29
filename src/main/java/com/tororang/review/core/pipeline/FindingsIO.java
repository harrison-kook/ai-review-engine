package com.tororang.review.core.pipeline;

import com.tororang.review.core.model.Finding;

import java.nio.file.Path;
import java.util.List;

/**
 * BUILD 단계(샌드박스, 시크릿 없음)와 REPORT 단계(시크릿 있음) 사이에서 결정적 분석 결과를
 * 파일로 주고받기 위한 직렬화. 두 단계가 서로 다른 프로세스/컨테이너로 분리되어 메모리를
 * 공유하지 않는 것을 전제로 한다 (설계서 1.3).
 */
public final class FindingsIO {

    private FindingsIO() {
    }

    public static void write(Path path, List<Finding> findings) {
        JsonListIO.write(path, findings);
    }

    public static List<Finding> read(Path path) {
        return JsonListIO.read(path, Finding.class);
    }
}
