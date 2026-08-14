package com.projeto.cortex.common;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/** Compatibilidade para contratos UTC antigos que ainda aceitam horário sem offset. */
public final class UtcLocalDateTimes {

    private static final Pattern HAS_OFFSET = Pattern.compile(
            "(?:Z|[+-]\\d{2}:\\d{2})$",
            Pattern.CASE_INSENSITIVE
    );

    private UtcLocalDateTimes() {
    }

    public static LocalDateTime parse(String rawValue) {
        String value = rawValue == null ? "" : rawValue.strip();
        if (HAS_OFFSET.matcher(value).find()) {
            return OffsetDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME)
                    .withOffsetSameInstant(ZoneOffset.UTC)
                    .toLocalDateTime();
        }
        return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
}
