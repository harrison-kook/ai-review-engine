package com.tororang.review.core.pipeline;

public class BuildFailedException extends RuntimeException {

    public BuildFailedException(String message) {
        super(message);
    }
}
