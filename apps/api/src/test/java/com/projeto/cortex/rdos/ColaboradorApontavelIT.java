package com.projeto.cortex.rdos;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Quem o RDO pode apontar deixou de ser "quem está autorizado nesta obra".
 *
 * <p>A lista já foi estreita duas vezes. Primeiro só lia o vínculo direto, e
 * quem entrou na obra por equipe não existia para o RDO. Depois somou as duas
 * portas — e continuava respondendo "nenhum colaborador autorizado" na obra que
 * ainda não tinha nenhuma das duas montadas, que é justamente a obra começando
 * e que mais precisa apontar.
 *
 * <p>Agora a fonte é o cadastro de pessoas, e estar na obra decide a ordem, não
 * a permissão. Quem trabalhou naquele dia trabalhou; o RDO registra o que
 * aconteceu em vez de decidir quem podia ter estado lá.
 *
 * <p>É contra banco real porque o que se prova aqui é SQL cru sobre cinco
 * tabelas — o {@code LEFT JOIN} que deixou de excluir, o {@code bool_or} que
 * marca a obra e o {@code ORDER BY} que põe a frente em cima. Erro nessa
 * consulta não aparece em teste unitário: ele aparece quando há um PostgreSQL
 * do outro lado.
 */
@Testcontainers(disabledWithoutDocker = true)
class ColaboradorApontavelIT {

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_colaborador_apontavel_it");

