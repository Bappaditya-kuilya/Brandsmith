package com.brandsmith.api.visual;

import java.util.LinkedHashMap;
import java.util.Map;

public record VisualBoard(BrandVisual visual, VisualDirection direction) {

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("palette", visual.palette());
        map.put("fonts", visual.fonts());
        map.put("shape", visual.shape());
        map.put("logoSvg", visual.logoSvg());
        map.put("direction", direction);
        return map;
    }
}
