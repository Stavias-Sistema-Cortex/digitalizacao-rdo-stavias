package com.projeto.cortex.financeiro.invoice;

import com.projeto.cortex.financeiro.core.FinanceValidation;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;

public final class FinanceInvoiceValidation {

    private FinanceInvoiceValidation() {
    }

    public static BigDecimal invoiceTotal(
            BigDecimal gross,
            BigDecimal discount,
            BigDecimal addition,
            BigDecimal withholding,
            BigDecimal declaredNet
    ) {
        BigDecimal normalizedGross = FinanceValidation.money(
                gross, "valorBruto"
        );
        BigDecimal normalizedDiscount = optionalMoney(
                discount, "desconto"
        );
        BigDecimal normalizedAddition = optionalMoney(
                addition, "acrescimo"
        );
        BigDecimal normalizedWithholding = optionalMoney(
                withholding, "retencoes"
        );
        BigDecimal normalizedNet = FinanceValidation.money(
                declaredNet, "valorLiquido"
        );
        BigDecimal expected = normalizedGross
                .subtract(normalizedDiscount)
                .add(normalizedAddition)
                .subtract(normalizedWithholding);
        if (expected.signum() < 0 || expected.compareTo(normalizedNet) != 0) {
            throw FinanceValidation.badRequest(
                    "O valor líquido não corresponde ao valor bruto, descontos, acréscimos e retenções."
            );
        }
        return normalizedNet;
    }

    public static BigDecimal ledgerTotal(
            BigDecimal original,
            BigDecimal discount,
            BigDecimal interest,
            BigDecimal fine,
            BigDecimal declaredNet
    ) {
        BigDecimal normalizedOriginal = FinanceValidation.money(
                original, "valorOriginal"
        );
        BigDecimal normalizedDiscount = optionalMoney(
                discount, "desconto"
        );
        BigDecimal normalizedInterest = optionalMoney(
                interest, "juros"
        );
        BigDecimal normalizedFine = optionalMoney(fine, "multa");
        BigDecimal normalizedNet = FinanceValidation.money(
                declaredNet, "valorLiquido"
        );
        BigDecimal expected = normalizedOriginal
                .subtract(normalizedDiscount)
                .add(normalizedInterest)
                .add(normalizedFine);
        if (expected.signum() < 0 || expected.compareTo(normalizedNet) != 0) {
            throw FinanceValidation.badRequest(
                    "O valor líquido do lançamento não corresponde aos componentes informados."
            );
        }
        return normalizedNet;
    }

    public static void requireExactAllocation(
            BigDecimal expected,
            Collection<BigDecimal> values,
            String label
    ) {
        BigDecimal normalizedExpected = FinanceValidation.money(
                expected, "valor esperado"
        );
        BigDecimal total = BigDecimal.ZERO;
        if (values != null) {
            int index = 0;
            for (BigDecimal value : values) {
                total = total.add(FinanceValidation.money(
                        value, label + "[" + index + "]"
                ));
                index++;
            }
        }
        if (total.compareTo(normalizedExpected) != 0) {
            throw FinanceValidation.badRequest(
                    "A soma de " + label + " deve ser exatamente igual ao valor informado."
            );
        }
    }

    public static String accessKey(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (!normalized.matches("[0-9]{44}")) {
            throw FinanceValidation.badRequest(
                    "A chave de acesso deve conter exatamente 44 dígitos."
            );
        }
        return normalized;
    }

    public static String enumValue(
            String value,
            String field,
            Set<String> allowed
    ) {
        String normalized = FinanceValidation.requiredText(value, field, 40)
                .toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw FinanceValidation.badRequest(field + " inválido.");
        }
        return normalized;
    }

    public static BigDecimal zeroIfNull(BigDecimal value, String field) {
        return optionalMoney(value, field);
    }

    private static BigDecimal optionalMoney(BigDecimal value, String field) {
        return value == null
                ? BigDecimal.ZERO
                : FinanceValidation.money(value, field);
    }
}
