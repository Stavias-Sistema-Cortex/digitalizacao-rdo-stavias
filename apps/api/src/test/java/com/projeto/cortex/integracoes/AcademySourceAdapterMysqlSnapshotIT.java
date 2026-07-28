package com.projeto.cortex.integracoes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class AcademySourceAdapterMysqlSnapshotIT {

    private static final String READER_USER = "academy_reader";
    private static final String READER_PASSWORD =
            "fixture-only-reader-credential";

    @Container
    private static final MySQLContainer<?> DATABASE =
            new MySQLContainer<>("mysql:8.4")
                    .withDatabaseName("dbstavias_acad")
                    .withUsername("fixture_admin")
                    .withPassword("fixture-only-admin-credential")
                    .withEnv("MYSQL_ROOT_HOST", "%");

    @BeforeEach
    void resetSourceFixture() throws Exception {
        try (
                Connection connection = adminConnection();
                Statement statement = connection.createStatement()
        ) {
            statement.execute("DROP TABLE IF EXISTS usuarios");
            statement.execute("DROP TABLE IF EXISTS grupos");
            statement.execute("DROP TABLE IF EXISTS perfil");
            statement.execute("""
                    CREATE TABLE grupos (
                        id_grupo INT UNSIGNED PRIMARY KEY,
                        nome VARCHAR(120) NOT NULL
                    ) ENGINE=InnoDB
                    """);
            statement.execute("""
                    CREATE TABLE perfil (
                        id_perfil INT UNSIGNED PRIMARY KEY,
                        nome_perfil VARCHAR(120) NOT NULL
                    ) ENGINE=InnoDB
                    """);
            statement.execute("""
                    CREATE TABLE usuarios (
                        id_usuario INT UNSIGNED PRIMARY KEY,
                        cpf VARCHAR(32),
                        nome VARCHAR(255) NOT NULL,
                        email VARCHAR(255),
                        ativo TINYINT(1) NOT NULL,
                        id_grupo INT UNSIGNED,
                        id_perfil INT UNSIGNED,
                        criado_em DATETIME(6)
                    ) ENGINE=InnoDB
                    """);
            statement.execute("""
                    INSERT INTO grupos (id_grupo, nome)
                    VALUES (1, 'Operacional')
                    """);
            statement.execute("""
                    INSERT INTO perfil (id_perfil, nome_perfil)
                    VALUES (1, 'Colaborador')
                    """);
            statement.execute("""
                    INSERT INTO usuarios (
                        id_usuario, cpf, nome, email, ativo,
                        id_grupo, id_perfil, criado_em
                    ) VALUES
                        (10, '11144477735', 'Academy 10',
                         'academy10@example.invalid', 1, 1, 1, NOW(6)),
                        (3000000000, '90000007935', 'Academy 3000000000',
                         'academy20@example.invalid', 1, 1, 1, NOW(6)),
                        (4000000000, '52998224725', 'Academy 4000000000 original',
                         'academy30@example.invalid', 1, 1, 1, NOW(6))
                    """);
            statement.execute(
                    "DROP USER IF EXISTS '" + READER_USER + "'@'%'"
            );
            statement.execute(
                    "CREATE USER '" + READER_USER + "'@'%' IDENTIFIED BY '"
                            + READER_PASSWORD + "'"
            );
            statement.execute(
                    "GRANT SELECT ON dbstavias_acad.* TO '"
                            + READER_USER + "'@'%'"
            );
        }
    }

    @Test
    void readOnlyUserAndRepeatableReadKeepAllPagesOnOneSnapshot()
            throws Exception {
        assertReaderCannotMutateSource();

        CountDownLatch firstPageRead = new CountDownLatch(1);
        CountDownLatch sourceMutated = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        AtomicReference<PreparedStatement> paginatedStatement =
                new AtomicReference<>();

        Connection realReader = DriverManager.getConnection(
                jdbcUrl(),
                READER_USER,
                READER_PASSWORD
        );
        Connection observedReader = spy(realReader);
        doAnswer(invocation -> {
            PreparedStatement observedStatement = spy(
                    realReader.prepareStatement(
                            invocation.getArgument(0, String.class)
                    )
            );
            doAnswer(query -> {
                Object result = query.callRealMethod();
                if (executions.incrementAndGet() == 1) {
                    firstPageRead.countDown();
                    if (!sourceMutated.await(10, TimeUnit.SECONDS)) {
                        throw new SQLException(
                                "fixture mutation did not complete"
                        );
                    }
                }
                return result;
            }).when(observedStatement).executeQuery();
            paginatedStatement.set(observedStatement);
            return observedStatement;
        }).when(observedReader).prepareStatement(anyString());

        AcademySourceAdapter adapter = new AcademySourceAdapter(
                jdbcUrl(),
                READER_USER,
                READER_PASSWORD,
                () -> observedReader
        );
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (
                Connection writer = adminConnection()
        ) {
            Future<AcademyUserSnapshot> future = executor.submit(
                    () -> adapter.fetchCompleteSnapshot(2)
            );
            assertThat(firstPageRead.await(10, TimeUnit.SECONDS)).isTrue();

            writer.setAutoCommit(false);
            try (Statement statement = writer.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO usuarios (
                            id_usuario, cpf, nome, email, ativo,
                            id_grupo, id_perfil, criado_em
                        ) VALUES (
                            3500000000, '12345678909',
                            'Academy 3500000000 late',
                            'academy25@example.invalid', 1, 1, 1, NOW(6)
                        )
                        """);
                statement.executeUpdate("""
                        UPDATE usuarios
                        SET nome = 'Academy 4000000000 changed'
                        WHERE id_usuario = 4000000000
                        """);
            }
            writer.commit();
            sourceMutated.countDown();

            AcademyUserSnapshot snapshot =
                    future.get(10, TimeUnit.SECONDS);
            assertThat(snapshot.complete()).isTrue();
            assertThat(snapshot.users())
                    .extracting(
                            AcademySourceAdapter.UsuarioAcademyRecord::idUsuario
                    )
                    .containsExactly(10L, 3_000_000_000L, 4_000_000_000L);
            assertThat(snapshot.users())
                    .filteredOn(user -> user.idUsuario() == 4_000_000_000L)
                    .extracting(
                            AcademySourceAdapter.UsuarioAcademyRecord::nome
                    )
                    .containsExactly("Academy 4000000000 original");
            verify(paginatedStatement.get(), times(2)).executeQuery();
        } finally {
            sourceMutated.countDown();
            executor.shutdownNow();
        }

        try (
                Connection verification = adminConnection();
                Statement statement = verification.createStatement();
                var resultSet = statement.executeQuery("""
                        SELECT id_usuario, nome
                        FROM usuarios
                        WHERE id_usuario IN (3500000000, 4000000000)
                        ORDER BY id_usuario
                        """)
        ) {
            assertThat(resultSet.next()).isTrue();
            assertThat(Map.of(
                    "id", resultSet.getLong("id_usuario"),
                    "name", resultSet.getString("nome")
            )).containsEntry("id", 3_500_000_000L);
            assertThat(resultSet.next()).isTrue();
            assertThat(Map.of(
                    "id", resultSet.getLong("id_usuario"),
                    "name", resultSet.getString("nome")
            ))
                    .containsEntry("id", 4_000_000_000L)
                    .containsEntry(
                            "name",
                            "Academy 4000000000 changed"
                    );
        }
    }

    private void assertReaderCannotMutateSource() throws Exception {
        try (
                Connection reader = DriverManager.getConnection(
                        jdbcUrl(),
                        READER_USER,
                        READER_PASSWORD
                );
                Statement statement = reader.createStatement()
        ) {
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE usuarios
                    SET nome = 'forbidden'
                    WHERE id_usuario = 10
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("denied");
        }
    }

    private Connection adminConnection() throws Exception {
        return DriverManager.getConnection(
                jdbcUrl(),
                "root",
                DATABASE.getPassword()
        );
    }

    private String jdbcUrl() {
        String url = DATABASE.getJdbcUrl();
        String separator = url.contains("?") ? "&" : "?";
        return url
                + separator
                + "allowPublicKeyRetrieval=true&useSSL=false";
    }
}
