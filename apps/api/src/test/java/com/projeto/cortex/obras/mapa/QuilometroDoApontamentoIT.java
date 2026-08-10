package com.projeto.cortex.obras.mapa;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * O trecho no mapa precisa dizer de que quilômetro a que quilômetro ele fala.
 *
 * <p>A geometria guarda a forma e nada mais — decisão deliberada, porque o
 * quilômetro existia em dois lugares sem nada que os reconciliasse, e corrigir
 * num deixava o outro mentindo. Ele passou a morar na linha de execução do RDO.
 *
 * <p>Só que ninguém fechou o outro lado: a resposta do mapa devolve o que está
 * gravado em {@code propriedades_json}, então o quilômetro nunca chegava à
 * tela. O trecho aparecia desenhado sem a primeira informação que se procura
 * olhando para uma rodovia.
 *
 * <p>É contra banco real porque o que se prova aqui são as duas consultas: a
 * ligação por {@code = ANY(?)} e, sobretudo, o {@code HAVING COUNT(*) = 1} que
 * impede o mapa de chutar qual frente do dia o desenho representa.
 */
@Testcontainers(disabledWithoutDocker = true)
class QuilometroDoApontamentoIT {

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_mapa_km_it");

    private static JdbcTemplate jdbc;
    private static QuilometroDoApontamento leitura;

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
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()
        ));
        leitura = new QuilometroDoApontamento(jdbc);
    }

    @Test
    void encontraOQuilometroDaLinhaQueODesenhoRepresenta() {
        String obraId = inserirObra("linha");
        String rdoId = inserirRdo(obraId, "RDO-0001");
        String execucaoId = inserirExecucao(rdoId, obraId, "206,822", "207,100");

        Map<String, QuilometroDoApontamento.Quilometragem> encontrado =
                leitura.porExecucao(Set.of(execucaoId));

        assertThat(encontrado.get(execucaoId))
                .isEqualTo(new QuilometroDoApontamento.Quilometragem(
                        "206,822", "207,100"
                ));
    }

    @Test
    void ignoraALinhaCancelada() {
        String obraId = inserirObra("cancelada");
        String rdoId = inserirRdo(obraId, "RDO-0002");
        String execucaoId = inserirExecucao(rdoId, obraId, "100", "101");
        jdbc.update(
                "UPDATE execucao_servico_rdo SET cancelada = true WHERE id = ?",
                execucaoId
        );

        assertThat(leitura.porExecucao(Set.of(execucaoId))).isEmpty();
    }

    @Test
    void ignoraALinhaSemQuilometroDeclarado() {
        String obraId = inserirObra("sem-km");
        String rdoId = inserirRdo(obraId, "RDO-0003");
        String execucaoId = inserirExecucao(rdoId, obraId, null, null);

        assertThat(leitura.porExecucao(Set.of(execucaoId))).isEmpty();
    }

    /**
     * O caminho do desenho antigo, feito antes de a geometria dizer qual linha
     * ela representa: vale o RDO, e só quando ele não deixa dúvida.
     */
    @Test
    void usaORdoQuandoEleTemUmaFrenteSo() {
        String obraId = inserirObra("uma-frente");
        String rdoId = inserirRdo(obraId, "RDO-0004");
        inserirExecucao(rdoId, obraId, "300", "302,500");

        assertThat(leitura.unicaPorRdo(Set.of(rdoId)).get(rdoId))
                .isEqualTo(new QuilometroDoApontamento.Quilometragem(
                        "300", "302,500"
                ));
    }

    /*
     * A regra que impede o mapa de mentir: com duas frentes no mesmo dia não há
     * como saber qual delas o desenho representa, e chutar escreveria na tela
     * um quilômetro que pertence a outro trabalho.
     */
    @Test
    void naoChutaQuandoORdoTemMaisDeUmaFrente() {
        String obraId = inserirObra("duas-frentes");
        String rdoId = inserirRdo(obraId, "RDO-0005");
        inserirExecucao(rdoId, obraId, "300", "302");
        inserirExecucao(rdoId, obraId, "410", "412");

        assertThat(leitura.unicaPorRdo(Set.of(rdoId))).isEmpty();
    }

    /*
     * A frente sem quilômetro não conta para a contagem: um dia com uma linha
     * de quilômetro e outra sem continua sendo um dia sem dúvida.
     */
    @Test
    void aFrenteSemQuilometroNaoCriaAmbiguidade() {
        String obraId = inserirObra("uma-com-km");
        String rdoId = inserirRdo(obraId, "RDO-0006");
        inserirExecucao(rdoId, obraId, "500", "501");
        inserirExecucao(rdoId, obraId, null, null);

        assertThat(leitura.unicaPorRdo(Set.of(rdoId)).get(rdoId))
                .isEqualTo(new QuilometroDoApontamento.Quilometragem("500", "501"));
    }

    @Test
    void naoConsultaNadaQuandoNaoHaTrechoDeQueFalar() {
        assertThat(leitura.porExecucao(Set.of())).isEmpty();
        assertThat(leitura.unicaPorRdo(Set.of())).isEmpty();
        assertThat(leitura.projetarEm(List.of())).isEmpty();
    }

    private String inserirObra(String sufixo) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO obra (id, codigo_contrato, nome, status)
                VALUES (?, ?, ?, 'ATIVA')
                """,
                id, "CTR-" + id, "Obra " + sufixo
        );
        return id;
    }

    private String inserirRdo(String obraId, String numero) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO rdo (id, obra_id, numero_rdo, data_rdo, status_rdo)
                VALUES (?, ?, ?, ?, 'RASCUNHO')
                """,
                id, obraId, numero, java.sql.Date.valueOf(LocalDate.of(2026, 8, 10))
        );
        return id;
    }

    private String inserirExecucao(
            String rdoId, String obraId, String inicial, String finalKm
    ) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO execucao_servico_rdo (
                    id, rdo_id, obra_id, servico_nome, quantidade_executada,
                    unidade_medida, trecho_inicial, trecho_final, data_execucao,
                    chave_execucao
                ) VALUES (?, ?, ?, 'Fresagem', 1, 'M2', ?, ?, ?, ?)
                """,
                id, rdoId, obraId, inicial, finalKm,
                java.sql.Date.valueOf(LocalDate.of(2026, 8, 10)),
                id.replace("-", "") + "0".repeat(64 - id.replace("-", "").length())
        );
        return id;
    }
}
