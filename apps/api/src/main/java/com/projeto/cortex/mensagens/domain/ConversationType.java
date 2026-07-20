package com.projeto.cortex.mensagens.domain;

import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public enum ConversationType {
    DIRETA,
    GRUPO,
    EQUIPE,
    OBRA;

    public static ConversationType require(String value) {
        if (value == null || value.isBlank()) {
            throw invalid();
        }
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Tipo de conversa inválido."
        );
    }
}
