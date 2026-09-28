package com.tororang.review.core.pipeline;

import com.tororang.review.core.config.ReviewConfig;
import com.tororang.review.core.config.ReviewConfigLoader;
import com.tororang.review.core.llm.LlmClient;
import com.tororang.review.core.llm.ReviewRequest;
import com.tororang.review.core.llm.ReviewResult;
import com.tororang.review.core.model.Finding;
import com.tororang.review.core.model.FindingsFilter;
import com.tororang.review.core.renderer.ReviewReport;
import com.tororang.review.core.rule.RuleDefinition;
import com.tororang.review.core.rule.RuleMerger;
import com.tororang.review.core.rule.RulepackInjector;
import com.tororang.review.core.stack.StackAdapter;
import com.tororang.review.core.stack.StepResult;
import com.tororang.review.core.stack.Workspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 설계서 3장 파이프라인을 두 단계로 나눈다 (설계서 1.3의 샌드박스/시크릿 분리 원칙):
 *
 * <p>{@link #runBuildPhase}: 대상 레포의 신뢰할 수 없는 코드를 실행하는 유일한 단계
 * (StackAdapter.build/lint, 즉 gradlew). 시크릿을 전혀 필요로 하지 않으며, 룰팩/LLM/GitHub
 * 어떤 것도 건드리지 않는다 — 네트워크 차단 샌드박스 컨테이너에서 실행하기 위함이다.
 * 결과는 findingsPath에 JSON으로 저장하고 프로세스를 종료한다.
 *
 * <p>{@link #runReportPhase}: 룰팩 주입 + LLM 리뷰. 시크릿(Anthropic API 키)과 네트워크가
 * 필요하지만, 대상 레포의 코드를 실행하지 않고 파일을 읽기만 한다(agents/reviewer.md의
 * Read/Grep/Glob 전용 도구 제한과 대응).
 *
 * <p>{@link #run}은 두 단계를 한 프로세스에서 순차 실행한다 — 로컬 개발/CLI 즉석 실행처럼
 * 샌드박스 격리가 필요 없는 경우를 위한 편의 메서드다. CI에서는 두 단계를 별도 컨테이너
 * 실행으로 나눠 호출해야 한다 (docker/README.md 참고).
 */
@Component
public class ReviewPipeline {

    private static final Logger log = LoggerFactory.getLogger(ReviewPipeline.class);

    private final List<StackAdapter> stackAdapters;
    private final LlmClient llmClient;
    private final ReviewConfigLoader configLoader = new ReviewConfigLoader();
    private final RuleMerger ruleMerger = new RuleMerger();
    private final RulepackInjector rulepackInjector = new RulepackInjector();

    public ReviewPipeline(List<StackAdapter> stackAdapters, LlmClient llmClient) {
        this.stackAdapters = stackAdapters;
        this.llmClient = llmClient;
    }

    public void runBuildPhase(ReviewCommand command) {
        Workspace workspace = new Workspace(command.repoRoot());
        StackAdapter stackAdapter = detectStackAdapter(command.repoRoot());
        log.info("detected stack adapter: {}", stackAdapter.id());

        StepResult buildResult = stackAdapter.build(workspace);
        if (!buildResult.success()) {
            throw new BuildFailedException("build failed (exit=" + buildResult.exitCode() + "):\n" + buildResult.output());
        }

        List<Finding> deterministicFindings = stackAdapter.lint(workspace);
        log.info("deterministic findings: {}", deterministicFindings.size());

        FindingsIO.write(command.findingsPath(), deterministicFindings);
    }

    public ReviewReport runReportPhase(ReviewCommand command) {
        ReviewConfig config = configLoader.load(command.configPath());

        rulepackInjector.inject(command.rulepackDir(), command.repoRoot(), config.profiles());
        List<RuleDefinition> activeRules = ruleMerger.merge(command.rulepackDir(), config);
        log.info("active rules after merge: {}", activeRules.size());

        List<Finding> deterministicFindings = FindingsIO.read(command.findingsPath());

        ReviewResult llmResult = llmClient.review(new ReviewRequest(command.repoRoot()));
        List<Finding> llmFindings = FindingsFilter.filter(llmResult.candidates());
        log.info("llm findings after filter: {}", llmFindings.size());

        List<Finding> allFindings = new ArrayList<>(deterministicFindings);
        allFindings.addAll(llmFindings);

        return new ReviewReport(allFindings, config);
    }

    /** 편의 메서드: 로컬 CLI 실행처럼 샌드박스 분리가 필요 없을 때 두 단계를 한 번에 돌린다. */
    public ReviewReport run(ReviewCommand command) {
        runBuildPhase(command);
        return runReportPhase(command);
    }

    private StackAdapter detectStackAdapter(Path repoRoot) {
        return stackAdapters.stream()
                .filter(adapter -> adapter.detect(repoRoot))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no stack adapter detected for " + repoRoot));
    }
}