    private static JdbcTemplate jdbc;
    private static RdoContextService service;

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
        service = new RdoContextService(
                jdbc,
                new ObjectMapper().findAndRegisterModules(),
                new DataSourceTransactionManager(dataSource)
        );
    }

    private List<RdoContextResponse.ColaboradorContexto> apontaveis(String obraId) {
        return service.listarColaboradoresApontaveis(obraId);
    }

    private RdoContextResponse.ColaboradorContexto encontrar(
            List<RdoContextResponse.ColaboradorContexto> lista, String id
    ) {
        return lista.stream()
                .filter(item -> item.id().equals(id))
                .findFirst()
                .orElse(null);
    }

    /** O caso que a regra antiga recusava: obra sem vínculo e sem equipe. */
    @Test
    void ofereceQuemNaoTemVinculoNenhumComAObra() {
        String obraId = inserirObra("sem-vinculo");
        String deOutraFrente = inserirColaborador("Pessoa de outra frente");

        RdoContextResponse.ColaboradorContexto encontrado =
                encontrar(apontaveis(obraId), deOutraFrente);

        assertThat(encontrado).isNotNull();
        assertThat(encontrado.naObra()).isFalse();
    }

    @Test
    void marcaComoDaObraQuemTemVinculoDireto() {
        String obraId = inserirObra("vinculo-direto");
        String colaboradorId = inserirColaborador("Pessoa vinculada");
        inserirVinculo(obraId, colaboradorId, "ENCARREGADO");

        RdoContextResponse.ColaboradorContexto encontrado =
                encontrar(apontaveis(obraId), colaboradorId);

        assertThat(encontrado.naObra()).isTrue();
        assertThat(encontrado.papelNaObra()).isEqualTo("ENCARREGADO");
    }

    @Test
    void marcaComoDaObraQuemEntrouPorEquipeAlocada() {
        String obraId = inserirObra("por-equipe");
        String colaboradorId = inserirColaborador("Pessoa da equipe");
        String equipeId = inserirEquipe(obraId, "Equipe da fresagem");
        inserirMembro(equipeId, colaboradorId);
        alocarEquipe(equipeId, obraId);

        assertThat(encontrar(apontaveis(obraId), colaboradorId).naObra()).isTrue();
    }

    /*
     * Quem está nas duas portas aparece uma vez só. Sem o agrupamento, a pessoa
     * em três frentes viraria três linhas iguais, e escolher uma delas não
     * diria nada além das outras.
     */
    @Test
    void naoDuplicaQuemEstaNoVinculoENaEquipe() {
        String obraId = inserirObra("duas-portas");
        String colaboradorId = inserirColaborador("Pessoa nas duas portas");
        inserirVinculo(obraId, colaboradorId, "OPERACIONAL");
        String equipeId = inserirEquipe(obraId, "Equipe da capa");
        inserirMembro(equipeId, colaboradorId);
        alocarEquipe(equipeId, obraId);

        assertThat(apontaveis(obraId).stream()
                .filter(item -> item.id().equals(colaboradorId))
                .count()).isEqualTo(1);
    }

    /*
     * Ordem, e não filtro: procurar o ajudante da própria frente não pode
     * custar rolar por gente de outra obra.
     */
    @Test
    void poeQuemEstaNaObraAntesDeTodoOResto() {
        String obraId = inserirObra("ordem");
        // O nome começa com Z de propósito: sem o critério de obra, a ordem
        // alfabética o jogaria para o fim da lista.
        String daObra = inserirColaborador("Zuleica da frente");
        inserirVinculo(obraId, daObra, "OPERACIONAL");
        inserirColaborador("Alberto de outra obra");

        List<RdoContextResponse.ColaboradorContexto> lista = apontaveis(obraId);
        int posicaoDaObra = posicao(lista, daObra);
        assertThat(lista.get(posicaoDaObra).naObra()).isTrue();
        assertThat(lista.subList(0, posicaoDaObra))
                .allMatch(RdoContextResponse.ColaboradorContexto::naObra);
    }

    /** Desligado da empresa é a única exclusão que restou. */
    @Test
    void naoOfereceQuemSaiuDoQuadro() {
        String obraId = inserirObra("desligado");
        String inativo = inserirColaborador("Pessoa inativa");
        jdbc.update("UPDATE colaborador SET ativo = FALSE WHERE id = ?", inativo);
        String apagado = inserirColaborador("Pessoa apagada");
        jdbc.update(
                "UPDATE colaborador SET deletado_em = ? WHERE id = ?",
                LocalDateTime.of(2026, 8, 1, 12, 0), apagado
        );

        List<RdoContextResponse.ColaboradorContexto> lista = apontaveis(obraId);
        assertThat(encontrar(lista, inativo)).isNull();
        assertThat(encontrar(lista, apagado)).isNull();
    }

    /*
     * Equipe que saiu da obra deixa de marcar a obra — mas a pessoa não some da
     * lista por isso. Ela desce.
     */
    @Test
    void rebaixaQuemVeioDeEquipeJaEncerrada() {
        String obraId = inserirObra("equipe-encerrada");
        String colaboradorId = inserirColaborador("Pessoa da equipe encerrada");
        String equipeId = inserirEquipe(obraId, "Equipe encerrada");
        inserirMembro(equipeId, colaboradorId);
        alocarEquipe(equipeId, obraId);
        jdbc.update(
                "UPDATE equipe_obra SET fim_em = ?, status = 'ENCERRADO'"
                        + " WHERE equipe_id = ? AND obra_id = ?",
                LocalDateTime.of(2026, 8, 1, 12, 0), equipeId, obraId
        );

        RdoContextResponse.ColaboradorContexto encontrado =
                encontrar(apontaveis(obraId), colaboradorId);

        assertThat(encontrado).isNotNull();
        assertThat(encontrado.naObra()).isFalse();
    }

    private int posicao(
            List<RdoContextResponse.ColaboradorContexto> lista, String id
    ) {
        for (int i = 0; i < lista.size(); i++) {
            if (lista.get(i).id().equals(id)) {
                return i;
            }
        }
        throw new AssertionError("Colaborador ausente da lista: " + id);
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

    private String inserirColaborador(String nome) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem, nome, papel_acesso
                ) VALUES (?, 'academy', 'colaborador', ?, ?, 'BETA')
                """,
                id, id, nome
        );
        return id;
    }

    private void inserirVinculo(
            String obraId, String colaboradorId, String papel
    ) {
        jdbc.update(
                """
                INSERT INTO vinculo_colaborador_obra (
                    id, obra_id, colaborador_id, status, papel_na_obra
                ) VALUES (?, ?, ?, 'ATIVO', ?)
                """,
                UUID.randomUUID().toString(), obraId, colaboradorId, papel
        );
    }

    private String inserirEquipe(String obraId, String nome) {
        String autor = inserirColaborador("Autor da equipe");
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO equipe (
                    id, obra_id, obra_principal_id, nome, status,
                    criado_por, atualizado_por
                ) VALUES (?, ?, ?, ?, 'ATIVA', ?, ?)
                """,
                id, obraId, obraId, nome, autor, autor
        );
        return id;
    }

    private void inserirMembro(String equipeId, String colaboradorId) {
        String autor = inserirColaborador("Autor do membro");
        jdbc.update(
                """
                INSERT INTO equipe_membro (
                    id, equipe_id, colaborador_id, status,
                    adicionado_por, atribuido_por, atualizado_por
                ) VALUES (?, ?, ?, 'ATIVO', ?, ?, ?)
                """,
                UUID.randomUUID().toString(), equipeId, colaboradorId,
                autor, autor, autor
        );
    }

    private void alocarEquipe(String equipeId, String obraId) {
        jdbc.update(
                """
                INSERT INTO equipe_obra (
                    id, equipe_id, obra_id, status, inicio_em, atribuido_por
                ) VALUES (?, ?, ?, 'ATIVO', ?, 'fixture')
                """,
                UUID.randomUUID().toString(), equipeId, obraId,
                LocalDateTime.of(2026, 7, 1, 8, 0)
        );
    }
}
