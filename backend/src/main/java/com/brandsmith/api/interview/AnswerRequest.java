package com.brandsmith.api.interview;

import jakarta.validation.constraints.Size;

public record AnswerRequest(@Size(max = 4000) String answer, Boolean skip) {
}
