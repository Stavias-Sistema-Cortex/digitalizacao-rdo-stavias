package com.projeto.cortex.intelligence.stavia.planning;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TemporalFilterParser {

    private static final ZoneId BUSINESS_ZONE =
            ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter DATE_BR =
            DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Pattern DATE_PATTERN =
            Pattern.compile("(\\d{2}/\\d{2}/\\d{4}|\\d{4}-\\d{2}-\\d{2})");

    private final Clock clock;

    public TemporalFilterParser(Clock clock) {
        this.clock = clock == null
                ? Clock.system(BUSINESS_ZONE)
                : clock;
    }

    public TemporalFilter parse(String normalized, boolean latestFallback) {
        LocalDate today =
                LocalDate.now(clock.withZone(BUSINESS_ZONE));

        if (contains(normalized, "hoje")) {
            return new TemporalFilter(today, today, "HOJE", null, null);
        }

        if (contains(normalized, "ontem")) {
            LocalDate yesterday = today.minusDays(1);
            return new TemporalFilter(
                    yesterday,
                    yesterday,
                    "ONTEM",
                    null,
                    null
            );
        }

        if (contains(normalized, "esta semana")
                || contains(normalized, "essa semana")) {
            LocalDate start =
                    today.minusDays(today.getDayOfWeek().getValue() - 1L);
            return new TemporalFilter(
                    start,
                    today,
                    "ESTA_SEMANA",
                    null,
                    null
            );
        }

        if (contains(normalized, "semana passada")) {
            LocalDate thisWeekStart =
                    today.minusDays(today.getDayOfWeek().getValue() - 1L);
            return new TemporalFilter(
                    thisWeekStart.minusWeeks(1),
                    thisWeekStart.minusDays(1),
                    "SEMANA_PASSADA",
                    null,
                    null
            );
        }

        LocalDate explicit = extractDate(normalized);
        if (explicit != null) {
            return new TemporalFilter(explicit, explicit, null, null, null);
        }

        if (latestFallback) {
            return TemporalFilter.latest("RDO_STATUS_E_DATA_OPERACIONAL");
        }

        return TemporalFilter.none();
    }

    public boolean requestsLatest(String normalized) {
        return contains(normalized, "mais recente")
                || contains(normalized, "ultimo")
                || contains(normalized, "ultima")
                || contains(normalized, "atual")
                || contains(normalized, "atualmente")
                || contains(normalized, "ultimo rdo");
    }

    private LocalDate extractDate(String normalized) {
        Matcher matcher = DATE_PATTERN.matcher(normalized);

        if (!matcher.find()) {
            return null;
        }

        String value = matcher.group(1);

        try {
            if (value.contains("/")) {
                return LocalDate.parse(value, DATE_BR);
            }

            return LocalDate.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private boolean contains(String value, String candidate) {
        return value.toLowerCase(Locale.ROOT).contains(candidate);
    }
}
