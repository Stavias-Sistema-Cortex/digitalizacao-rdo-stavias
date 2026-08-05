package com.projeto.cortex.equipes;

import java.time.LocalDateTime;

/**
 * Pedido de desarquivamento.
 *
 * <p>Não há campo de motivo: devolver a equipe ao trabalho não exige
 * justificativa, do mesmo modo que arquivá-la não exige.
 */
public record EquipeUnarchiveRequest(
        Long baseVersao,
        LocalDateTime desarquivadaEm
) {
}
