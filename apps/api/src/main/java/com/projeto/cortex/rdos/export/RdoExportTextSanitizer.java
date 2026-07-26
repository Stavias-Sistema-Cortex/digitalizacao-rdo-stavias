package com.projeto.cortex.rdos.export;

import java.text.Normalizer;
import java.util.regex.Pattern;

final class RdoExportTextSanitizer {

    private static final Pattern EMAIL = Pattern.compile(
            "(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b"
    );
    private static final Pattern CPF = Pattern.compile(
            "(?<!\\d)\\d{3}\\.?\\d{3}\\.?\\d{3}-?\\d{2}(?!\\d)"
    );
    private static final Pattern PRIVATE_KEY_BLOCK = Pattern.compile(
            "(?is)-----BEGIN[ \\t]+("
                    + "(?:[A-Z0-9]+(?:[ \\t]+[A-Z0-9]+){0,7}[ \\t]+)?"
                    + "PRIVATE KEY)-----"
                    + ".*?-----END[ \\t]+\\1-----"
    );
    private static final Pattern UNBOUNDED_PRIVATE_KEY = Pattern.compile(
            "(?is)-----BEGIN[ \\t]+"
                    + "(?:[A-Z0-9]+(?:[ \\t]+[A-Z0-9]+){0,7}[ \\t]+)?"
                    + "PRIVATE KEY-----.*"
    );
    private static final Pattern PRIVATE_KEY_MARKER = Pattern.compile(
            "(?i)-{2,}\\s*(?:BEGIN|END)(?: [A-Z0-9]+)* PRIVATE KEY\\s*-{2,}"
    );
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)\\b(?:api[_-]?key|secret|token|password|senha|chave|"
                    + "aws_access_key_id|aws_secret_access_key)\\s*[:=]\\s*"
                    + "(?:\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|[^\\s,;]+)"
    );
    private static final Pattern BEARER_TOKEN = Pattern.compile(
            "(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{8,}"
    );
    private static final Pattern AWS_ACCESS_KEY = Pattern.compile(
            "(?<![A-Z0-9])(?:AKIA|ASIA)[A-Z0-9]{16}(?![A-Z0-9])"
    );
    private static final Pattern DISALLOWED_CONTROL = Pattern.compile(
            "[\\p{Cc}&&[^\\n\\r\\t]]"
    );
    private static final Pattern SAFE_FILENAME_CHARACTER = Pattern.compile(
            "[^A-Za-z0-9._-]+"
    );
    private static final Pattern REPEATED_SEPARATOR = Pattern.compile("-{2,}");

    String cellText(String value) {
        if (value == null) {
            return "";
        }

        String sanitized = PRIVATE_KEY_BLOCK.matcher(value)
                .replaceAll("[bloco de chave privada removido]");
        sanitized = UNBOUNDED_PRIVATE_KEY.matcher(sanitized)
                .replaceAll("[bloco de chave privada inválido removido]");
        sanitized = DISALLOWED_CONTROL.matcher(sanitized).replaceAll("");
        sanitized = EMAIL.matcher(sanitized).replaceAll("[email removido]");
        sanitized = CPF.matcher(sanitized).replaceAll("[CPF removido]");
        sanitized = PRIVATE_KEY_MARKER.matcher(sanitized)
                .replaceAll("[chave privada removida]");
        sanitized = BEARER_TOKEN.matcher(sanitized)
                .replaceAll("Bearer [segredo removido]");
        sanitized = AWS_ACCESS_KEY.matcher(sanitized)
                .replaceAll("[credencial AWS removida]");
        sanitized = SECRET_ASSIGNMENT.matcher(sanitized)
                .replaceAll("[segredo removido]");

        int firstVisible = firstNonWhitespace(sanitized);
        if (firstVisible >= 0 && isFormulaPrefix(sanitized.charAt(firstVisible))) {
            return "'" + sanitized;
        }
        return sanitized;
    }

    String filename(String numeroRdo, String rdoId) {
        return filename(numeroRdo, rdoId, ".xlsx");
    }

    String filename(String numeroRdo, String rdoId, String extension) {
        if (!".xlsx".equals(extension) && !".pdf".equals(extension)) {
            throw new IllegalArgumentException(
                    "Extensão de exportação não permitida."
            );
        }
        return "rdo-" + safeBasename(numeroRdo, rdoId) + extension;
    }

    private String safeBasename(String numeroRdo, String rdoId) {
        String candidate = firstNonBlank(numeroRdo, rdoId, "rdo");
        candidate = Normalizer.normalize(candidate, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "");
        candidate = SAFE_FILENAME_CHARACTER.matcher(candidate).replaceAll("-");
        candidate = REPEATED_SEPARATOR.matcher(candidate).replaceAll("-");
        candidate = candidate.replaceAll("^[.\\-_]+|[.\\-_]+$", "");
        if (candidate.isBlank()) {
            candidate = "rdo";
        }
        if (candidate.length() > 64) {
            candidate = candidate.substring(0, 64);
        }
        return candidate;
    }

    private int firstNonWhitespace(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isWhitespace(value.charAt(index))) {
                return index;
            }
        }
        return -1;
    }

    private boolean isFormulaPrefix(char value) {
        return value == '=' || value == '+' || value == '-' || value == '@';
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "rdo";
    }
}
