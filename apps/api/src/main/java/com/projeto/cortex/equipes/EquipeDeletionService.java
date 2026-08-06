package com.projeto.cortex.equipes;

import com.projeto.cortex.auth.CurrentUserService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Apagar uma equipe de verdade, não só arquivá-la.
 *
 * <p>Arquivar preserva; apagar existe para o caso em que preservar é o
 * problema — a equipe de teste, a criada por engano, a que ficou com uma fila
 * de sincronização doente pendurada nela. Sem este caminho a única saída era o
 * console do banco, e apagar por fora é justamente o que quebra a
 * sincronização dos aparelhos que ainda a citam.
 *
 * <p>Só o Alfa apaga, como em todo o resto da gestão de equipe. E apagar leva
 * junto o que é da equipe — participações e associações a obras —, mas não o
 * que apenas a mencionava: a conversa fica, desamarrada, porque as mensagens
 * são das pessoas que as escreveram.
 */
@Service
public class EquipeDeletionService {

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserService currentUserService;
    private final EquipeService equipeService;
    private final EquipeMemoryPublisher memoryPublisher;

    public EquipeDeletionService(
            JdbcTemplate jdbcTemplate,
            CurrentUserService currentUserService,
            EquipeService equipeService,
            EquipeMemoryPublisher memoryPublisher
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUserService = currentUserService;
        this.equipeService = equipeService;
        this.memoryPublisher = memoryPublisher;
    }

    @Transactional
    public EquipeDeletionResponse apagar(String equipeId) {
        currentUserService.requireAlfa();
        String actorId = currentUserService.requireUserId();
        if (equipeId == null || equipeId.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "equipeId é obrigatório."
            );
        }
        String id = equipeId.trim();

        // Carrega antes de apagar: o registro na Memória precisa do retrato.
        EquipeResponse antes = equipeService.buscarPorId(id);

        // A conversa fica; só se desamarra. As mensagens são das pessoas.
        jdbcTemplate.update(
                "UPDATE conversa SET equipe_id = NULL WHERE equipe_id = ?",
                id
        );

        int membros = jdbcTemplate.update(
                "DELETE FROM equipe_membro WHERE equipe_id = ?", id
        );
        jdbcTemplate.update(
                "DELETE FROM equipe_obra WHERE equipe_id = ?", id
        );

        int apagadas = jdbcTemplate.update(
                "DELETE FROM equipe WHERE id = ?", id
        );
        if (apagadas != 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A equipe mudou durante a exclusão. Recarregue e tente de novo."
            );
        }

        memoryPublisher.equipeApagada(antes, actorId);
        return new EquipeDeletionResponse(id, antes.nome(), membros);
    }
}
