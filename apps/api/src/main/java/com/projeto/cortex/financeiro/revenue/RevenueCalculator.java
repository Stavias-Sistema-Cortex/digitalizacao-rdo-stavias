package com.projeto.cortex.financeiro.revenue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Objects;

public final class RevenueCalculator {

    public boolean isAccepted(
            String validationStatus,
            boolean rework,
            boolean productionRejected
    ) {
        return "VALIDADA".equals(normalize(validationStatus))
                && !rework
                && !productionRejected;
    }

    public BigDecimal calculate(
            String validationStatus,
            boolean rework,
            boolean productionRejected,
            BigDecimal acceptedQuantity,
            BigDecimal unitPriceSnapshot
    ) {
        requireNonNegative(acceptedQuantity, "acceptedQuantity");
        requireNonNegative(unitPriceSnapshot, "unitPriceSnapshot");
        if (!isAccepted(validationStatus, rework, productionRejected)) {
            return BigDecimal.ZERO.setScale(2);
        }
        return acceptedQuantity.multiply(unitPriceSnapshot)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static void requireNonNegative(BigDecimal value, String field) {
        Objects.requireNonNull(value, field + " is required");
        if (value.signum() < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
    }
}
