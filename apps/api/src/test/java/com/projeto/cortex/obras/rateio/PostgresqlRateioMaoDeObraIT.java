package com.projeto.cortex.obras.rateio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.projeto.cortex.auth.CurrentUserService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * O que o servidor entrega ao rateio, conferido contra um PostgreSQL de
 * verdade.
 *
 * <p>São três promessas que só o banco pode confirmar: o RDO sem ninguém
 * apontado chega com a lista vazia (e não some), o RDO apagado não chega, e
 * quem não é Alfa recebe apenas as obras a que tem caminho.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresqlRateioMaoDeObraIT {

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_rateio_it");

    private static JdbcTemplate jdbc;

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
    }

    /** Um usuário de teste: quem ele é e se enxerga tudo. */
    private RateioMaoDeObraService servicoPara(String userId, boolean alfa) {
        CurrentUserService usuario = new CurrentUserService(
                jdbc,
                new MockEnvironment(),
                false
        ) {
            @Override
            public String requireUserId() {
                return userId;
            }

            @Override
            public boolean isAlfa(String colaboradorId) {
                return alfa;
            }
        };
        return new RateioMaoDeObraService(jdbc, usuario);
    }

    @Test
    void oRdoSemNinguemApontadoChegaComListaVazia() {
        String obraId = obra("vazio");
        String rdoId = rdo(obraId, "2026-07-10", "RDO-0001");

        RateioMaoDeObraResponse resposta = servicoPara(alfa(), true)
                .apontamentosDoPeriodo(
                        LocalDate.parse("2026-07-01"),
                        LocalDate.parse("2026-07-31")
                );

        RateioMaoDeObraResponse.RdoDoRateio encontrado =
                resposta.rdos().stream()
                        .filter(item -> item.id().equals(rdoId))
                        .findFirst()
                        .orElseThrow();
        assertThat(encontrado.maoObra()).isEmpty();
        assertThat(resposta.completo()).isTrue();
        // O nome da obra viaja junto: sem ele, quem ainda não baixou a lista
        // de obras veria um identificador cru — ou não veria a obra.
        assertThat(encontrado.obraNome()).isEqualTo("Obra vazio");
    }

    /*
     * O RDO sem equipe apontada ainda tem quem o assinou, e é essa assinatura
     * que fazia falta: o rateio contava só a lista de mão de obra, e um dia
     * inteiro de trabalho declarado por quem preencheu o documento entrava
     * como dia de ninguém. O servidor não decide isso — ele entrega os campos,
     * e o núcleo do rateio, um só nos dois caminhos, conta a presença.
     */
    @Test
    void aAssinaturaDoRdoViajaParaOQuemContaAPresenca() {
        String obraId = obra("assinatura");
        String apontador = colaborador("Quem Aponta");
        // A função vem do Academy pelo sync; aqui ela já está no cadastro.
        jdbc.update(
                "UPDATE colaborador SET funcao = 'APONTADOR DE OBRA' WHERE id = ?",
                apontador
        );
        String rdoId = rdo(obraId, "2026-07-10", "RDO-0009");
        jdbc.update(
                """
                UPDATE rdo
                   SET preenchido_por = 'QUEM PREENCHEU',
                       apontador_rdo = 'QUEM APONTA',
                       apontador_colaborador_id = ?
                 WHERE id = ?
                """,
                apontador,
                rdoId
        );

        RateioMaoDeObraResponse.RdoDoRateio encontrado =
                servicoPara(alfa(), true)
                        .apontamentosDoPeriodo(
                                LocalDate.parse("2026-07-01"),
                                LocalDate.parse("2026-07-31")
                        )
                        .rdos().stream()
                        .filter(item -> item.id().equals(rdoId))
                        .findFirst()
                        .orElseThrow();

        assertThat(encontrado.maoObra()).isEmpty();
        assertThat(encontrado.preenchidoPor()).isEqualTo("QUEM PREENCHEU");
        assertThat(encontrado.apontadorRdo()).isEqualTo("QUEM APONTA");
        assertThat(encontrado.apontadorColaboradorId()).isEqualTo(apontador);
        // O ofício do cadastro viaja junto: é ele que a matriz mostra no
        // lugar do rótulo genérico "Apontador do RDO".
        assertThat(encontrado.apontadorFuncao()).isEqualTo("APONTADOR DE OBRA");
    }

    @Test
    void aMaoDeObraApontadaChegaComNomeECargo() {
        String obraId = obra("gente");
        String rdoId = rdo(obraId, "2026-07-11", "RDO-0002");
        String colaboradorId = colaborador("Antonio Marcos");
        maoDeObra(rdoId, colaboradorId, "Antonio Marcos", "MOTORISTA");
        maoDeObra(rdoId, null, "Ajudante somado à mão", "AJUDANTE");

        RateioMaoDeObraResponse.RdoDoRateio encontrado =
                servicoPara(alfa(), true)
                        .apontamentosDoPeriodo(
                                LocalDate.parse("2026-07-01"),
                                LocalDate.parse("2026-07-31")
                        )
                        .rdos()
                        .stream()
                        .filter(item -> item.id().equals(rdoId))
                        .findFirst()
                        .orElseThrow();

        assertThat(encontrado.maoObra())
                .extracting(
                        RateioMaoDeObraResponse.MaoDeObraDoRateio::nomeColaborador
                )
                .containsExactlyInAnyOrder(
                        "Antonio Marcos",
                        "Ajudante somado à mão"
                );
        assertThat(encontrado.maoObra())
                .extracting(
                        RateioMaoDeObraResponse.MaoDeObraDoRateio::colaboradorId
                )
                .containsExactlyInAnyOrder(colaboradorId, null);
    }

    @Test
    void oRdoApagadoNaoEntraNoRateio() {
        String obraId = obra("apagado");
        String vivo = rdo(obraId, "2026-07-12", "RDO-0003");
        String apagado = rdo(obraId, "2026-07-13", "RDO-0004");
        jdbc.update(
                "UPDATE rdo SET cancelado_em = now(), status = 'CANCELADA'"
                        + " WHERE id = ?",
                apagado
        );

        List<String> ids = servicoPara(alfa(), true)
                .apontamentosDoPeriodo(
                        LocalDate.parse("2026-07-12"),
                        LocalDate.parse("2026-07-13")
                )
                .rdos()
                .stream()
                .map(RateioMaoDeObraResponse.RdoDoRateio::id)
                .toList();

        assertThat(ids).contains(vivo).doesNotContain(apagado);
    }

    @Test
    void quemNaoEAlfaSoRecebeAsObrasAQueTemCaminho() {
        String minha = obra("minha");
        String alheia = obra("alheia");
        String meuRdo = rdo(minha, "2026-07-14", "RDO-0005");
        String rdoAlheio = rdo(alheia, "2026-07-14", "RDO-0006");
        String colaboradorId = colaborador("Beta da Obra");
        vincular(colaboradorId, minha);

        List<String> ids = servicoPara(colaboradorId, false)
                .apontamentosDoPeriodo(
                        LocalDate.parse("2026-07-14"),
                        LocalDate.parse("2026-07-14")
                )
                .rdos()
                .stream()
                .map(RateioMaoDeObraResponse.RdoDoRateio::id)
                .toList();

        assertThat(ids).contains(meuRdo).doesNotContain(rdoAlheio);
    }

    @Test
    void oPeriodoInvertidoOuLongoDemaisERecusadoComoPedidoRuim() {
        RateioMaoDeObraService servico = servicoPara(alfa(), true);

        assertThatThrownBy(() -> servico.apontamentosDoPeriodo(
                LocalDate.parse("2026-07-31"),
                LocalDate.parse("2026-07-01")
        )).isInstanceOf(ResponseStatusException.class);

        assertThatThrownBy(() -> servico.apontamentosDoPeriodo(
                LocalDate.parse("2020-01-01"),
                LocalDate.parse("2030-01-01")
        )).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void oPeriodoRecortaOQueChega() {
        String obraId = obra("periodo");
        String dentro = rdo(obraId, "2026-07-15", "RDO-0007");
        String fora = rdo(obraId, "2026-08-15", "RDO-0008");

        List<String> ids = servicoPara(alfa(), true)
                .apontamentosDoPeriodo(
                        LocalDate.parse("2026-07-01"),
                        LocalDate.parse("2026-07-31")
                )
                .rdos()
                .stream()
                .map(RateioMaoDeObraResponse.RdoDoRateio::id)
                .toList();

        assertThat(ids).contains(dentro).doesNotContain(fora);
    }

    private static String alfa() {
        return colaborador("Alfa do Teste");
    }

    private static String obra(String sufixo) {
        String obraId = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO obra (id, codigo_contrato, nome) VALUES (?, ?, ?)",
                obraId,
                "RAT-" + sufixo + "-" + obraId.substring(0, 8),
                "Obra " + sufixo
        );
        return obraId;
    }

    private static String rdo(String obraId, String data, String numero) {
        String rdoId = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO rdo (id, obra_id, numero_rdo, data_rdo, status)
                VALUES (?, ?, ?, CAST(? AS date), 'ENVIADO')
                """,
                rdoId,
                obraId,
                numero + "-" + rdoId.substring(0, 8),
                data
        );
        return rdoId;
    }

    private static String colaborador(String nome) {
        String colaboradorId = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO colaborador
                    (id, banco_origem, tabela_origem, pk_origem, nome)
                VALUES (?, 'teste', 'teste', ?, ?)
                """,
                colaboradorId,
                colaboradorId,
                nome
        );
        return colaboradorId;
    }

    private static void vincular(String colaboradorId, String obraId) {
        jdbc.update(
                """
                INSERT INTO vinculo_colaborador_obra
                    (id, obra_id, colaborador_id, status)
                VALUES (?, ?, ?, 'ATIVO')
                """,
                UUID.randomUUID().toString(),
                obraId,
                colaboradorId
        );
    }

    private static void maoDeObra(
            String rdoId,
            String colaboradorId,
            String nome,
            String cargo
    ) {
        jdbc.update(
                """
                INSERT INTO rdo_mao_obra
                    (id, rdo_id, colaborador_id, nome_colaborador, cargo)
                VALUES (?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(),
                rdoId,
                colaboradorId,
                nome,
                cargo
        );
    }
}
