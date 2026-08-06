package com.projeto.cortex.equipes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import com.projeto.cortex.obras.ObraOperabilityGuard;
import com.projeto.cortex.obras.VinculoColaboradorObraService;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A versão que a equipe mostra tem de ser a versão que o sync confere.
 *
 * <p>Existe por uma falha que travou o trabalho de campo: havia dois contadores
 * com o mesmo nome. {@code equipe.versao_linha} conta alterações na linha;
 * {@code cortex_estado_entidade.versao_entidade} conta eventos, e é contra este
 * que toda mutação é validada. A leitura entregava o primeiro sob o nome do
 * segundo.
 *
 * <p>Os dois nascem iguais — por isso nenhum teste percebeu. Eles se separam no
 * primeiro vínculo: adicionar um membro registra evento sem tocar na linha. A
 * partir dali o aparelho montava toda mutação sobre um número que o servidor já
 * tinha deixado para trás, e cada recarga da lista reenvenenava o registro
 * local. Da cadeira de quem usa: "adiciono o colaborador e não sincroniza".
 *
 * <p>Por isso este teste precisa de banco. A divergência não é de lógica, é de
 * duas tabelas discordando — nenhum dublê de {@code JdbcTemplate} teria como
 * discordar de si mesmo.
 */
@Testcontainers(disabledWithoutDocker = true)
class EquipeVersaoCanonicaIT {

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_equipe_versao_it");

