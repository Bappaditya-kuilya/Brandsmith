package com.brandsmith.api.stage;

import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.brandsmith.api.budget.BudgetGuard;
import com.brandsmith.api.llm.LlmClient;
import com.brandsmith.api.llm.LlmUnavailableException;
import com.brandsmith.api.prompt.PromptLoader;
import com.brandsmith.api.prompt.PromptLoader.Prompt;

@Component
public class S0IntakeService {

    public static final String REFUSAL_MESSAGE =
            "We can't help with that idea. Please share a different, legitimate brand idea.";

    private static final Logger log = LoggerFactory.getLogger(S0IntakeService.class);

    private static final List<Pattern> RULES = List.of(
            Pattern.compile("kill all", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bhow to (make|build) a bomb\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bmake a bomb\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bmeth\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bheroin\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bfentanyl\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bcounterfeit\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bstolen credit cards?\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bsteal credit cards?\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bphishing\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\brun a scam\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bponzi\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bpyramid scheme\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bchild porn", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bcsam\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bsex trafficking\\b", Pattern.CASE_INSENSITIVE));

    private final LlmClient llm;
    private final PromptLoader prompts;
    private final StageRunner runner;
    private final BudgetGuard budget;
    private final String smallModel;
    private final double defaultCap;

    public S0IntakeService(LlmClient llm,
                           PromptLoader prompts,
                           StageRunner runner,
                           BudgetGuard budget,
                           @Value("${brandsmith.llm.small-model}") String smallModel,
                           @Value("${brandsmith.budget.default-cap:0.40}") double defaultCap) {
        this.llm = llm;
        this.prompts = prompts;
        this.runner = runner;
        this.budget = budget;
        this.smallModel = smallModel;
        this.defaultCap = defaultCap;
    }

    public IntakeResult run(String idea, double spentUsd, double capUsd) {
        if (rulesHit(idea) != null) {
            return rulesOnly(new S0Output(idea.strip(), "unknown", true, "harmful or disallowed content"), false);
        }
        if (!llm.available()) {
            log.warn("S0 degraded: LLM not configured, rules-only moderation");
            return rulesOnly(new S0Output(idea.strip(), "unknown", false, null), true);
        }
        budget.ensureWithinCap(spentUsd, capUsd);
        Prompt prompt = prompts.load("s0-intake");
        String user = "<data idea>\n" + idea + "\n</data>";
        StageResult<S0Output> result;
        try {
            result = runner.run(llm, prompt, user, smallModel, S0Output.class);
        } catch (LlmUnavailableException e) {
            log.warn("S0 degraded: LLM call failed ({})", e.getMessage());
            return rulesOnly(new S0Output(idea.strip(), "unknown", false, null), true);
        }
        S0Output output = result.value();
        boolean degraded = result.degraded();
        if (output == null) {
            degraded = true;
            output = new S0Output(idea.strip(), "unknown", false, null);
        }
        double cost = budget.cost(result.model(), result.tokensIn(), result.tokensOut());
        return new IntakeResult(output, degraded, result.model(), result.promptVersion(), result.rawResponse(),
                result.tokensIn(), result.tokensOut(), result.latencyMs(), cost);
    }

    public static String rulesHit(String idea) {
        String lower = idea.toLowerCase();
        for (Pattern rule : RULES) {
            if (rule.matcher(lower).find()) {
                return rule.pattern();
            }
        }
        return null;
    }

    private IntakeResult rulesOnly(S0Output output, boolean degraded) {
        return new IntakeResult(output, degraded, "rules", null, null, 0, 0, 0, 0);
    }

    public record IntakeResult(S0Output output,
                               boolean degraded,
                               String model,
                               String promptVersion,
                               String rawResponse,
                               int tokensIn,
                               int tokensOut,
                               long latencyMs,
                               double costUsd) {
    }
}
