package com.projeto.cortex.integracoes;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class ZeladoriaSourceAdapterMysqlSnapshotIT {

    @Container
    private static final MySQLContainer<?> DATABASE =
            new MySQLContainer<>("mysql:8.4")
                    .withDatabaseName("dbstavias_zld")
                    .withUsername("fixture_admin")
                    .withPassword("fixture-only-admin-credential")
                    .withEnv("MYSQL_ROOT_HOST", "%")
                    .withInitScript("zeladoria-source-init.sql");

    @Test
    void realMysqlReturnsEveryPageWithoutMutatingSource()
            throws Exception {
        ZeladoriaAssetSnapshot snapshot = new ZeladoriaSourceAdapter(
                jdbcUrl(),
                DATABASE.getUsername(),
                DATABASE.getPassword()
        ).fetchCompleteSnapshot(2);

        assertThat(snapshot.complete()).isTrue();
        assertThat(snapshot.assets())
                .extracting(ZeladoriaSourceAdapter.AtivoZeladoriaRecord::id)
                .containsExactly("1", "2", "3");
        try (
                Connection admin = adminConnection();
                Statement statement = admin.createStatement();
                var result = statement.executeQuery(
                        "SELECT modelo FROM ativos WHERE id = 1"
                )
        ) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("Modelo A");
        }
    }

    private Connection adminConnection() throws Exception {
        return DriverManager.getConnection(
                jdbcUrl(),
                DATABASE.getUsername(),
                DATABASE.getPassword()
        );
    }

    private String jdbcUrl() {
        String separator = DATABASE.getJdbcUrl().contains("?") ? "&" : "?";
        return DATABASE.getJdbcUrl()
                + separator
                + "useSSL=false&allowPublicKeyRetrieval=true";
    }
}
