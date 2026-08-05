package com.projeto.cortex.equipamentos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.rdos.RdoAssetEligibilityService;
import com.projeto.cortex.rdos.RdoCreateRequest;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * O cadastro de máquina de terceiro contra PostgreSQL de verdade.
 *
 * <p>O que precisa ser provado aqui não é o CRUD — é que a máquina cadastrada
 * no Córtex atravessa inteiro o caminho que foi desenhado para asset importado:
 * aparece no catálogo de equipamentos da obra e passa pelo portão de
 * elegibilidade que o RDO aplica ao salvar. Se qualquer um dos dois recusasse,
 * o cadastro seria uma tela que não leva a lugar nenhum.
 */
@Testcontainers(disabledWithoutDocker = true)
class EquipamentoTerceirizadoIT {

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_equipamento_terceirizado_it");

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
     * O trajeto inteiro num teste só, porque é o trajeto que importa: cadastrar
     * põe a máquina no catálogo da obra e o RDO a aceita.
     */
    @Test
    void maquinaCadastradaEntraNoCatalogoDaObraEPassaPeloPortaoDoRdo() {
        String obraId = inserirObra("catalogo");
        String autorId = inserirColaborador("Apontador da frente");

        EquipamentoTerceirizadoResponse criado = servico(autorId).criar(
                obraId,
                new EquipamentoTerceirizadoRequest(
                        null,
                        "RETRO-99",
                        "Retroescavadeira alugada",
                        "Terraplenagem",
                        "Locadora Serra Ltda",
                        "12.345.678/0001-90",
                        "Diária fechada",
                        null,
                        null
                )
        );

        assertThat(criado.assetId()).isNotBlank();
        assertThat(criado.ativo()).isTrue();
        assertThat(criado.versaoEntidade()).isEqualTo(1);

        // Fora do alcance do conector: a origem é o próprio Córtex.
        assertThat(jdbc.queryForObject(
                "SELECT source_database FROM asset WHERE id = ?",
                String.class,
                criado.assetId()
        )).isEqualTo("cortex");
        assertThat(jdbc.queryForObject(
                "SELECT source_pk FROM asset WHERE id = ?",
                String.class,
                criado.assetId()
        )).isEqualTo(criado.id());

        // No catálogo da obra, do mesmo jeito que um asset importado.
        assertThat(jdbc.queryForList(
                """
                SELECT asset.name
                FROM asset_obra_eligibilidade eligibility
                JOIN asset ON asset.id = eligibility.asset_id
                WHERE eligibility.obra_id = ?
                  AND eligibility.status = 'ATIVO'
                  AND asset.active = TRUE
                  AND asset.deleted_at IS NULL
                """,
                String.class,
                obraId
        )).containsExactly("Retroescavadeira alugada");

        // E o portão que o RDO aplica ao salvar deixa passar.
        RdoAssetEligibilityService portao = new RdoAssetEligibilityService(jdbc);
        portao.requireEligible(obraId, List.of(equipamentoDoRdo(criado.assetId())));
    }

    /**
     * A máquina alugada some do RDO quando é devolvida à locadora, e volta
     * inteira quando é alugada de novo — mesma identidade, sem cadastro novo.
     */
    @Test
    void desativarTiraDoCatalogoEReativarDevolveAMesmaIdentidade() {
        String obraId = inserirObra("ciclo");
        String autorId = inserirColaborador("Encarregado");
        EquipamentoTerceirizadoService servico = servico(autorId);

        EquipamentoTerceirizadoResponse criado = servico.criar(
                obraId,
                new EquipamentoTerceirizadoRequest(
                        null, "ROLO-04", "Rolo compactador", "Pavimentação",
                        "Locadora Serra Ltda", null, null, null, null
                )
        );

        EquipamentoTerceirizadoResponse desativado = servico.atualizar(
                obraId,
                criado.id(),
                new EquipamentoTerceirizadoRequest(
                        null, "ROLO-04", "Rolo compactador", "Pavimentação",
                        "Locadora Serra Ltda", null, "Devolvido", false,
                        criado.versaoEntidade()
                )
        );

        assertThat(desativado.ativo()).isFalse();
        assertThatThrownBy(() -> new RdoAssetEligibilityService(jdbc)
                .requireEligible(obraId, List.of(equipamentoDoRdo(criado.assetId()))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("não está ativo e elegível");

        EquipamentoTerceirizadoResponse reativado = servico.atualizar(
                obraId,
                criado.id(),
                new EquipamentoTerceirizadoRequest(
                        null, "ROLO-04", "Rolo compactador", "Pavimentação",
                        "Locadora Serra Ltda", null, null, true,
                        desativado.versaoEntidade()
                )
        );

        assertThat(reativado.assetId()).isEqualTo(criado.assetId());
        assertThat(reativado.id()).isEqualTo(criado.id());
        new RdoAssetEligibilityService(jdbc)
                .requireEligible(obraId, List.of(equipamentoDoRdo(criado.assetId())));
    }

    /**
     * A tela é usada em obra, com rede que cai no meio do envio. Recusar o
     * reenviado faria a pessoa cadastrar a mesma máquina outra vez com outro id
     * — a duplicidade que este cadastro existe para evitar.
     */
    @Test
    void reenvioDoMesmoCadastroNaoCriaSegundaMaquina() {
        String obraId = inserirObra("reenvio");
        String autorId = inserirColaborador("Apontador");
        EquipamentoTerceirizadoService servico = servico(autorId);
        String cadastroId = UUID.randomUUID().toString();

        EquipamentoTerceirizadoRequest pedido = new EquipamentoTerceirizadoRequest(
                cadastroId, "ESC-11", "Escavadeira alugada", "Terraplenagem",
                "Locadora Serra Ltda", null, null, null, null
        );

        EquipamentoTerceirizadoResponse primeira = servico.criar(obraId, pedido);
        EquipamentoTerceirizadoResponse segunda = servico.criar(obraId, pedido);

        assertThat(segunda.id()).isEqualTo(primeira.id());
        assertThat(segunda.assetId()).isEqualTo(primeira.assetId());
        assertThat(servico.listarPorObra(obraId)).hasSize(1);
    }

    /**
     * A mesma máquina volta em obra seguinte sem cadastro novo: o que muda é o
     * alcance, não a identidade. Sem isso, cada obra recadastraria a mesma
     * retroescavadeira e a medição do parque de terceiros nunca fecharia.
     */
    @Test
    void mesmaMaquinaAlcancaObraNovaSemPerderAIdentidade() {
        String primeiraObra = inserirObra("primeira");
        String segundaObra = inserirObra("segunda");
        String autorId = inserirColaborador("Engenheiro");
        EquipamentoTerceirizadoService servico = servico(autorId);

        EquipamentoTerceirizadoResponse criado = servico.criar(
                primeiraObra,
                new EquipamentoTerceirizadoRequest(
                        null, "MOTO-02", "Motoniveladora alugada", "Terraplenagem",
                        "Locadora Serra Ltda", null, null, null, null
                )
        );

        servico.criar(
                segundaObra,
                new EquipamentoTerceirizadoRequest(
                        criado.id(), "MOTO-02", "Motoniveladora alugada",
                        "Terraplenagem", "Locadora Serra Ltda", null, null, null, null
                )
        );

        assertThat(servico.listarPorObra(primeiraObra))
                .extracting(EquipamentoTerceirizadoResponse::id)
                .containsExactly(criado.id());
        assertThat(servico.listarPorObra(segundaObra))
                .extracting(EquipamentoTerceirizadoResponse::id)
                .containsExactly(criado.id());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM equipamento_terceirizado WHERE id = ?",
                Integer.class,
                criado.id()
        )).isEqualTo(1);
    }

