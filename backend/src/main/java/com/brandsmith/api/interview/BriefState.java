package com.brandsmith.api.interview;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

public class BriefState {

    public static final double OVERALL_CONFIDENCE_THRESHOLD = 0.75;
    public static final int MAX_QUESTIONS = 6;
    public static final double ANSWER_CONFIDENCE = 0.7;
    public static final double SKIP_CONFIDENCE = 0.1;
    public static final double USER_EDIT_CONFIDENCE = 0.9;

    private String idea;
    @JsonProperty("clean_idea")
    private String cleanIdea;
    @JsonProperty("product_type")
    private String productType;
    private Map<String, BriefField> fields = new LinkedHashMap<>();
    @JsonProperty("question_count")
    private int questionCount;
    @JsonProperty("current_field")
    private String currentField;
    private List<FieldAnswer> answers = new ArrayList<>();

    public BriefState() {
        ensureFields();
    }

    public void ensureFields() {
        if (fields == null) {
            fields = new LinkedHashMap<>();
        }
        for (BriefFieldId id : BriefFieldId.values()) {
            fields.putIfAbsent(id.id(), new BriefField(null, 0.0, null, false));
        }
        if (answers == null) {
            answers = new ArrayList<>();
        }
    }

    public double overallConfidence() {
        double weightedSum = 0.0;
        double weightSum = 0.0;
        for (BriefFieldId id : BriefFieldId.values()) {
            double weight = id.weight();
            weightSum += weight;
            weightedSum += weight * field(id).confidence();
        }
        return weightedSum / weightSum;
    }

    public Optional<String> selectNext() {
        String bestField = null;
        double bestScore = -1.0;
        for (BriefFieldId id : BriefFieldId.values()) {
            BriefField field = field(id);
            boolean unanswered = field.value() == null || field.value().isBlank();
            if (!unanswered || field.assumption()) {
                continue;
            }
            double score = id.weight() * (1.0 - field.confidence());
            if (score > bestScore) {
                bestScore = score;
                bestField = id.id();
            }
        }
        return Optional.ofNullable(bestField);
    }

    @JsonIgnore
    public boolean isDone() {
        if (overallConfidence() >= OVERALL_CONFIDENCE_THRESHOLD) {
            return true;
        }
        if (questionCount >= MAX_QUESTIONS) {
            return true;
        }
        return selectNext().isEmpty();
    }

    public void applyAnswer(String answer) {
        String fieldId = currentField;
        fields.put(fieldId, new BriefField(answer, ANSWER_CONFIDENCE, answer, false));
        answers.add(new FieldAnswer(fieldId, answer));
        currentField = null;
    }

    public void applySkip() {
        String fieldId = currentField;
        fields.put(fieldId, new BriefField(null, SKIP_CONFIDENCE, null, true));
        answers.add(new FieldAnswer(fieldId, "[skipped]"));
        currentField = null;
    }

    public void startQuestion(String fieldId) {
        this.currentField = fieldId;
        this.questionCount++;
    }

    public void applyUserEdit(String fieldId, String value) {
        BriefFieldId id = BriefFieldId.of(fieldId);
        BriefField previous = field(id);
        double confidence = Math.max(previous.confidence(), USER_EDIT_CONFIDENCE);
        fields.put(fieldId, new BriefField(value, confidence, value, false));
    }

    public BriefField field(BriefFieldId id) {
        BriefField field = fields.get(id.id());
        return field == null ? new BriefField(null, 0.0, null, false) : field;
    }

    public List<FieldAnswer> lastAnswers(int n) {
        int from = Math.max(0, answers.size() - n);
        return List.copyOf(answers.subList(from, answers.size()));
    }

    public String getIdea() {
        return idea;
    }

    public void setIdea(String idea) {
        this.idea = idea;
    }

    public String getCleanIdea() {
        return cleanIdea;
    }

    public void setCleanIdea(String cleanIdea) {
        this.cleanIdea = cleanIdea;
    }

    public String getProductType() {
        return productType;
    }

    public void setProductType(String productType) {
        this.productType = productType;
    }

    public Map<String, BriefField> getFields() {
        return fields;
    }

    public void setFields(Map<String, BriefField> fields) {
        this.fields = fields;
    }

    public int getQuestionCount() {
        return questionCount;
    }

    public void setQuestionCount(int questionCount) {
        this.questionCount = questionCount;
    }

    public String getCurrentField() {
        return currentField;
    }

    public void setCurrentField(String currentField) {
        this.currentField = currentField;
    }

    public List<FieldAnswer> getAnswers() {
        return answers;
    }

    public void setAnswers(List<FieldAnswer> answers) {
        this.answers = answers;
    }
}
