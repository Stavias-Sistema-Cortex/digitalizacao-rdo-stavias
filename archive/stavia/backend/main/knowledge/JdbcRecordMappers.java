package com.projeto.cortex.intelligence.stavia.knowledge;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Null-safe conversions from {@code java.sql} temporal types to
 * {@code java.time} types, shared by the JDBC knowledge readers so the mapping
 * is defined once instead of copied into every reader.
 */
public final class JdbcRecordMappers {

    private JdbcRecordMappers() {
    }

    public static LocalDate toLocalDate(Date value) {
        return value == null
                ? null
                : value.toLocalDate();
    }

    public static LocalTime toLocalTime(Time value) {
        return value == null
                ? null
                : value.toLocalTime();
    }

    public static LocalDateTime toLocalDateTime(Timestamp value) {
        return value == null
                ? null
                : value.toLocalDateTime();
    }
}
