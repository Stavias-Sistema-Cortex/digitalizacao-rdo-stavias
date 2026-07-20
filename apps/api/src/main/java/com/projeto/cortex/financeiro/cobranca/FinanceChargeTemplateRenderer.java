package com.projeto.cortex.financeiro.cobranca;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict renderer for finance charge templates. */
public final class FinanceChargeTemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile(
            "\\{\\{([A-Za-z][A-Za-z0-9]{0,63})}}"
    );
    private static final int MAX_SUBJECT_LENGTH = 500;
    private static final int MAX_BODY_LENGTH = 100_000;

    public RenderedEmail render(
            String subjectTemplate,
            String textBodyTemplate,
            Set<String> allowedVariables,
            Map<String, String> realValues
    ) {
        String subject = requireTemplate(subjectTemplate, "assunto");
        String body = requireTemplate(textBodyTemplate, "corpo");
        Set<String> allowed = allowedVariables == null
                ? Set.of() : Set.copyOf(allowedVariables);
        Map<String, String> values = realValues == null
                ? Map.of() : Map.copyOf(realValues);

        validateSyntax(subject);
        validateSyntax(body);
        subject = interpolate(subject, allowed, values);
        body = interpolate(body, allowed, values);

        if (subject.isBlank()
                || subject.length() > MAX_SUBJECT_LENGTH
                || subject.contains("\r")
                || subject.contains("\n")) {
            throw new IllegalArgumentException(
                    "O assunto renderizado do e-mail é inválido."
            );
        }
        if (body.length() > MAX_BODY_LENGTH) {
            throw new IllegalArgumentException(
                    "O corpo renderizado do e-mail excede o limite permitido."
            );
        }
        return new RenderedEmail(subject, body, sha256(subject, body));
    }

    private String interpolate(
            String template,
            Set<String> allowed,
            Map<String, String> values
    ) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException(
                        "Variável de template não permitida: '" + name + "'."
                );
            }
            String value = values.get(name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                        "Não há valor real disponível para a variável '"
                                + name + "'."
                );
            }
            matcher.appendReplacement(output, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private void validateSyntax(String template) {
        String withoutValidPlaceholders = PLACEHOLDER.matcher(template)
                .replaceAll("");
        if (withoutValidPlaceholders.contains("{{")
                || withoutValidPlaceholders.contains("}}")) {
            throw new IllegalArgumentException(
                    "A sintaxe do template de e-mail é inválida."
            );
        }
    }

    private String requireTemplate(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "O template de " + field + " é obrigatório."
            );
        }
        return value;
    }

    private String sha256(String subject, String body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(
                    (subject + '\0' + body).getBytes(StandardCharsets.UTF_8)
            );
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponível.");
        }
    }

    public record RenderedEmail(
            String subject,
            String textBody,
            String previewHash
    ) {
    }
}
