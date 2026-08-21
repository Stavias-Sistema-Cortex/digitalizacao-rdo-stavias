package com.projeto.cortex.obras.usinados;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.obras.usinados.ObraUsinadosResponse.MaterialUsinado;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Os totais de usinagem contra um PostgreSQL de verdade.
 *
 * <p>A agregação e o casamento de preço são SQL, e SQL só se confere no banco:
 * o RDO cancelado que não pode contar, o mesmo material grafado em caixas
 * diferentes que precisa virar uma linha só, o preço vigente que casa por nome
 * e unidade — exatamente um —, e o financeiro invisível para quem não tem a
 * permissão da obra.</p>
 *
 * <p>Mesma infraestrutura da simulação de sincronização: Testcontainers com
 * Docker, ou um cluster apontado por {@code CORTEX_SYNC_IT_JDBC_URL}.</p>
 */
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class PostgresqlObraUsinadosIT {

    private static final String USER = "10000000-0000-4000-8000-000000000042";
    private static final LocalDate HOJE = LocalDate.of(2026, 8, 21);

    private static PostgreSQLContainer<?> container;
    private static JdbcTemplate jdbc;
    private static FinancialAccessService access;
    private static ObraUsinadosService service;

    @BeforeAll
    static void boot() {
        String externalUrl = System.getenv("CORTEX_SYNC_IT_JDBC_URL");
        String url;
        String user;
        String password;
        if (externalUrl != null && !externalUrl.isBlank()) {
            url = externalUrl.replaceFirst(
                    "/[^/]*$",
                    "/cortex_usinados_it"
            );
            user = System.getenv()
                    .getOrDefault("CORTEX_SYNC_IT_JDBC_USER", "postgres");
            password = System.getenv()
                    .getOrDefault("CORTEX_SYNC_IT_JDBC_PASSWORD", "postgres");
            criarBancoSePreciso(externalUrl, user, password);
        } else {
            assumeTrue(
                    dockerDisponivel(),
                    "Sem Docker e sem CORTEX_SYNC_IT_JDBC_URL: teste pulado."
            );
            container = new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_usinados_it");
            container.start();
            url = container.getJdbcUrl();
            user = container.getUsername();
            password = container.getPassword();
        }

        Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration-postgresql")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, user, password));

        CurrentUserService currentUser = mock(CurrentUserService.class);
        when(currentUser.requireUserId()).thenReturn(USER);
        access = mock(FinancialAccessService.class);
        service = new ObraUsinadosService(jdbc, currentUser, access);
    }

    @AfterAll
    static void stop() {
        if (container != null) {
            container.stop();
        }
    }

    private static void criarBancoSePreciso(
            String adminUrl,
            String user,
            String password
    ) {
        JdbcTemplate admin = new JdbcTemplate(
                new DriverManagerDataSource(adminUrl, user, password)
        );
        Integer existe = admin.queryForObject(
                "SELECT COUNT(*) FROM pg_database WHERE datname = 'cortex_usinados_it'",
                Integer.class
        );
        if (existe == null || existe == 0) {
            admin.execute("CREATE DATABASE cortex_usinados_it");
        }
    }

    private static boolean dockerDisponivel() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    @Test
    void agregaODeclaradoCasaPrecoENuncaInventaValor() {
        when(access.hasPermission(anyString(), anyString(), any()))
                .thenReturn(true);
        String obraId = criarObra();
        String autor = criarColaborador();

        // Catálogo: CBUQ com um preço vigente; Areia com dois serviços de
        // mesmo nome (ambíguo); Brita sem preço nenhum.
        String cbuq = criarServico(autor, obraId, "CBUQ-C", "CBUQ Faixa C");
        criarPreco(autor, obraId, cbuq, "T", new BigDecimal("850.0000"));
        String areia1 = criarServico(autor, obraId, "AREIA-1", "Areia");
        String areia2 = criarServico(autor, obraId, "AREIA-2", "Areia");
        criarPreco(autor, obraId, areia1, "M3", new BigDecimal("120.0000"));
        criarPreco(autor, obraId, areia2, "M3", new BigDecimal("110.0000"));

        String rdo1 = criarRdo(obraId, "RDO-0001", LocalDate.of(2026, 8, 18), null);
        String rdo2 = criarRdo(obraId, "RDO-0002", LocalDate.of(2026, 8, 19), null);
        String cancelado = criarRdo(
                obraId, "RDO-0003", LocalDate.of(2026, 8, 20), HOJE.atStartOfDay()
        );

        // O mesmo material em caixas diferentes soma numa linha só.
        criarMaterial(rdo1, "CBUQ Faixa C", "T", "100", "90", "80", "10");
        criarMaterial(rdo2, "CBUQ FAIXA C", "T", "50", "60", "55", "5");
        // O RDO cancelado não conta.
        criarMaterial(cancelado, "CBUQ Faixa C", "T", "999", "999", "999", "999");
        // Aplicado acima do previsto: "não aplicado" não fica negativo.
        criarMaterial(rdo1, "Areia", "M3", "10", null, "12", null);
        // Sem preço no catálogo: quantidade sim, dinheiro não.
        criarMaterial(rdo2, "Brita 3/4", "M3", null, "40", "38", "2");

        ObraUsinadosResponse resposta = service.buscarUsinados(obraId, HOJE);

        assertThat(resposta.precosVisiveis()).isTrue();
        assertThat(resposta.materiais()).hasSize(3);

        MaterialUsinado cbuqLinha = linha(resposta, "cbuq faixa c");
        assertThat(cbuqLinha.unidade()).isEqualTo("T");
        assertThat(cbuqLinha.quantidadePrevista())
                .isEqualByComparingTo("150");
        assertThat(cbuqLinha.quantidadeUsinada()).isEqualByComparingTo("150");
        assertThat(cbuqLinha.quantidadeAplicada()).isEqualByComparingTo("135");
        assertThat(cbuqLinha.quantidadeSobra()).isEqualByComparingTo("15");
        assertThat(cbuqLinha.quantidadeNaoAplicada())
                .isEqualByComparingTo("15");
        assertThat(cbuqLinha.totalRdos()).isEqualTo(2);
        assertThat(cbuqLinha.primeiraData())
                .isEqualTo(LocalDate.of(2026, 8, 18));
        assertThat(cbuqLinha.ultimaData())
                .isEqualTo(LocalDate.of(2026, 8, 19));
        assertThat(cbuqLinha.precoUnitario())
                .isEqualByComparingTo("850.0000");
        assertThat(cbuqLinha.valorAplicado())
                .isEqualByComparingTo("114750.00");
        assertThat(cbuqLinha.valorDesperdicado())
                .isEqualByComparingTo("12750.00");
        assertThat(cbuqLinha.precoMotivo()).isNull();

        MaterialUsinado areiaLinha = linha(resposta, "areia");
        assertThat(areiaLinha.quantidadeNaoAplicada())
                .isEqualByComparingTo("0");
        assertThat(areiaLinha.quantidadeUsinada()).isNull();
        assertThat(areiaLinha.precoMotivo())
                .isEqualTo(ObraUsinadosResponse.PRECO_AMBIGUO);
        assertThat(areiaLinha.valorAplicado()).isNull();

        MaterialUsinado britaLinha = linha(resposta, "brita 3/4");
        assertThat(britaLinha.quantidadePrevista()).isNull();
        assertThat(britaLinha.quantidadeNaoAplicada()).isNull();
        assertThat(britaLinha.precoMotivo())
                .isEqualTo(ObraUsinadosResponse.SEM_PRECO);
        assertThat(britaLinha.valorDesperdicado()).isNull();

        assertThat(resposta.totais()).isNotNull();
        assertThat(resposta.totais().valorAplicado())
                .isEqualByComparingTo("114750.00");
        assertThat(resposta.totais().valorDesperdicado())
                .isEqualByComparingTo("12750.00");
        assertThat(resposta.totais().materiaisSemPreco()).isEqualTo(2);
    }

    @Test
    void semPermissaoFinanceiraEntregaQuantidadesESilenciaDinheiro() {
        when(access.hasPermission(anyString(), anyString(), any()))
                .thenReturn(false);
        String obraId = criarObra();
        String autor = criarColaborador();
        String cbuq = criarServico(autor, obraId, "CBUQ-X", "CBUQ Faixa X");
        criarPreco(autor, obraId, cbuq, "T", new BigDecimal("900.0000"));
        String rdo = criarRdo(obraId, "RDO-0004", LocalDate.of(2026, 8, 19), null);
        criarMaterial(rdo, "CBUQ Faixa X", "T", "10", "9", "8", "1");

        ObraUsinadosResponse resposta = service.buscarUsinados(obraId, HOJE);

        assertThat(resposta.precosVisiveis()).isFalse();
        assertThat(resposta.totais()).isNull();
        assertThat(resposta.materiais()).hasSize(1);
        MaterialUsinado material = resposta.materiais().getFirst();
        assertThat(material.quantidadeAplicada()).isEqualByComparingTo("8");
        assertThat(material.precoUnitario()).isNull();
        assertThat(material.precoMotivo()).isNull();
        assertThat(material.valorAplicado()).isNull();
        assertThat(material.valorDesperdicado()).isNull();
    }

    private static MaterialUsinado linha(
            ObraUsinadosResponse resposta,
            String materialNormalizado
    ) {
        return resposta.materiais().stream()
                .filter(item -> item.material()
                        .toLowerCase(java.util.Locale.ROOT)
                        .equals(materialNormalizado))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Material não encontrado: " + materialNormalizado
                ));
    }

    private static String criarObra() {
        String obraId = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO obra (id, codigo_contrato, nome) VALUES (?, ?, ?)",
                obraId,
                "USI-" + obraId.substring(0, 8),
                "Obra usinados"
        );
        return obraId;
    }

    private static String criarColaborador() {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO colaborador (id, banco_origem, tabela_origem, pk_origem, nome)
                VALUES (?, 'simulacao', 'simulacao', ?, 'Autor de catálogo')
                """,
                id,
                id
        );
        return id;
    }

    private static String criarServico(
            String autor,
            String obraId,
            String codigo,
            String nome
    ) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO catalogo_servico (
                    id, codigo, nome, status, obra_autorizadora_id, criado_por
                ) VALUES (?, ?, ?, 'ACTIVE', ?, ?)
                """,
                id,
                codigo + "-" + id.substring(0, 8).toUpperCase(java.util.Locale.ROOT),
                nome,
                obraId,
                autor
        );
        return id;
    }

    private static void criarPreco(
            String autor,
            String obraId,
            String serviceId,
            String unidade,
            BigDecimal valorUnitario
    ) {
        jdbc.update(
                """
                INSERT INTO service_price_version (
                    id, obra_id, service_id, unidade, moeda, versao,
                    valor_unitario, vigencia_inicio, fonte, criado_por
                ) VALUES (?, ?, ?, ?, 'BRL', 1, ?, DATE '2026-01-01', 'MANUAL', ?)
                """,
                UUID.randomUUID().toString(),
                obraId,
                serviceId,
                unidade,
                valorUnitario,
                autor
        );
    }

    private static String criarRdo(
            String obraId,
            String numero,
            LocalDate data,
            java.time.LocalDateTime canceladoEm
    ) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO rdo (id, obra_id, numero_rdo, data_rdo, status, cancelado_em)
                VALUES (?, ?, ?, ?, 'APROVADA', ?)
                """,
                id,
                obraId,
                numero + "-" + id.substring(0, 6),
                data,
                canceladoEm
        );
        return id;
    }

    private static void criarMaterial(
            String rdoId,
            String nome,
            String unidade,
            String prevista,
            String usinada,
            String aplicada,
            String sobra
    ) {
        jdbc.update(
                """
                INSERT INTO rdo_material (
                    id, rdo_id, material_nome, unidade,
                    quantidade_prevista, quantidade_usinada,
                    quantidade_aplicada, quantidade_sobra
                ) VALUES (?, ?, ?, ?, ?::numeric, ?::numeric, ?::numeric, ?::numeric)
                """,
                UUID.randomUUID().toString(),
                rdoId,
                nome,
                unidade,
                prevista,
                usinada,
                aplicada,
                sobra
        );
    }
}
