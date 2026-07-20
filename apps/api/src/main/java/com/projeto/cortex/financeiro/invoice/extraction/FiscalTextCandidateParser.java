package com.projeto.cortex.financeiro.invoice.extraction;

import static com.projeto.cortex.financeiro.invoice.extraction.FiscalExtractionDtos.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class FiscalTextCandidateParser {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE
            | Pattern.UNICODE_CASE;
    private static final Pattern NUMBER = Pattern.compile(
            "\\bNumero\\s*[:#-]?\\s*([A-Za-z0-9.-]+)", FLAGS);
    private static final Pattern SERIES = Pattern.compile(
            "\\bSerie\\s*[:#-]?\\s*([A-Za-z0-9.-]+)", FLAGS);
    private static final Pattern ISSUED_AT = Pattern.compile(
            "\\bEmissao\\s*[:#-]?\\s*(\\d{2}/\\d{2}/\\d{4})", FLAGS);
    private static final Pattern CNPJ = Pattern.compile(
            "\\bCNPJ\\s*[:#-]?\\s*([0-9./-]{14,18})", FLAGS);

    private FiscalTextCandidateParser() {
    }

    static FiscalExtractionResult parse(
            String format,
            String extractor,
            String extractorVersion,
            String text,
            List<String> locations,
            BigDecimal confidence
    ) {
        String normalizedText = normalize(text);
        String location = locations == null || locations.isEmpty()
                ? "texto"
                : locations.getFirst();
        Map<String, ParsedValue> values = new LinkedHashMap<>();

        putText(values, "numero", match(NUMBER, normalizedText));
        putText(values, "serie", match(SERIES, normalizedText));
        putText(values, "dataEmissao", parseDate(match(ISSUED_AT, normalizedText)));
        String cnpj = match(CNPJ, normalizedText);
        putText(values, "fornecedorCnpj",
                cnpj == null ? null : cnpj.replaceAll("\\D", ""));

        BigDecimal gross = money(normalizedText, "Valor\\s+bruto");
        BigDecimal discount = money(normalizedText, "Desconto");
        BigDecimal addition = money(normalizedText, "Acrescimo");
        BigDecimal retention = money(normalizedText, "Retencoes?");
        BigDecimal net = money(normalizedText, "Valor\\s+(?:total|liquido)");
        if (gross != null && net != null) {
            discount = discount == null ? BigDecimal.ZERO : discount;
            addition = addition == null ? BigDecimal.ZERO : addition;
            retention = retention == null ? BigDecimal.ZERO : retention;
        }
        putDecimal(values, "valorBruto", gross);
        putDecimal(values, "desconto", discount);
        putDecimal(values, "acrescimo", addition);
        putDecimal(values, "retencoes", retention);
        putDecimal(values, "valorLiquido", net);

        List<String> warnings = new ArrayList<>();
        warnings.add("REVISAO_HUMANA_OBRIGATORIA");
        CandidateValidation totalValidation = CandidateValidation.NAO_VERIFICADO;
        if (gross != null && net != null) {
            BigDecimal calculated = gross.subtract(discount)
                    .add(addition).subtract(retention);
            if (calculated.compareTo(net) != 0) {
                totalValidation = CandidateValidation.DIVERGENTE;
                warnings.add("TOTAL_DIVERGENTE");
            }
        }

        List<FiscalCandidate> candidates = new ArrayList<>();
        for (Map.Entry<String, ParsedValue> entry : values.entrySet()) {
            CandidateValidation validation = "valorLiquido".equals(entry.getKey())
                    ? totalValidation
                    : CandidateValidation.NAO_VERIFICADO;
            candidates.add(new FiscalCandidate(
                    entry.getKey(), entry.getValue().normalized(),
                    entry.getValue().original(), extractor, extractorVersion,
                    location, confidence, validation,
                    validation == CandidateValidation.DIVERGENTE
                            ? "TOTAL_DIVERGENTE" : null));
        }
        if (candidates.isEmpty()) warnings.add("CAMPOS_FISCAIS_NAO_IDENTIFICADOS");

        return new FiscalExtractionResult(
                format,
                ExtractionStatus.REVISAO_NECESSARIA,
                extractor,
                extractorVersion,
                candidates,
                warnings,
                "NAO_VERIFICADA");
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }

    private static String match(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).strip() : null;
    }

    private static BigDecimal money(String text, String label) {
        Pattern pattern = Pattern.compile(
                "\\b" + label + "\\s*[:#-]?\\s*(?:R\\$\\s*)?"
                        + "([0-9.]+(?:,[0-9]{1,4})?)",
                FLAGS);
        String value = match(pattern, text);
        if (value == null) return null;
        try {
            if (value.contains(",")) {
                value = value.replace(".", "").replace(',', '.');
            }
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String parseDate(String value) {
        if (value == null) return null;
        try {
            return LocalDate.parse(
                    value,
                    DateTimeFormatter.ofPattern("dd/MM/uuuu")
            ).toString();
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static void putText(
            Map<String, ParsedValue> values,
            String field,
            String value
    ) {
        if (value == null || value.isBlank()) return;
        values.put(field, new ParsedValue(TextNode.valueOf(value), value));
    }

    private static void putDecimal(
            Map<String, ParsedValue> values,
            String field,
            BigDecimal value
    ) {
        if (value == null) return;
        values.put(field, new ParsedValue(
                DecimalNode.valueOf(value), value.toPlainString()));
    }

    private record ParsedValue(JsonNode normalized, String original) {
    }
}
