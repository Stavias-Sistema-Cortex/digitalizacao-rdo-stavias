package com.projeto.cortex.financeiro;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;
import java.math.BigDecimal;

public final class ExactDecimalJsonSerializer
        extends JsonSerializer<BigDecimal> {

    @Override
    public void serialize(
            BigDecimal value,
            JsonGenerator generator,
            SerializerProvider serializers
    ) throws IOException {
        generator.writeString(value.toPlainString());
    }
}
