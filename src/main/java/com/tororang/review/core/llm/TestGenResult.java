package com.tororang.review.core.llm;

import java.util.List;

public record TestGenResult(List<GeneratedTestCase> generatedTests, String rawOutput) {
}
