package com.brandsmith.api.interview;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.brandsmith.api.budget.BudgetExceededException;
import com.brandsmith.api.budget.BudgetGuard;
import com.brandsmith.api.llm.LlmClient;
import com.brandsmith.api.llm.LlmUnavailableException;
import com.brandsmith.api.prompt.PromptLoader;
import com.brandsmith.api.session.SessionService;
import com.brandsmith.api.stage.StageResult;
import com.brandsmith.api.stage.StageRunRecorder;
import com.brandsmith.api.stage.StageRunner;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@Service
public class S1InterviewService {

    private static final Logger log = LoggerFactory.getLogger(S1InterviewService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final SessionService sessions;
    private final LlmClient llm;
    private final PromptLoader prompts;
    private final StageRunner runner;
    private final BudgetGuard budget;
    private final ObjectMapper mapper;
    private final StageRunRecorder recorder;
    private final String mainModel;

    public S1InterviewService(SessionService sessions,
                              LlmClient llm,
                              PromptLoader prompts,
                              StageRunner runner,
                              BudgetGuard budget,
                              ObjectMapper mapper,
                              StageRunRecorder recorder,
                              @Value("${brandsmith.llm.main-model}") String mainModel) {
        this.sessions = sessions;
        this.llm = llm;
        this.prompts = prompts;
        this.runner = runner;
        this.budget = budget;
        this.mapper = mapper;
        this.recorder = recorder;
        this.mainModel = mainModel;
    }

    public InterviewResponse submit(UUID id, String token, AnswerRequest request) {
        SessionService.BriefSnapshot snapshot = sessions.loadBrief(id, token);
        BriefState brief = mapper.convertValue(snapshot.brief(), BriefState.class);
        brief.ensureFields();

        boolean hasAnswer = request.answer() != null && !request.answer().isBlank();
        boolean hasSkip = Boolean.TRUE.equals(request.skip());
        if (brief.getCurrentField() != null) {
            if (hasAnswer) {
                brief.applyAnswer(request.answer().strip());
            } else if (hasSkip) {
                brief.applySkip();
            } else {
                throw new ResponseStatusException(BAD_REQUEST, "Answer or skip is required");
            }
        } else if (hasAnswer || hasSkip) {
            throw new ResponseStatusException(BAD_REQUEST, "No pending question");
        }

        String fieldId = brief.isDone() ? null : brief.selectNext().orElse(null);
        if (fieldId == null) {
            sessions.saveBrief(id, toMap(brief), 0);
            return new InterviewResponse(toMap(brief), null, null,
                    brief.overallConfidence(), brief.getQuestionCount(), true);
        }

        Generated generated;
        try {
            generated = generateQuestion(brief, BriefFieldId.of(fieldId),
                    snapshot.spentUsd(), snapshot.capUsd());
        } catch (BudgetExceededException e) {
            sessions.saveBrief(id, toMap(brief), 0);
            throw e;
        }
        brief.startQuestion(fieldId);
        sessions.saveBrief(id, toMap(brief), generated.costUsd());
        StageRunRecorder.Run stageRun = recorder.start(id, "S1", stageInput(request, fieldId));
        recorder.run(stageRun, generated.degraded() ? "degraded" : "ok",
                Map.of("question", generated.question()), generated.rawResponse(), generated.model(),
                generated.promptVersion(), generated.latencyMs(), generated.tokensIn(), generated.tokensOut());
        return new InterviewResponse(toMap(brief), generated.question(), fieldId,
                brief.overallConfidence(), brief.getQuestionCount(), false);
    }

    public Map<String, Object> patch(UUID id, String token, PatchBriefRequest request) {
        SessionService.BriefSnapshot snapshot = sessions.loadBrief(id, token);
        BriefState brief = mapper.convertValue(snapshot.brief(), BriefState.class);
        brief.ensureFields();
        for (Map.Entry<String, PatchBriefRequest.FieldPatch> entry : request.fields().entrySet()) {
            String fieldId = entry.getKey();
            BriefFieldId.find(fieldId).orElseThrow(() ->
                    new ResponseStatusException(BAD_REQUEST, "Unknown brief field: " + fieldId));
            brief.applyUserEdit(fieldId, entry.getValue().value().strip());
        }
        sessions.saveBrief(id, toMap(brief), 0);
        return toMap(brief);
    }

    private Generated generateQuestion(BriefState brief, BriefFieldId field, double spentUsd, double capUsd) {
        if (!llm.available()) {
            return templateGenerated(field);
        }
        budget.ensureWithinCap(spentUsd, capUsd);
        try {
            StageResult<S1Question> result = runner.run(llm, prompts.load("s1-interview"),
                    userMessage(brief, field), mainModel, S1Question.class);
            double cost = budget.cost(result.model(), result.tokensIn(), result.tokensOut());
            if (result.value() != null && result.value().question() != null
                    && !result.value().question().isBlank()) {
                return new Generated(result.value().question().strip(), cost, result.model(),
                        result.promptVersion(), result.rawResponse(), result.tokensIn(),
                        result.tokensOut(), result.latencyMs(), false);
            }
            log.warn("S1 question degraded to template for field {}", field.id());
            return new Generated(templateQuestion(field), cost, result.model(), result.promptVersion(),
                    result.rawResponse(), result.tokensIn(), result.tokensOut(), result.latencyMs(), true);
        } catch (LlmUnavailableException e) {
            log.warn("S1 question degraded to template: {}", e.getMessage());
            return templateGenerated(field);
        }
    }

    private Generated templateGenerated(BriefFieldId field) {
        return new Generated(templateQuestion(field), 0, "template", null, null, 0, 0, 0, true);
    }

    private Map<String, Object> stageInput(AnswerRequest request, String fieldId) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("fieldId", fieldId);
        if (request.answer() != null && !request.answer().isBlank()) {
            input.put("answer", request.answer().strip());
        }
        if (Boolean.TRUE.equals(request.skip())) {
            input.put("skip", true);
        }
        return input;
    }

