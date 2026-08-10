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
 *
 * <p>O separador decimal é lido em português, e o ponto de milhar também. A
 * leitura anterior trocava apenas a primeira vírgula por ponto e exigia o
 * formato exato {@code 1234.567}: um km escrito como se escreve aqui —
 * {@code "1.206,5"} — virava {@code "1.206.5"} e era descartado como
 * irreconhecível. O trecho ficava sem posição no mapa e no esquemático, e o
 * cadastro recusava o salvamento por falta de quilômetro, sem que nada dissesse
 * que o culpado era o ponto de milhar.</p>
 */
public final class QuilometroParser {

    private static final Pattern KM_MAIS_METROS =
            Pattern.compile("^(\\d{1,4})\\+(\\d{1,3})$");
    private static final Pattern DECIMAL = Pattern.compile("^\\d{1,4}(?:\\.\\d{1,3})?$");
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
                .replace(" ", "");
        if (normalized.isEmpty()) {
            return null;
        }

        Matcher kmMaisMetros = KM_MAIS_METROS.matcher(normalized);
        if (kmMaisMetros.matches()) {
            return new BigDecimal(kmMaisMetros.group(1))
                    .add(new BigDecimal(kmMaisMetros.group(2)).divide(MIL));
        }

        String decimal = decimalDigitado(normalized);
        if (decimal == null || !DECIMAL.matcher(decimal).matches()) {
            return null;
        }
        return new BigDecimal(decimal);
    }

    /**
     * Reduz o quilômetro digitado à forma com ponto decimal e sem milhar.
     *
     * <p>Quando os dois separadores aparecem, o último a aparecer é o decimal —
     * é o que distingue {@code 1.206,5} de {@code 1,206.5}, e o outro é o
     * milhar.
     *
     * <p>Um ponto sozinho é sempre decimal, e essa é a diferença deliberada em
     * relação ao leitor de números do resto do sistema: ali {@code 1.234} é mil
     * duzentos e trinta e quatro, aqui {@code 206.822} é o km 206,822. É a
     * notação que a base inteira já guarda, e trocá-la moveria cada trecho
     * antigo mil vezes para frente na rodovia.
     */
    private static String decimalDigitado(String texto) {
        if (!texto.matches("^[\\d.,]+$")) {
            return null;
        }
        int ultimaVirgula = texto.lastIndexOf(',');
        int ultimoPonto = texto.lastIndexOf('.');
        if (ultimaVirgula >= 0 && ultimoPonto >= 0) {
            char decimal = ultimaVirgula > ultimoPonto ? ',' : '.';
            char milhar = decimal == ',' ? '.' : ',';
            return texto
                    .replace(String.valueOf(milhar), "")
                    .replace(decimal, '.');
        }
        if (ultimaVirgula >= 0) {
            return texto.indexOf(',') == ultimaVirgula
                    ? texto.replace(',', '.')
                    : null;
        }
        return texto;
    }
}
