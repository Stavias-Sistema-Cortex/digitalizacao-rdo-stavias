package com.projeto.cortex.rdos;

import com.projeto.cortex.common.SyncConvergenceWindow;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Quem pode constar como mão de obra de um RDO.
 *
 * <p>A regra era uma só: o colaborador precisa estar ativo <em>e</em> com
 * vínculo ATIVO na obra do RDO. Ela faz sentido no cadastro interativo — quem
 * monta um RDO na tela escolhe de uma lista que já é o vínculo — e não faz na
 * convergência do sync.
 *
 * <p>O apontamento vem do campo, muitas vezes de um aparelho que ficou horas
 * sem rede. O vínculo pode ter sido revogado nesse intervalo, e a pessoa
 * trabalhou assim mesmo: aconteceu, está no diário, e o dispositivo carrega a
 * única cópia. Recusar a escrita não desfaz o que foi vivido — apenas prende o
 * registro no aparelho, e foi exatamente o que manteve quinze lançamentos
 * parados numa fila que não drenava.
 *
 * <p>Dentro da janela de convergência, portanto, o vínculo deixa de ser
 * exigido. O que continua exigido é o colaborador existir, estar ativo e não
 * ter sido removido: sem isso não há a quem atribuir o trabalho, e aceitar um
 * identificador solto gravaria mão de obra que não corresponde a ninguém.
 * Afrouxar a autorização é uma decisão; abrir mão da referência seria outra, e
 * não é a que está sendo tomada aqui.
 *
 * <p>Fora da janela — endpoints interativos — o contrato segue inalterado: ali
 * não há dado de campo em risco, há alguém montando um RDO com uma pessoa que
 * a obra não reconhece.
 */
final class ColaboradorNaObraPolicy {

    private static final String VINCULADO_E_ATIVO = """
            SELECT CASE WHEN EXISTS (
                SELECT 1
                FROM colaborador collaborator
                JOIN vinculo_colaborador_obra link
                  ON link.colaborador_id = collaborator.id
                 AND link.obra_id = ?
                 AND link.status = 'ATIVO'
                WHERE collaborator.id = ?
                  AND collaborator.ativo = TRUE
                  AND collaborator.deletado_em IS NULL
            ) THEN 1 ELSE 0 END
            """;

    private static final String APENAS_ATIVO = """
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

    static void require(
            JdbcTemplate jdbcTemplate,
            String collaboratorId,
            String obraId
    ) {
        if (SyncConvergenceWindow.isOpen()) {
            requireExistente(jdbcTemplate, collaboratorId);
            return;
        }
        requireVinculado(jdbcTemplate, collaboratorId, obraId);
    }

    private static void requireExistente(
            JdbcTemplate jdbcTemplate,
            String collaboratorId
    ) {
        Integer valid = jdbcTemplate.queryForObject(
                APENAS_ATIVO,
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

    private static void requireVinculado(
            JdbcTemplate jdbcTemplate,
            String collaboratorId,
            String obraId
    ) {
        Integer valid = jdbcTemplate.queryForObject(
                VINCULADO_E_ATIVO,
                Integer.class,
                obraId,
                collaboratorId
        );
        if (valid == null || valid != 1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Colaborador não está ativo e vinculado à obra do RDO."
            );
        }
    }
}
