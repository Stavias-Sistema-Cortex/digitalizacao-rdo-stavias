package com.projeto.cortex.intelligence.stavia.text;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Shared, accent-insensitive text utilities for the Stav.IA pipeline.
 *
 * <p>Centralises the normalization rule that was previously duplicated across
 * the intent classifier, the knowledge sources and the response generator so
 * that classification and retrieval always agree on how text is compared.
 */
public final class StaviaText {

    private static final Pattern DIACRITICS =
            Pattern.compile("\\p{M}+");

    private static final Pattern NON_WORD =
            Pattern.compile("[^\\p{L}\\p{Nd}]+");

    private StaviaText() {
    }

    /**
     * Normalizes free text for comparison: strips diacritics, lower-cases
     * using a stable locale and trims surrounding whitespace. Null input is
     * treated as empty text so callers never have to null-guard.
     */
    public static String normalize(String value) {
        if (value == null) {
            return "";
        }

        return DIACRITICS
                .matcher(
                        Normalizer.normalize(
                                value,
                                Normalizer.Form.NFD
                        )
                )
                .replaceAll("")
                .toLowerCase(Locale.ROOT)
                .trim();
    }

    /**
     * Splits already-normalized text into word tokens, discarding any
     * non-letter/non-digit separators.
     */
    public static String[] tokenize(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return new String[0];
        }

        return NON_WORD.split(normalized.strip());
    }

    /**
     * Word-anchored containment: true only when {@code term} appears at the
     * start of a word (or multi-word phrase). The match is anchored on a word
     * boundary <em>before</em> the term but allows inflections after it, so
     * "parada" matches "parada"/"paradas" but not "preparada", and the stem
     * "comec" still matches "começou".
     */
    public static boolean containsWord(
            String normalized,
            String term
    ) {
        return indexOfWord(normalized, term) >= 0;
    }

    /**
     * Position of the first word-anchored occurrence of {@code term} in
     * {@code normalized}, or -1 when it does not appear at a word start.
     */
    public static int indexOfWord(
            String normalized,
            String term
    ) {
        return wordPattern(term)
                .matcher(normalized)
                .results()
                .mapToInt(match -> match.start())
                .findFirst()
                .orElse(-1);
    }

    /**
     * Compiles a reusable word-anchored matcher for {@code term} (boundary
     * before the term, inflections allowed after). Callers that match the same
     * term repeatedly should cache the returned pattern.
     */
    public static Pattern wordPattern(String term) {
        return Pattern.compile(
                "\\b" + Pattern.quote(StaviaText.normalize(term))
        );
    }

    /**
     * Returns the trimmed {@code preferred} value when it carries text,
     * otherwise the {@code alternative} unchanged. Used by knowledge sources to
     * fall back from a human-readable label to a stable identifier.
     */
    public static String fallback(
            String preferred,
            String alternative
    ) {
        return preferred != null && !preferred.isBlank()
                ? preferred.trim()
                : alternative;
    }
}
