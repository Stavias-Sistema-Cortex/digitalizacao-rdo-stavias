package com.projeto.cortex.obras.mapa;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
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
 * O RDO manda no quilômetro do eixo, e a gravação disso mora no banco.
 *
 * <p>O eixo é a régua da obra: uma linha só, sobre a qual todo apontamento é
 * projetado para virar trecho no mapa. O quilômetro mora nas propriedades dessa
 * linha, em jsonb, e a reescrita precisa trocar as duas pontas sem tocar na
 * geometria, na vigência nem no resto das propriedades. Nada disso é
 * verificável fora de um PostgreSQL de verdade — {@code jsonb_set} é do banco.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresqlQuilometroDoEixoIT {

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_km_eixo_it");

    private static JdbcTemplate jdbc;
    private static QuilometroDoEixo quilometroDoEixo;
    private static String colaboradorId;

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
        quilometroDoEixo = new QuilometroDoEixo(jdbc, new ObjectMapper());
        colaboradorId = id();
        jdbc.update(
                """
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem, nome
                ) VALUES (?, 'teste', 'colaborador', ?, 'Apontador')
                """,
                colaboradorId,
                colaboradorId
        );
    }

    @Test
    void oQuilometroDeclaradoPeloEixoEhOQueOContextoSugere() {
        String obraId = obra("sugere");
        eixo(obraId, "{\"kmInicial\": 206.822, \"kmFinal\": 214.5}");

        QuilometroDoEixo.Faixa faixa = quilometroDoEixo.faixaVigente(obraId);

        assertThat(faixa).isNotNull();
        assertThat(faixa.declarada()).isTrue();
        assertThat(faixa.kmInicial()).isEqualByComparingTo("206.822");
        assertThat(faixa.kmFinal()).isEqualByComparingTo("214.5");
    }

    @Test
    void semEixoNaoHaOQueSugerir() {
        String obraId = obra("sem-eixo");

        assertThat(quilometroDoEixo.faixaVigente(obraId)).isNull();
    }

    /*
     * A linha pode ser desenhada antes de alguém saber a estaca. Nesse estado
     * ela não serve de régua, e o RDO novo nasce com o campo em branco em vez
     * de com zero.
     */
    @Test
    void eixoSemQuilometroNaoDeclaraNada() {
        String obraId = obra("sem-km");
        eixo(obraId, "{}");

        QuilometroDoEixo.Faixa faixa = quilometroDoEixo.faixaVigente(obraId);

        assertThat(faixa).isNotNull();
        assertThat(faixa.declarada()).isFalse();
    }

    @Test
    void oRdoReescreveAsDuasPontasSemTocarNoResto() {
        String obraId = obra("reescreve");
        eixo(
                obraId,
                "{\"kmInicial\": 206.822, \"kmFinal\": 214.5, \"rotulo\": \"Anhanguera\"}"
        );
        String geometriaAntes = geometria(obraId);

        boolean mudou = quilometroDoEixo.reescrever(
                obraId,
                new BigDecimal("300.000"),
                new BigDecimal("312.750")
        );

        assertThat(mudou).isTrue();
        QuilometroDoEixo.Faixa depois = quilometroDoEixo.faixaVigente(obraId);
        assertThat(depois.kmInicial()).isEqualByComparingTo("300.000");
        assertThat(depois.kmFinal()).isEqualByComparingTo("312.750");
        // O que não é quilômetro fica onde estava.
        assertThat(propriedades(obraId)).contains("Anhanguera");
        assertThat(geometria(obraId)).isEqualTo(geometriaAntes);
    }

    /*
     * Reescrever com o mesmo número não é mudança. Sem isso, cada salvamento de
     * RDO marcaria a geometria como alterada e a ontologia teria de contar uma
     * história que não aconteceu.
     */
    @Test
    void reescreverComOMesmoQuilometroNaoMudaNada() {
        String obraId = obra("idempotente");
        eixo(obraId, "{\"kmInicial\": 100, \"kmFinal\": 110}");

        assertThat(quilometroDoEixo.reescrever(
                obraId, new BigDecimal("100"), new BigDecimal("110")
        )).isFalse();
    }

    /*
     * Um RDO salvo sem trecho interditado não está dizendo que a obra encolheu:
     * está dizendo que ninguém preencheu aquele campo. Meia régua é pior do que
     * a régua antiga.
     */
    @Test
    void quilometroIncompletoNaoApagaARegua() {
        String obraId = obra("incompleto");
        eixo(obraId, "{\"kmInicial\": 100, \"kmFinal\": 110}");

        assertThat(quilometroDoEixo.reescrever(
                obraId, null, new BigDecimal("110")
        )).isFalse();
        assertThat(quilometroDoEixo.faixaVigente(obraId).kmInicial())
                .isEqualByComparingTo("100");
    }

    @Test
    void semEixoCadastradoNaoHaOQueReescrever() {
        String obraId = obra("nada-para-reescrever");

        assertThat(quilometroDoEixo.reescrever(
                obraId, new BigDecimal("1"), new BigDecimal("2")
        )).isFalse();
    }

    /*
     * Eixo encerrado saiu de vigência. Reescrevê-lo ressuscitaria uma régua que
     * a obra já aposentou.
     */
    @Test
    void eixoEncerradoNaoRecebeQuilometro() {
        String obraId = obra("encerrado");
        String eixoId = eixo(obraId, "{\"kmInicial\": 100, \"kmFinal\": 110}");
        jdbc.update(
                "UPDATE obra_geometria SET valido_ate = ?, status = 'ENCERRADA' WHERE id = ?",
                LocalDateTime.now(),
                eixoId
        );

        assertThat(quilometroDoEixo.reescrever(
                obraId, new BigDecimal("300"), new BigDecimal("312")
        )).isFalse();
        assertThat(quilometroDoEixo.faixaVigente(obraId)).isNull();
    }

    private static String obra(String suffix) {
        String obraId = id();
        jdbc.update(
                "INSERT INTO obra (id, codigo_contrato, nome) VALUES (?, ?, ?)",
                obraId,
                "EIXO-" + suffix,
                "Obra eixo " + suffix
        );
        return obraId;
    }

    private static String eixo(String obraId, String propriedadesJson) {
        String eixoId = id();
        jdbc.update(
                """
                INSERT INTO obra_geometria (
                    id, obra_id, categoria, tipo_geometria,
                    geometria_json, propriedades_json,
                    fonte, status, valido_desde,
                    criado_por, atualizado_por, criado_em, atualizado_em
                ) VALUES (
                    ?, ?, ?, 'LINESTRING',
                    CAST(? AS jsonb), CAST(? AS jsonb),
                    'GESTAO_MAPA', 'ATIVA', ?,
                    ?, ?, ?, ?
                )
                """,
                eixoId,
                obraId,
                TrechoApoiadoNoEixo.CATEGORIA_EIXO,
                "{\"type\":\"LineString\",\"coordinates\":[[-47.4,-22.0],[-47.3,-21.9]]}",
                propriedadesJson,
                LocalDateTime.now(),
                colaboradorId,
                colaboradorId,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
        return eixoId;
    }

    private static String propriedades(String obraId) {
        return jdbc.queryForObject(
                """
                SELECT propriedades_json::text FROM obra_geometria
                WHERE obra_id = ? AND categoria = ? AND valido_ate IS NULL
                """,
                String.class,
                obraId,
                TrechoApoiadoNoEixo.CATEGORIA_EIXO
        );
    }

    private static String geometria(String obraId) {
        return jdbc.queryForObject(
                """
                SELECT geometria_json::text FROM obra_geometria
                WHERE obra_id = ? AND categoria = ? AND valido_ate IS NULL
                """,
                String.class,
                obraId,
                TrechoApoiadoNoEixo.CATEGORIA_EIXO
        );
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
