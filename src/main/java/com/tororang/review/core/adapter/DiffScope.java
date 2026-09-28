package com.tororang.review.core.adapter;

import java.util.Map;
import java.util.Set;

/**
 * diff 모드 리뷰 범위. 파일별로 "새 버전 기준" 변경/추가된 라인 번호 집합을 담는다.
 * 삭제된 라인은 새 파일에 존재하지 않으므로 대상이 아니다 (설계서 3.1, 8장 "인라인 코멘트는
 * 변경된 라인에만").
 */
public record DiffScope(Map<String, Set<Integer>> changedLinesByFile) {

    public static final DiffScope EMPTY = new DiffScope(Map.of());

    public DiffScope {
        changedLinesByFile = Map.copyOf(changedLinesByFile);
    }

    public boolean isInScope(String file, int line) {
        Set<Integer> lines = changedLinesByFile.get(file);
        return lines != null && lines.contains(line);
    }
}
