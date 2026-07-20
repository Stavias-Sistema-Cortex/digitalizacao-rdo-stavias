package com.projeto.cortex.auth.identity;

/** Normalizes and validates a CPF without retaining or echoing its raw value. */
public final class CpfNormalizer {

    private CpfNormalizer() {
    }

    public static String requireValid(String raw) {
        String digits = raw == null ? "" : raw.replaceAll("\\D", "");
        if (digits.length() != 11 || digits.chars().distinct().count() == 1) {
            throw invalidIdentifier();
        }

        int first = checkDigit(digits, 9, 10);
        int second = checkDigit(digits, 10, 11);
        if (digits.charAt(9) - '0' != first
                || digits.charAt(10) - '0' != second) {
            throw invalidIdentifier();
        }
        return digits;
    }

    private static int checkDigit(String digits, int length, int weight) {
        int sum = 0;
        for (int index = 0; index < length; index++) {
            sum += (digits.charAt(index) - '0') * (weight - index);
        }
        int value = 11 - (sum % 11);
        return value >= 10 ? 0 : value;
    }

    private static IllegalArgumentException invalidIdentifier() {
        return new IllegalArgumentException("Identificador inválido.");
    }
}
