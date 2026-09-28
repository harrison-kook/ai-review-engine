package com.tororang.review.arch;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 설계서(AI_REVIEW_SYSTEM_PLAN.md) 4.3(3) 원칙을 강제한다:
 * core는 adapter/renderer/llm/특정 스택 구현을 직접 참조하지 않는다 (인터페이스만 core에 둔다).
 */
@AnalyzeClasses(packages = "com.tororang.review")
class ArchitectureTest {

    @ArchTest
    static final ArchRule core_does_not_depend_on_implementation_packages =
        noClasses().that().resideInAPackage("com.tororang.review.core..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.tororang.review.llm",
                "com.tororang.review.llm..",
                "com.tororang.review.stack.gradlespring",
                "com.tororang.review.stack.gradlespring..",
                "com.tororang.review.adapter..",
                "com.tororang.review.renderer.prcomment",
                "com.tororang.review.renderer.prcomment..",
                "com.tororang.review.renderer.markdown..",
                "com.tororang.review.renderer.sarif.."
            )
            .because("core는 파이프라인/설정/룰 병합/Finding 모델만 담당하며, 구현 패키지는 core의 인터페이스에 의존해야 한다");

    @ArchTest
    static final ArchRule core_does_not_depend_on_top_level_application_classes =
        noClasses().that().resideInAPackage("com.tororang.review.core..")
            .should().dependOnClassesThat().resideInAPackage("com.tororang.review")
            .because("core는 ReviewApplication/ReviewRunner 같은 최상위 부트스트랩 클래스에 의존하지 않는다");
}
