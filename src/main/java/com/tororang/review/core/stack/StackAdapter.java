package com.tororang.review.core.stack;

import com.tororang.review.core.model.Finding;

import java.nio.file.Path;
import java.util.List;

/**
 * 설계서 7.1. 스택(빌드 도구/프레임워크)별 결정적 분석을 담당한다.
 * 첫 구현체는 stack.gradlespring.GradleSpringStackAdapter.
 */
public interface StackAdapter {

    String id();

    boolean detect(Path repoRoot);

    StepResult build(Workspace ws);

    List<Finding> lint(Workspace ws);

    TestResult test(Workspace ws);

    CoverageReport coverage(Workspace ws);

    MutationReport mutate(Workspace ws);
}
