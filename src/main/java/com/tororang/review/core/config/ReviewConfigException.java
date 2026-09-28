package com.tororang.review.core.config;

public class ReviewConfigException extends RuntimeException {

    public ReviewConfigException(String message) {
        super(message);
    }

    public ReviewConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