    /** Editar com versão vencida é conflito, não sobrescrita silenciosa. */
    @Test
    void edicaoComVersaoVencidaRecusa() {
        String obraId = inserirObra("versao");
        String autorId = inserirColaborador("Fiscal");
        EquipamentoTerceirizadoService servico = servico(autorId);

        EquipamentoTerceirizadoResponse criado = servico.criar(
                obraId,
                new EquipamentoTerceirizadoRequest(
                        null, "CAM-31", "Caminhão pipa alugado", "Transporte",
                        "Locadora Serra Ltda", null, null, null, null
                )
        );

        assertThatThrownBy(() -> servico.atualizar(
                obraId,
                criado.id(),
                new EquipamentoTerceirizadoRequest(
                        null, "CAM-31", "Caminhão pipa alugado", "Transporte",
                        "Outra locadora", null, null, null,
                        criado.versaoEntidade() + 7
                )
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Conflito de versão");
    }

    /**
     * O cadastro é da obra. Editar por uma obra que não alcança a máquina seria
     * mexer no parque de outra frente sem passar por porta nenhuma.
     */
    @Test
    void naoDeixaEditarPorObraQueNaoAlcancaAMaquina() {
        String obraDona = inserirObra("dona");
        String obraAlheia = inserirObra("alheia");
        String autorId = inserirColaborador("Apontador");
        EquipamentoTerceirizadoService servico = servico(autorId);

        EquipamentoTerceirizadoResponse criado = servico.criar(
                obraDona,
                new EquipamentoTerceirizadoRequest(
                        null, "TRA-08", "Trator alugado", "Terraplenagem",
                        "Locadora Serra Ltda", null, null, null, null
                )
        );

        assertThatThrownBy(() -> servico.atualizar(
                obraAlheia,
                criado.id(),
                new EquipamentoTerceirizadoRequest(
                        null, "TRA-08", "Trator alugado", "Terraplenagem",
                        "Locadora Serra Ltda", null, null, null,
                        criado.versaoEntidade()
                )
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("não pertence a esta obra");
    }

    /** Fornecedor em branco não identifica ninguém — o cadastro perde o sentido. */
    @Test
    void exigeFornecedor() {
        String obraId = inserirObra("fornecedor");
        String autorId = inserirColaborador("Apontador");

        assertThatThrownBy(() -> servico(autorId).criar(
                obraId,
                new EquipamentoTerceirizadoRequest(
                        null, "X-1", "Máquina sem dono", null, "   ",
                        null, null, null, null
                )
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("fornecedor");
    }

    private EquipamentoTerceirizadoService servico(String autorId) {
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        when(currentUserService.requireUserId()).thenReturn(autorId);
        return new EquipamentoTerceirizadoService(jdbc, currentUserService);
    }

    /** O assetId é o segundo componente, depois do id do próprio item. */
    private RdoCreateRequest.EquipamentoItem equipamentoDoRdo(String assetId) {
        return new RdoCreateRequest.EquipamentoItem(
                null, assetId, null, null, null, null, null, null, null, null
        );
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
                colaboradorId,
                colaboradorId,
                nome
        ));
        return colaboradorId;
    }
}