    private String userMessage(BriefState brief, BriefFieldId field) {
        StringBuilder sb = new StringBuilder();
        sb.append("<idea>").append(brief.getIdea() == null ? "" : brief.getIdea()).append("</idea>\n");
        sb.append("<field id=\"").append(field.id())
                .append("\" weight=\"").append(field.weight()).append("\">")
                .append(field.label()).append("</field>\n");
        BriefField current = brief.field(field);
        sb.append("<evidence>").append(current.evidence() == null ? "" : current.evidence())
                .append("</evidence>\n");
        sb.append("<data last_answers>\n");
        for (FieldAnswer answer : brief.lastAnswers(3)) {
            sb.append(answer.field()).append(": ").append(answer.answer()).append("\n");
        }
        sb.append("</data>");
        return sb.toString();
    }

    private String templateQuestion(BriefFieldId field) {
        return switch (field) {
            case TARGET_USER -> "Think of the last person who nearly needed this. What were they doing that day, and what did they do instead?";
            case PROBLEM_ALTERNATIVE -> "Describe the last time this problem showed up. What workaround did they reach for in that moment?";
            case DESIRED_OUTCOME -> "Think of the last time this worked for someone like them. What became possible right after?";
            case CATEGORY_COMPETITORS -> "What did they try or pay for just before this, and what frustrated them about it?";
            case FOUNDER_GOAL -> "Recall the last time you explained this idea to a friend. What did you hope it would change for you in a year?";
            case CONSTRAINTS -> "Walk through last week: when would this actually fit, and what budget or time limit is real right now?";
            case TONE_HINTS -> "Think of a brand message you scrolled past recently. What exactly turned you off?";
            case PROOF_ADVANTAGE -> "When did you last solve a piece of this for someone? What happened, in specifics?";
        };
    }

    private Map<String, Object> toMap(BriefState brief) {
        return mapper.convertValue(brief, MAP_TYPE);
    }

    private record Generated(String question,
                             double costUsd,
                             String model,
                             String promptVersion,
                             String rawResponse,
                             int tokensIn,
                             int tokensOut,
                             long latencyMs,
                             boolean degraded) {
    }

    public record InterviewResponse(Map<String, Object> briefState,
                                    String nextQuestion,
                                    @JsonInclude(JsonInclude.Include.NON_NULL) String fieldId,
                                    double overallConfidence,
                                    int questionCount,
                                    boolean done) {
    }
}
