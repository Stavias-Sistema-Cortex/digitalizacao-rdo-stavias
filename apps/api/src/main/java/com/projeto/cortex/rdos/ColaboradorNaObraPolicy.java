package com.projeto.cortex.rdos;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Quem pode constar como mão de obra de um RDO.
 *
 * <p>A regra era: o colaborador precisa estar ativo <em>e</em> com vínculo
 * ATIVO na obra do RDO. O vínculo deixou de ser exigido.
 *
 * <p>O motivo veio do campo, não da tela. O apontamento chega de aparelhos que
 * passam horas sem rede, e o vínculo pode ter sido revogado nesse intervalo —
 * a pessoa trabalhou assim mesmo. Recusar não desfaz o que foi vivido: apenas
 * prende o registro no dispositivo, que foi o que travou quinze lançamentos
 * numa fila sem saída. E o diário registra o que aconteceu; o vínculo descreve
 * quem a obra reconhece hoje. São duas perguntas diferentes, e uma não deveria
 * poder anular a resposta da outra.
 *
 * <p>O que continua exigido é a referência: o colaborador precisa existir,
 * estar ativo e não ter sido removido. Sem isso não há a quem atribuir o
 * trabalho, e um identificador solto gravaria mão de obra que não corresponde
 * a ninguém — o RDO deixaria de ser auditável, que é o que ele serve para ser.
 * Afrouxar a autorização foi a decisão tomada; abrir mão de saber quem
 * trabalhou seria outra.
 */
final class ColaboradorNaObraPolicy {

    private static final String ATIVO = """
            SELECT CASE WHEN EXISTS (
                SELECT 1
                FROM colaborador collaborator
                WHERE collaborator.id = ?
                  AND collaborator.ativo = TRUE
                  AND collaborator.deletado_em IS NULL
            ) THEN 1 ELSE 0 END
            """;

    private ColaboradorNaObraPolicy() {
    }

    /**
     * @param obraId mantido no contrato porque a chamada é sobre a mão de obra
     *               <em>daquela</em> obra; deixou de restringir, não de
     *               descrever o que está sendo validado.
     */
    static void require(
            JdbcTemplate jdbcTemplate,
            String collaboratorId,
            String obraId
    ) {
        Integer valid = jdbcTemplate.queryForObject(
                ATIVO,
                Integer.class,
                collaboratorId
        );
        if (valid == null || valid != 1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Colaborador não encontrado ou inativo."
            );
        }
    }
}
