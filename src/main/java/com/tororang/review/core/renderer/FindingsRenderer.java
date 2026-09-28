package com.tororang.review.core.renderer;

/**
 * 설계서 7.4. PrCommentRenderer / MarkdownRenderer / SarifRenderer가 구현한다.
 */
public interface FindingsRenderer {

    void render(ReviewReport report, RenderContext ctx);
}
