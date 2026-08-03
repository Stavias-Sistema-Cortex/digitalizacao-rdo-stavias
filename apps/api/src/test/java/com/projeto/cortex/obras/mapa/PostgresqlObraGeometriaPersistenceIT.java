package com.projeto.cortex.obras.mapa;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A geometria gravada pelo mesmo Hibernate da produção, contra o mesmo banco.
 *
 * <p>Este teste existe porque a coluna jsonb recusada em silêncio já entalou a
 * fila de campo uma vez: o campo String da entidade era bindado como VARCHAR, o
 * PostgreSQL recusava a conversão implícita, e cada marcação de geometria feita
 * offline morria numa retentativa infinita — com o CI verde, porque o caminho
 * só era exercitado contra MySQL, que aceita string em coluna JSON. Um teste
 * que grava pela entidade real contra PostgreSQL real é o que faltava para o
 * defeito não conseguir voltar.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresqlObraGeometriaPersistenceIT {

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("obra_geometria_persistence");

    private static SessionFactory sessionFactory;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateAndBootHibernate() {
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

        Configuration configuration = new Configuration()
                .addAnnotatedClass(ObraGeometria.class);
        configuration.setProperty(
                "hibernate.connection.url", DATABASE.getJdbcUrl());
        configuration.setProperty(
                "hibernate.connection.username", DATABASE.getUsername());
        configuration.setProperty(
                "hibernate.connection.password", DATABASE.getPassword());
        // O schema vem do Flyway, como na produção; o Hibernate só grava nele.
        configuration.setProperty("hibernate.hbm2ddl.auto", "none");
        sessionFactory = configuration.buildSessionFactory();
    }

    @AfterAll
    static void closeHibernate() {
        if (sessionFactory != null) {
            sessionFactory.close();
        }
    }

    @Test
    void persisteGeometriaEPropriedadesNaColunaJsonb() {
        String obraId = UUID.randomUUID().toString();
        String actorId = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO obra (id, codigo_contrato, nome) VALUES (?, ?, ?)",
                obraId,
                "CTR-" + obraId.substring(0, 8),
                "Obra geometria"
        );
        jdbc.update("""
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem,
                    codigo_colaborador, nome, papel_acesso, ativo
                ) VALUES (?, 'geo-it', 'colaborador', ?, ?, 'Atriz', 'BETA', TRUE)
                """, actorId, actorId, actorId.substring(0, 8));

        ObraGeometria feature = ObraGeometria.criar(
                null,
                obraId,
                "TRECHO",
                null,
                null,
                "LINESTRING",
                "{\"type\":\"LineString\",\"coordinates\":[[-47.5,-22.4],[-47.6,-22.5]]}",
                "{\"origem\":\"campo\"}",
                "APONTAMENTO",
                LocalDateTime.of(2026, 8, 1, 8, 0),
                actorId
        );

        sessionFactory.inTransaction(session -> session.persist(feature));

        assertThat(jdbc.queryForObject("""
                SELECT geometria_json ->> 'type'
                FROM obra_geometria
                WHERE obra_id = ?
                """, String.class, obraId))
                .isEqualTo("LineString");
        assertThat(jdbc.queryForObject("""
                SELECT propriedades_json ->> 'origem'
                FROM obra_geometria
                WHERE obra_id = ?
                """, String.class, obraId))
                .isEqualTo("campo");
    }
}
