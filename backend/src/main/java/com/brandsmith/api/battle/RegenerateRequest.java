package com.brandsmith.api.battle;

import jakarta.validation.constraints.Size;

public record RegenerateRequest(@Size(max = 500) String note) {
}
