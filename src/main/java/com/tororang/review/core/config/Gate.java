package com.tororang.review.core.config;

import com.tororang.review.core.model.Severity;

public record Gate(Severity failOn) {

    public static final Gate DISABLED = new Gate(null);

    public boolean isEnabled() {
        return failOn != null;
    }
}
