package com.projeto.cortex.obras.trecho;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normaliza a marcação quilométrica textual usada pelo domínio.
 *
 * <p>As colunas de km são {@code varchar} em toda a base ({@code rdo},
 * {@code rdo_controle_geometrico}, {@code programacao_operacional}) e recebem o
 * valor exatamente como o campo digitou ou como a planilha importada trouxe.
 * Formatos observados: {@code "309.04"}, {@code "309,2"}, {@code "km 172"} e a
 * notação rodoviária {@code "309+400"} (quilômetro mais metros).</p>
 *
 * <p>Texto que não corresponde a nenhum desses formatos devolve {@code null}.
 * Nunca devolve zero: a ausência de marcação precisa continuar distinguível de
 * uma marcação real no km 0.</p>
 */
public final class QuilometroParser {

    private static final Pattern DECIMAL = Pattern.compile("^\\d{1,4}(?:\\.\\d{1,3})?$");
    private static final Pattern KM_MAIS_METROS =
            Pattern.compile("^(\\d{1,4})\\+(\\d{1,3})$");
    private static final BigDecimal MIL = new BigDecimal("1000");

    private QuilometroParser() {
    }

    /**
     * @param raw marcação quilométrica como persistida
     * @return quilômetro em {@link BigDecimal}, ou {@code null} quando o texto
     *         não representa uma marcação reconhecível
     */
    public static BigDecimal parse(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return null;
        }
        normalized = normalized
                .replace("KM", "")
                .replace(" ", "")
                .replace(',', '.');
        if (normalized.isEmpty()) {
            return null;
        }

        Matcher kmMaisMetros = KM_MAIS_METROS.matcher(normalized);
        if (kmMaisMetros.matches()) {
            return new BigDecimal(kmMaisMetros.group(1))
                    .add(new BigDecimal(kmMaisMetros.group(2)).divide(MIL));
        }
        if (DECIMAL.matcher(normalized).matches()) {
            return new BigDecimal(normalized);
        }
        return null;
    }
}
