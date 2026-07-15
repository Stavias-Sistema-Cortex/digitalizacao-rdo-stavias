package com.projeto.cortex.financeiro.core;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class FinanceValidation {

    private static final Pattern EMAIL = Pattern.compile(
            "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"
    );
    private static final Pattern MUTATION_ID = Pattern.compile(
            "^[A-Za-z0-9][A-Za-z0-9._:-]{0,119}$"
    );

    private FinanceValidation() {
    }

    public static String cnpj(String value) {
        String digits = value == null ? "" : value.replaceAll("\\D", "");
        if (digits.length() != 14
                || digits.chars().distinct().count() == 1
                || checkDigit(digits, 12) != digits.charAt(12) - '0'
                || checkDigit(digits, 13) != digits.charAt(13) - '0') {
            throw badRequest("CNPJ inválido.");
        }
        return digits;
    }

    public static String email(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > 320 || !EMAIL.matcher(normalized).matches()) {
            throw badRequest("E-mail inválido.");
        }
        return normalized;
    }

    public static BigDecimal money(BigDecimal value, String field) {
        if (value == null) {
            throw badRequest(field + " é obrigatório.");
        }
        if (value.scale() > 4 || value.signum() < 0
                || value.precision() - value.scale() > 15) {
            throw badRequest(
                    field + " deve ser não negativo e ter no máximo quatro casas decimais."
            );
        }
        return value;
    }

    public static BigDecimal optionalMoney(BigDecimal value, String field) {
        return value == null ? null : money(value, field);
    }

    public static BigDecimal percentage(BigDecimal value, String field) {
        if (value == null) {
            throw badRequest(field + " é obrigatório.");
        }
        if (value.scale() > 6 || value.signum() <= 0
                || value.compareTo(new BigDecimal("100")) > 0) {
            throw badRequest(
                    field + " deve ser maior que zero, menor ou igual a 100 "
                            + "e ter no máximo seis casas decimais."
            );
        }
        return value;
    }

    public static String currency(String value) {
        String normalized = requiredText(value, "moeda", 3)
                .toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{3}")) {
            throw badRequest("Moeda deve usar um código ISO de três letras.");
        }
        return normalized;
    }

    public static String priority(String value, boolean optional) {
        if (optional && (value == null || value.isBlank())) {
            return null;
        }
        String normalized = requiredText(value, "prioridade", 20)
                .toUpperCase(Locale.ROOT);
        if (!List.of("BAIXA", "MEDIA", "ALTA", "URGENTE")
                .contains(normalized)) {
            throw badRequest("Prioridade inválida.");
        }
        return normalized;
    }

    public static String uuid(String value, String field) {
        try {
            return UUID.fromString(requiredText(value, field, 36)).toString();
        } catch (IllegalArgumentException exception) {
            throw badRequest(field + " inválido.");
        }
    }

    public static String optionalUuid(String value, String field) {
        return value == null || value.isBlank() ? null : uuid(value, field);
    }

    public static String mutationId(String value) {
        String normalized = requiredText(value, "clientMutationId", 120);
        if (!MUTATION_ID.matcher(normalized).matches()) {
            throw badRequest("clientMutationId inválido.");
        }
        return normalized;
    }

    public static String requiredText(
            String value,
            String field,
            int maxLength
    ) {
        if (value == null || value.isBlank()) {
            throw badRequest(field + " é obrigatório.");
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength) {
            throw badRequest(field + " excede o tamanho permitido.");
        }
        return normalized;
    }

    public static String optionalText(String value, String field, int maxLength) {
        return value == null || value.isBlank()
                ? null
                : requiredText(value, field, maxLength);
    }

    public static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static int checkDigit(String digits, int length) {
        int[] weights = length == 12
                ? new int[]{5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2}
                : new int[]{6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        int sum = 0;
        for (int index = 0; index < length; index++) {
            sum += (digits.charAt(index) - '0') * weights[index];
        }
        int remainder = sum % 11;
        return remainder < 2 ? 0 : 11 - remainder;
    }
}
