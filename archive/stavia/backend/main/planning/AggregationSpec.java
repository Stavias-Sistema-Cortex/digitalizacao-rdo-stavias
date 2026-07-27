package com.projeto.cortex.intelligence.stavia.planning;

public record AggregationSpec(
        String function,
        String attribute,
        String groupBy
) {

    public AggregationSpec {
        function = normalize(function);
        attribute = normalize(attribute);
        groupBy = normalize(groupBy);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim();
    }
}
