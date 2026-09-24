package com.brandsmith.api.battle;

import java.util.Map;

public record SelectRequest(Integer index, Map<String, Object> edits) {
}
