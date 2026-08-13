package com.projeto.cortex.mensagens.api;

import java.time.LocalDateTime;
import java.util.List;

public record ConversationResponse(
        String id,
        String tipo,
        String titulo,
        String obraId,
        String equipeId,
        String status,
        LocalDateTime criadaEm,
        LocalDateTime atualizadaEm,
        long versao,
        /**
         * Se quem está lendo tirou esta conversa da própria lista.
         *
         * <p>É por leitor, não pela conversa: a mesma conversa vem arquivada
         * para mim e ativa para quem estava junto. O campo {@code status} acima
         * segue sendo o da conversa, que é outra coisa.
         */
        boolean arquivadaParaMim,
        List<ParticipantResponse> participantes
) {
}
