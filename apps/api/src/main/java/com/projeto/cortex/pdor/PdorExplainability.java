package com.projeto.cortex.pdor;

import com.fasterxml.jackson.databind.JsonNode;

record PdorExplainability(
        JsonNode analysisScope,
        JsonNode temporalWindow,
        JsonNode featuresUsed,
        JsonNode missingData,
        JsonNode limitations,
        JsonNode alerts,
        JsonNode recommendations,
        JsonNode previousComparison,
        JsonNode evidence
) {
}
