package com.projeto.cortex.mensagens.api;

/** O instante original em que a pessoa limpou o historico no aparelho. */
public record ConversationHistoryClearRequest(
        String limpoAte
) {
}
