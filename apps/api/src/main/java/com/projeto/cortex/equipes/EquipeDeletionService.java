package com.projeto.cortex.equipes;

import com.projeto.cortex.auth.CurrentUserService;
import org.springframework.dao.DataIntegrityViolationException;
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

        /*
         * A conversa fica — as mensagens são das pessoas que as escreveram —,
         * mas não basta soltar a chave: `chk_conversa_tipo_escopo` exige que
         * conversa de tipo EQUIPE tenha equipe_id preenchido. Anular sozinho
         * viola a restrição, e foi assim que o primeiro apagar falhou em obra
         * com conversa de equipe.
         *
         * Ela vira GRUPO, que é o tipo de quem conversa sem estar amarrado a
         * equipe nem a obra — exatamente o que ela passa a ser. Participantes,
         * mensagens e título continuam.
         */
        jdbcTemplate.update(
                """
                UPDATE conversa
                SET tipo = 'GRUPO', equipe_id = NULL, obra_id = NULL
                WHERE equipe_id = ?
                """,
                id
        );

        int membros = jdbcTemplate.update(
                "DELETE FROM equipe_membro WHERE equipe_id = ?", id
        );
        jdbcTemplate.update(
                "DELETE FROM equipe_obra WHERE equipe_id = ?", id
        );

        /*
         * Se sobrar alguma amarra que este método não conhece, a pessoa lê o
         * que prendeu em vez de "não foi possível". Um 500 mudo manda tentar
         * de novo, e tentar de novo nunca vai funcionar.
         */
        int apagadas;
        try {
            apagadas = jdbcTemplate.update(
                    "DELETE FROM equipe WHERE id = ?", id
            );
        } catch (DataIntegrityViolationException prendeu) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A equipe ainda está presa a outro registro e não pôde ser"
                            + " apagada. Avise o suporte com o id " + id + ".",
                    prendeu
            );
        }
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
