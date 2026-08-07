package com.projeto.cortex.equipes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import com.projeto.cortex.sync.EquipeSyncOperationHandler;
import com.projeto.cortex.sync.SyncMutationContext;
import com.projeto.cortex.sync.SyncPushRequest;
import com.projeto.cortex.obras.ObraOperabilityGuard;
import com.projeto.cortex.obras.VinculoColaboradorObraService;
import java.time.LocalDateTime;
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

    private static final ObjectMapper JSON =
            new ObjectMapper().registerModule(new JavaTimeModule());

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

        /*
         * Três encarregados pelo caminho de verdade: o handler de sync, não uma
         * imitação dele.
         *
         * A versão anterior deste laço chamava o serviço e registrava o evento
         * de vínculo à mão, replicando o que eu achava que o handler fazia. Um
         * teste que reimplementa o alvo só confirma a minha leitura dele — se o
         * handler contasse dois eventos por mutação, este teste continuaria
         * verde e a fila continuaria travando. Medir exige passar por dentro.
         */
        EquipeSyncOperationHandler handler = handler(service, actorId);
        for (String nome : new String[] {"CARLOS", "PAULO", "ADAO"}) {
            long antes = versaoLida(service, equipeId);

            handler.apply(
                    adicionarMembro(equipeId, inserirColaborador(nome)),
                    new SyncMutationContext(actorId, "dispositivo-1")
            );

            /*
             * Exatamente um. O aparelho monta a base da próxima mutação somando
             * um por mutação já enfileirada; se uma delas movesse a versão em
             * dois, tudo o que estivesse atrás na fila nasceria condenado — e
             * da cadeira do usuário isso é "o primeiro colaborador entra e o
             * segundo não".
             */
            assertThat(versaoLida(service, equipeId)).isEqualTo(antes + 1);
            assertThat(versaoLida(service, equipeId))
                    .isEqualTo(versaoCanonica(equipeId));

            /*
             * A tela projeta a participação recém-criada antes de qualquer
             * recarga, e precisa projetá-la com a versão que o servidor dá.
             * Projetava 0, que nenhuma linha jamais tem.
             */
            assertThat(versaoDoUltimoMembro(equipeId)).isEqualTo(1L);
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
        handler(service, actorId).apply(
                adicionarMembro(equipeId, inserirColaborador("PEDRO")),
                new SyncMutationContext(actorId, "dispositivo-1")
        );
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

    private long versaoDoUltimoMembro(String equipeId) {
        Long versao = jdbc.queryForObject(
                """
                SELECT versao_linha FROM equipe_membro
                WHERE equipe_id = ? ORDER BY criado_em DESC, id DESC LIMIT 1
                """,
                Long.class, equipeId
        );
        return versao == null ? 0L : versao;
    }

    private EquipeSyncOperationHandler handler(
            EquipeService service,
            String actorId
    ) {
        CurrentUserService usuario = mock(CurrentUserService.class);
        when(usuario.requireUserId()).thenReturn(actorId);
        return new EquipeSyncOperationHandler(
                service, memoria, usuario, JSON
        );
    }

    /* O envelope que o aparelho manda ao adicionar alguém à equipe. */
    private SyncPushRequest.MutacaoCliente adicionarMembro(
            String equipeId,
            String colaboradorId
    ) {
        ObjectNode vinculo = JSON.createObjectNode();
        vinculo.put("colaboradorId", colaboradorId);
        vinculo.put("funcaoOperacionalId", funcaoAtiva());
        vinculo.put("responsavel", false);
        vinculo.put("motivo", "Escalação.");
        vinculo.put("concederAcessoObra", false);

        ObjectNode payload = JSON.createObjectNode();
        payload.put("id", equipeId);
        payload.put("vinculoAcao", "ADICIONAR_MEMBRO");
        payload.set("vinculo", vinculo);

        /*
         * Os dois apelidos, preenchidos juntos.
         *
         * O envelope carrega o mesmo dado em português e em inglês
         * (`entidadeId` e `entityId`), e o handler lê o inglês. O construtor
         * curto só preenche o português e deixa o resto nulo — quem o usasse
         * aqui entregaria ao handler um envelope que o `SyncService` jamais
         * deixaria passar: ele exige `entityId` presente (MALFORMED_ENTITY_ID)
         * e idêntico a `entidadeId` (ENTITY_ALIAS_MISMATCH) antes de despachar.
         *
         * Medir o handler por dentro só vale se a entrada for uma que ele possa
         * mesmo receber em produção. Com o construtor curto, o teste morria de
         * NullPointerException dentro do handler — um estouro que a fila real
         * nunca veria, porque a requisição teria sido recusada com 400 antes.
         */
        return new SyncPushRequest.MutacaoCliente(
                UUID.randomUUID().toString(),
                "EQUIPE",
                equipeId,
                "ALTERAR_VINCULO_EQUIPE",
                null,
                payload,
                LocalDateTime.now(),
                UUID.randomUUID().toString(),
                null,
                null,
                null,
                null,
                "EQUIPE",
                equipeId,
                "UPDATE",
                null,
                null,
                null,
                null,
                null,
                null,
                null
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
