package com.tororang.review.core.adapter;

/**
 * diff 모드에서 "어떤 파일의 어떤 라인이 바뀌었는지"를 제공한다.
 * 1단계 구현체는 adapter.github.GitHubDiffCollector (PR 기준).
 */
public interface DiffSource {

    DiffScope fetchChangedLines();
}
