package com.tororang.review.renderer.history;

import com.tororang.review.core.pipeline.JsonListIO;
import com.tororang.review.core.pipeline.ReviewRunSummary;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 리뷰 실행 기록을 {@code JsonListIO}로 하나의 JSON 배열 파일에 누적한다
 * (GeneratedTestCase/TestCaseReport와 같은 "읽고 → 추가 → 다시 쓰기" 패턴).
 * 로컬에서 반복 실행할 때만 의미가 있다 — CI 컨테이너는 매번 사라지므로 누적되지 않는다.
 */
public final class ReviewHistoryStore {

    private ReviewHistoryStore() {
    }

    public static void append(Path historyPath, ReviewRunSummary summary) {
        List<ReviewRunSummary> history = new ArrayList<>(readAll(historyPath));
        history.add(summary);
        JsonListIO.write(historyPath, history);
    }

    public static List<ReviewRunSummary> readAll(Path historyPath) {
        return JsonListIO.read(historyPath, ReviewRunSummary.class);
    }
}