    private static JdbcTemplate jdbc;
    private static CortexOperationalMemoryService memoria;

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
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                DATABASE.getJdbcUrl(),
                DATABASE.getUsername(),
                DATABASE.getPassword()
        ));
        memoria = new CortexOperationalMemoryService(
                jdbc,
                // Sem os módulos de data o estado da equipe não serializa:
                // `inicioValidadeEm` é LocalDateTime e vai inteiro no evento.
                new ObjectMapper().findAndRegisterModules(),
                mock(ApplicationEventPublisher.class)
        );
    }

    /**
     * O caso que quebrou em campo, contado em números.
     *
     * <p>Criar deixa os dois contadores em 1 — a coincidência que escondia o
     * defeito. Cada vínculo move só o dos eventos. A leitura tem de seguir esse.
     */
    @Test
    void aVersaoLidaAcompanhaOsEventosENaoALinhaDaEquipe() {
        String obraId = inserirObra("versao");
        String actorId = inserirColaborador("JOAO LUCAS");
        EquipeService service = servico(actorId, obraId);
        String equipeId = UUID.randomUUID().toString();

        service.criar(new EquipeCreateRequest(
                equipeId, obraId, "FR Carlos", null, LocalDateTime.now()
        ));

        assertThat(versaoLida(service, equipeId)).isEqualTo(versaoCanonica(equipeId));
        assertThat(versaoDaLinha(equipeId)).isEqualTo(1L);

        // Três encarregados, como na equipe real que travou.
        for (String nome : new String[] {"CARLOS", "PAULO", "ADAO"}) {
            long antes = versaoLida(service, equipeId);

            service.adicionarMembro(equipeId, new EquipeMemberRequest(
                    null, inserirColaborador(nome), funcaoAtiva(),
                    false, LocalDateTime.now(), null, "Escalação.", false
            ));
            registrarVinculoAlterado(equipeId, obraId, actorId);

            // Exatamente um: o aparelho soma um por mutação enfileirada, e
            // errar o passo aqui condenaria toda a fila atrás dela.
            assertThat(versaoLida(service, equipeId)).isEqualTo(antes + 1);
            assertThat(versaoLida(service, equipeId))
                    .isEqualTo(versaoCanonica(equipeId));
        }

        // A prova de que são dois números: a linha nunca saiu de 1.
        assertThat(versaoDaLinha(equipeId)).isEqualTo(1L);
        assertThat(versaoLida(service, equipeId)).isEqualTo(4L);
    }

    /**
     * Editar a equipe depois dos vínculos — o gesto que era impossível.
     *
     * <p>A base vinha do que a tela leu. Quando a leitura entregava a versão da
     * linha, ela nunca batia com a dos eventos, e renomear uma equipe que tinha
     * membros era recusado para sempre.
     */
    @Test
    void editarDepoisDeVincularAceitaAVersaoQueATelaLeu() {
        String obraId = inserirObra("edicao");
        String actorId = inserirColaborador("MARIA");
        EquipeService service = servico(actorId, obraId);
        String equipeId = UUID.randomUUID().toString();

        service.criar(new EquipeCreateRequest(
                equipeId, obraId, "FR Antiga", null, LocalDateTime.now()
        ));
        service.adicionarMembro(equipeId, new EquipeMemberRequest(
                null, inserirColaborador("PEDRO"), funcaoAtiva(),
                false, LocalDateTime.now(), null, "Escalação.", false
        ));
        registrarVinculoAlterado(equipeId, obraId, actorId);

        long base = versaoLida(service, equipeId);
        EquipeResponse renomeada = service.atualizar(equipeId, new EquipeUpdateRequest(
                "FR Nova", null, "ATIVA", LocalDateTime.now(), null, base, "Correção."
        ));

        assertThat(renomeada.nome()).isEqualTo("FR Nova");

        /*
         * A conferência é numa leitura NOVA, não na resposta: `atualizar`
         * monta o retorno antes de publicar o evento, então o número que ele
         * carrega é o de antes. Quem importa aqui é a releitura — é o que a
         * lista faz ao recarregar, e é justamente essa releitura que
         * envenenava o registro local antes desta correção.
         */
        assertThat(versaoLida(service, equipeId))
                .isEqualTo(versaoCanonica(equipeId));
        assertThat(versaoLida(service, equipeId)).isGreaterThan(base);

        // E a versão velha continua sendo recusada: a trava não afrouxou.
        assertThatThrownBy(() -> service.atualizar(equipeId, new EquipeUpdateRequest(
                "FR Terceira", null, "ATIVA", LocalDateTime.now(), null,
                base, "Correção."
        ))).isInstanceOf(ResponseStatusException.class);
    }

    // ---------------------------------------------------------------

    private long versaoLida(EquipeService service, String equipeId) {
        return service.buscarPorId(equipeId).versaoEntidade();
    }

    private long versaoCanonica(String equipeId) {
        Long versao = jdbc.queryForObject(
                """
                SELECT versao_entidade FROM cortex_estado_entidade
                WHERE tipo_entidade = 'EQUIPE' AND entidade_id = ?
                """,
                Long.class, equipeId
        );
        return versao == null ? 0L : versao;
    }

    private long versaoDaLinha(String equipeId) {
        Long versao = jdbc.queryForObject(
                "SELECT versao_linha FROM equipe WHERE id = ?", Long.class, equipeId
        );
        return versao == null ? 0L : versao;
    }

    /* O mesmo evento que o EquipeSyncOperationHandler publica por vínculo. */
    private void registrarVinculoAlterado(
            String equipeId,
            String obraId,
            String actorId
    ) {
        memoria.registrarEvento(
                "EQUIPE", equipeId, "EQUIPE_VINCULO_ALTERADO", "SYNC", obraId,
                Map.of("teamId", equipeId, "actorId", actorId)
        );
    }

    private EquipeService servico(String actorId, String obraId) {
        CurrentUserService usuario = mock(CurrentUserService.class);
        when(usuario.requireUserId()).thenReturn(actorId);
        when(usuario.allowedObraIds(actorId)).thenReturn(Optional.empty());
        return new EquipeService(
                jdbc,
                usuario,
                new EquipeMemoryPublisher(memoria),
                mock(VinculoColaboradorObraService.class),
                mock(ObraOperabilityGuard.class)
        );
    }

    private String funcaoAtiva() {
        return jdbc.queryForObject(
                "SELECT id FROM funcao_operacional WHERE ativo = TRUE ORDER BY id LIMIT 1",
                String.class
        );
    }

    private String inserirObra(String sufixo) {
        String obraId = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO obra (id, codigo_contrato, codigo_cw, nome)
                VALUES (?, ?, ?, ?)
                """,
                obraId,
                "CTR-" + sufixo + "-" + obraId.substring(0, 8),
                "CW-" + sufixo + "-" + obraId.substring(0, 8),
                "Obra " + sufixo
        );
        return obraId;
    }

    private String inserirColaborador(String nome) {
        String colaboradorId = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem, nome
                ) VALUES (?, 'cortex', 'fixture', ?, ?)
                """,
                colaboradorId, colaboradorId, nome
        );
        return colaboradorId;
    }
}
