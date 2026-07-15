package com.projeto.cortex.mensagens;

import java.time.LocalDateTime;

public record ConversaParticipantResponse(
        String id,
        String conversaId,
        String colaboradorId,
        String colaboradorNome,
        String papel,
        String status,
        LocalDateTime entrouEm,
        LocalDateTime saiuEm,
        LocalDateTime ultimaLeituraEm,
        long versaoEntidade
) {
}
