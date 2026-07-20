package com.projeto.cortex.pdor;

import java.util.LinkedHashMap;
import java.util.Map;

public record PdorInputOrigin(
        String field,
        String label,
        PdorDataAvailability availability,
        Object value,
        String source,
        String detail,
        boolean required
) {
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("field", field);
        map.put("label", label);
        map.put("availability", availability.name());
        map.put("availabilityLabel", availability.label());
        map.put("value", value);
        map.put("source", source);
        map.put("detail", detail);
        map.put("required", required);
        return map;
    }
}
