package com.brandsmith.api.session;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateSessionRequest(@NotBlank(message = "Idea is required")
                                   @Size(min = 10, max = 1000, message = "Idea must be 10 to 1000 characters")
                                   String idea) {
}
