package com.projeto.cortex.obras.mapa;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O trecho apontado por quilômetro, desenhado sobre o eixo — na API.
 *
 * <p>A derivação tinha de morar aqui, e não no aparelho: assim qualquer
 * consumidor do mapa vê o trecho, e não só a tela que a implementava. E ela é
 * de leitura — acertar o km no RDO muda o mapa na consulta seguinte, sem
 * segunda cópia da posição a envelhecer.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresqlTrechoApoiadoNoEixoIT {

    private static final LocalDate DATA = LocalDate.of(2026, 8, 10);

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("trecho_apoiado_no_eixo_it");

    private static JdbcTemplate jdbc;
    private static TrechoApoiadoNoEixo apoio;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(
                        DATABASE.getJdbcUrl(), DATABASE.getUsername(),
                        DATABASE.getPassword()
                )
                .locations("classpath:db/migration-postgresql")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(),
                DATABASE.getPassword()
        ));
        apoio = new TrechoApoiadoNoEixo(jdbc, new ObjectMapper());
    }

    @Test
    void desenhaOApontamentoQuePassouSoPeloQuilometro() {
        String obraId = cenario("APOIO-BASICO");
        apontamento(obraId, "102", "104", "Fresagem");

        List<ObraGeometriaResponse> resultado =
                apoio.projetarEm(obraId, List.of(eixo(obraId)));

        assertThat(resultado).hasSize(2);
        ObraGeometriaResponse derivada = resultado.get(1);
        assertThat(derivada.categoria()).isEqualTo("TRECHO");
        assertThat(derivada.objetoTipo()).isEqualTo("RDO");
        assertThat(derivada.properties())
                .containsEntry(TrechoApoiadoNoEixo.PROPRIEDADE_DERIVADA, true)
                .containsEntry("servico", "Fresagem");
        assertThat(derivada.geometry().path("type").asText())
                .isEqualTo("LineString");
        // Eixo reto de 1 grau por quilômetro: o km 102 cai em 0,4 e o 104 em 0,8.
        assertThat(
                derivada.geometry().path("coordinates").get(0).get(0).asDouble()
        ).isCloseTo(0.4, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void semEixoNaoInventaPosicaoNenhuma() {
        String obraId = cenario("APOIO-SEM-EIXO");
        apontamento(obraId, "102", "104", "Fresagem");

        assertThat(apoio.projetarEm(obraId, List.of())).isEmpty();
    }

    @Test
    void oApontamentoComUmExtremoSoNaoEPosicionavel() {
        String obraId = cenario("APOIO-UM-EXTREMO");
        apontamento(obraId, "102", null, "Fresagem");

        assertThat(apoio.projetarEm(obraId, List.of(eixo(obraId)))).hasSize(1);
    }

    /*
     * O desenho é a posição declarada por quem estava lá. Sobrepor a ela uma
     * linha derivada mostraria o mesmo trabalho duas vezes, em lugares
     * levemente diferentes.
     */
    @Test
    void naoRepeteOTrechoQueJaTemDesenhoProprio() {
        String obraId = cenario("APOIO-JA-DESENHADO");
        String rdoId = apontamento(obraId, "102", "104", "Fresagem");

        List<ObraGeometriaResponse> comDesenho = List.of(
                eixo(obraId),
                new ObraGeometriaResponse(
                        id(), "TRECHO", "RDO", rdoId,
                        linha(), Map.of(), "GESTAO_MAPA", "ATIVA",
                        DATA.atStartOfDay(), null, null, 1L,
                        null, null, DATA.atStartOfDay(), DATA.atStartOfDay()
                )
        );

        assertThat(apoio.projetarEm(obraId, comDesenho)).hasSize(2);
    }

    /*
     * Apagar o RDO marca `rdo.cancelado_em` e não toca nas linhas de execução.
     * Sem atravessar o RDO, o dia apagado seguiria desenhado no mapa.
     */
    @Test
    void oRdoApagadoSaiDoMapa() {
        String obraId = cenario("APOIO-RDO-APAGADO");
        String rdoId = apontamento(obraId, "102", "104", "Fresagem");

        assertThat(apoio.projetarEm(obraId, List.of(eixo(obraId)))).hasSize(2);

        jdbc.update("""
                UPDATE rdo
                SET status = 'CANCELADA', cancelado_em = CURRENT_TIMESTAMP(6)
                WHERE id = ?
                """, rdoId);

        assertThat(apoio.projetarEm(obraId, List.of(eixo(obraId)))).hasSize(1);
    }

    /** Obra com colaborador, pronta para receber RDO. */
    private String cenario(String sufixo) {
        String obraId = id();
        jdbc.update(
                "INSERT INTO obra (id, codigo_contrato, nome) VALUES (?, ?, ?)",
                obraId, sufixo, "Obra " + sufixo
        );
        return obraId;
    }

    /** Uma linha de serviço com quilômetro, no RDO do dia. */
    private String apontamento(
            String obraId,
            String kmInicial,
            String kmFinal,
            String servico
    ) {
        String rdoId = id();
        jdbc.update(
                "INSERT INTO rdo (id, obra_id, numero_rdo, data_rdo) VALUES (?, ?, ?, ?)",
                rdoId, obraId, "RDO-" + rdoId.substring(0, 8), DATA
        );
        String execucaoId = id();
        jdbc.update("""
                INSERT INTO execucao_servico_rdo (
                    id, rdo_id, obra_id, servico_nome, quantidade_executada,
                    unidade_medida, trecho_inicial, trecho_final, data_execucao,
                    fonte, chave_execucao
                ) VALUES (?, ?, ?, ?, 10.000, 'M2', ?, ?, ?, 'IT', ?)
                """, execucaoId, rdoId, obraId, servico, kmInicial, kmFinal,
                DATA, chave(execucaoId));
        return rdoId;
    }

    /** Eixo reto sobre o equador: 1 grau de longitude por quilômetro. */
    private ObraGeometriaResponse eixo(String obraId) {
        return new ObraGeometriaResponse(
                "eixo-" + obraId,
                TrechoApoiadoNoEixo.CATEGORIA_EIXO,
                "OBRA",
                obraId,
                linha(),
                Map.of("kmInicial", 100, "kmFinal", 110),
                "GESTAO_MAPA",
                "ATIVA",
                LocalDateTime.of(2026, 8, 1, 12, 0),
                null,
                null,
                1L,
                null,
                null,
                LocalDateTime.of(2026, 8, 1, 12, 0),
                LocalDateTime.of(2026, 8, 1, 12, 0)
        );
    }

    private com.fasterxml.jackson.databind.JsonNode linha() {
        ObjectMapper mapper = new ObjectMapper();
        try {
            return mapper.readTree("""
                    {"type":"LineString","coordinates":[[0,0],[1,0],[2,0]]}
                    """);
        } catch (Exception erro) {
            throw new IllegalStateException(erro);
        }
    }

    private static String chave(String semente) {
        return "0".repeat(56) + String.format("%08x", semente.hashCode());
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
