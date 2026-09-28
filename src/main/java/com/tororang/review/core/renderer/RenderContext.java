package com.tororang.review.core.renderer;

import java.util.Map;

/**
 * 렌더러별로 필요한 부가 정보를 담는 확장 지점(출력 파일 경로 등). PR 코멘트 렌더러는
 * PR 대상 자체를 {@link com.tororang.review.core.adapter.PrCommentPublisher} 주입으로 알기
 * 때문에 이 컨텍스트에 의존하지 않는다.
 */
public record RenderContext(Map<String, String> attributes) {

    public static final RenderContext EMPTY = new RenderContext(Map.of());

    public RenderContext {
        attributes = Map.copyOf(attributes);
    }
}
