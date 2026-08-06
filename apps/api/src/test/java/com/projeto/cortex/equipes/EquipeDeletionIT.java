package com.projeto.cortex.equipes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.projeto.cortex.auth.CurrentUserService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Apagar equipe contra PostgreSQL de verdade.
 *
 * <p>Existe por uma falha concreta: o teste unitário verificava a ORDEM dos
 * comandos com um {@code JdbcTemplate} de mentira, e mock nenhum avalia uma
 * restrição CHECK. O primeiro apagar em obra com conversa de equipe falhou
 * porque {@code chk_conversa_tipo_escopo} exige que conversa de tipo EQUIPE
 * tenha {@code equipe_id} preenchido — anular a chave sozinho viola a regra.
 *
 * <p>A lição que este arquivo carrega: ordem de FK se prova com mock, o que o
 * banco recusa só se prova com banco.
 */
@Testcontainers(disabledWithoutDocker = true)
class EquipeDeletionIT {

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_equipe_deletion_it");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(
                        DATABASE.getJdbcUrl(),
                        DATABASE.getUsername(),
                        DATABASE.getPassword()
                )
                .locations("classpath:db/migration-postgresql")
                .load()
                .migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
    }

    /**
     * O caso que quebrou em produção: a equipe tem conversa, e a conversa de
     * equipe não aceita ficar sem equipe. Ela vira GRUPO, com as mensagens
     * intactas — são das pessoas que as escreveram.
     */
    @Test
    void apagaEquipeComConversaSemVioLarOEscopoDaConversa() {
        String obraId = inserirObra("conversa");
        String autorId = inserirColaborador("JOAO LUCAS");
        String equipeId = inserirEquipe(obraId, "FR Carlos", autorId);
        inserirMembro(equipeId, autorId);
        String conversaId = inserirConversaDeEquipe(equipeId, obraId, autorId);
        String mensagemId = inserirMensagem(conversaId, autorId);

        EquipeDeletionResponse apagada = servico(equipeId, obraId, autorId)
                .apagar(equipeId);

        assertThat(apagada.equipeId()).isEqualTo(equipeId);
        assertThat(apagada.membrosRemovidos()).isEqualTo(1);
        assertThat(existeEquipe(equipeId)).isFalse();

        // A conversa fica, agora como GRUPO, e a mensagem continua lá.
        assertThat(jdbc.queryForObject(
                "SELECT tipo FROM conversa WHERE id = ?", String.class, conversaId
        )).isEqualTo("GRUPO");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM mensagem WHERE id = ?",
                Integer.class, mensagemId
        )).isEqualTo(1);
    }

    /** Equipe sem conversa também sai — o caminho simples continua simples. */
    @Test
    void apagaEquipeSemConversa() {
        String obraId = inserirObra("simples");
        String autorId = inserirColaborador("MARIA");
        String equipeId = inserirEquipe(obraId, "FR Simples", autorId);
        inserirMembro(equipeId, autorId);

        servico(equipeId, obraId, autorId).apagar(equipeId);

        assertThat(existeEquipe(equipeId)).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM equipe_membro WHERE equipe_id = ?",
                Integer.class, equipeId
        )).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM equipe_obra WHERE equipe_id = ?",
                Integer.class, equipeId
        )).isZero();
    }

    // ---------------------------------------------------------------

    private EquipeDeletionService servico(
            String equipeId,
            String obraId,
            String actorId
    ) {
        CurrentUserService usuario = mock(CurrentUserService.class);
        when(usuario.requireUserId()).thenReturn(actorId);
        EquipeService equipes = mock(EquipeService.class);
        LocalDateTime agora = LocalDateTime.of(2026, 8, 6, 9, 0);
        when(equipes.buscarPorId(equipeId)).thenReturn(new EquipeResponse(
                equipeId, obraId, "Obra", "FR Carlos", null, "ATIVA",
                agora, null, 1, agora, agora, List.of()
        ));
        return new EquipeDeletionService(
                jdbc, usuario, equipes, mock(EquipeMemoryPublisher.class)
        );
    }

    private boolean existeEquipe(String equipeId) {
        Integer total = jdbc.queryForObject(
                "SELECT count(*) FROM equipe WHERE id = ?", Integer.class, equipeId
        );
        return total != null && total > 0;
    }

    private String inserirObra(String sufixo) {
        String obraId = UUID.randomUUID().toString();
        transactions.executeWithoutResult(status -> jdbc.update(
                """
                INSERT INTO obra (id, codigo_contrato, codigo_cw, nome)
                VALUES (?, ?, ?, ?)
                """,
                obraId,
                "CTR-" + sufixo + "-" + obraId.substring(0, 8),
                "CW-" + sufixo + "-" + obraId.substring(0, 8),
                "Obra " + sufixo
        ));
        return obraId;
    }

    private String inserirColaborador(String nome) {
        String colaboradorId = UUID.randomUUID().toString();
        transactions.executeWithoutResult(status -> jdbc.update(
                """
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem, nome
                ) VALUES (?, 'cortex', 'fixture', ?, ?)
                """,
                colaboradorId, colaboradorId, nome
        ));
        return colaboradorId;
    }

    private String inserirEquipe(String obraId, String nome, String autorId) {
        String equipeId = UUID.randomUUID().toString();
        transactions.executeWithoutResult(status -> {
            jdbc.update(
                    """
                    INSERT INTO equipe (
                        id, obra_id, obra_principal_id, nome,
                        inicio_validade_em, criado_por, atualizado_por
                    ) VALUES (?, ?, ?, ?, now(), ?, ?)
                    """,
                    equipeId, obraId, obraId, nome, autorId, autorId
            );
            jdbc.update(
                    """
                    INSERT INTO equipe_obra (
                        id, equipe_id, obra_id, inicio_em, atribuido_por
                    ) VALUES (?, ?, ?, now(), ?)
                    """,
                    UUID.randomUUID().toString(), equipeId, obraId, autorId
            );
        });
        return equipeId;
    }

    private void inserirMembro(String equipeId, String colaboradorId) {
        transactions.executeWithoutResult(status -> jdbc.update(
                """
                INSERT INTO equipe_membro (
                    id, equipe_id, colaborador_id, inicio_em,
                    adicionado_por, atribuido_por, atualizado_por
                ) VALUES (?, ?, ?, now(), ?, ?, ?)
                """,
                UUID.randomUUID().toString(), equipeId, colaboradorId,
                colaboradorId, colaboradorId, colaboradorId
        ));
        }

    private String inserirConversaDeEquipe(
            String equipeId,
            String obraId,
            String autorId
    ) {
        String conversaId = UUID.randomUUID().toString();
        transactions.executeWithoutResult(status -> jdbc.update(
                """
                INSERT INTO conversa (
                    id, tipo, obra_id, equipe_id, titulo, criado_por
                ) VALUES (?, 'EQUIPE', ?, ?, 'Conversa da equipe', ?)
                """,
                conversaId, obraId, equipeId, autorId
        ));
        return conversaId;
    }

    private String inserirMensagem(String conversaId, String autorId) {
        String mensagemId = UUID.randomUUID().toString();
        transactions.executeWithoutResult(status -> jdbc.update(
                """
                INSERT INTO mensagem (
                    id, conversa_id, autor_id, corpo,
                    client_mutation_id, criado_cliente_em
                ) VALUES (?, ?, ?, 'Bom dia', ?, now())
                """,
                mensagemId, conversaId, autorId, UUID.randomUUID().toString()
        ));
        return mensagemId;
    }
}
