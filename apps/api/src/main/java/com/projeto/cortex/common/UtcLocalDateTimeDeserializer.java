package com.projeto.cortex.common;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/** Normaliza `Z`/offset para UTC e mantém compatibilidade com o legado sem offset. */
public final class UtcLocalDateTimeDeserializer
        extends JsonDeserializer<LocalDateTime> {

    @Override
    public LocalDateTime deserialize(
            JsonParser parser,
            DeserializationContext context
    ) throws IOException {
        String value = parser.getValueAsString();
        try {
            return UtcLocalDateTimes.parse(value);
        } catch (DateTimeParseException exception) {
            throw context.weirdStringException(
                    value,
                    LocalDateTime.class,
                    "Data e hora devem estar em ISO-8601."
            );
        }
    }
}
