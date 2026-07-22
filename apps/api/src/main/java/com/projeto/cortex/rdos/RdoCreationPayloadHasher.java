package com.projeto.cortex.rdos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

final class RdoCreationPayloadHasher {

    private final ObjectMapper objectMapper;

    RdoCreationPayloadHasher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String hash(RdoCreateRequest request) {
        String canonical = canonicalJson(objectMapper.valueToTree(request));
        return HexFormat.of().formatHex(
                sha256().digest(canonical.getBytes(StandardCharsets.UTF_8))
        );
    }

    private String canonicalJson(JsonNode value) {
        if (value == null || value.isNull()) {
            return "null";
        }
        if (value.isArray()) {
            List<String> items = new ArrayList<>();
            value.forEach(item -> items.add(canonicalJson(item)));
            return "[" + String.join(",", items) + "]";
        }
        if (value.isObject()) {
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            List<String> entries = new ArrayList<>();
            for (String name : names) {
                entries.add(jsonString(name) + ":" + canonicalJson(value.get(name)));
            }
            return "{" + String.join(",", entries) + "}";
        }
        if (value.isNumber()) {
            return canonicalNumber(value.decimalValue());
        }
        return json(value);
    }

    private String canonicalNumber(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.signum() == 0 ? "0" : normalized.toPlainString();
    }

    private String jsonString(String value) {
        return json(objectMapper.getNodeFactory().textNode(value));
    }

    private String json(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Não foi possível canonicalizar o RDO.", exception);
        }
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponível.", exception);
        }
    }
}
