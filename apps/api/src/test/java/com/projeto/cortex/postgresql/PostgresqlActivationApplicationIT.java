package com.projeto.cortex.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import com.projeto.cortex.auth.postgresql.PostgresqlAuthPersistenceTestSupport;
import com.projeto.cortex.common.PostgresqlActivationReadinessController;
import com.projeto.cortex.common.ReadinessController;
import com.projeto.cortex.postgresql.activation.PostgresqlActivationApplication;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = PostgresqlActivationApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@ActiveProfiles({"test", "postgresql-activation"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PostgresqlActivationApplicationIT
        extends PostgresqlAuthPersistenceTestSupport {

    @Container
    private static final PostgreSQLContainer<?> DATABASE = database();

    @DynamicPropertySource
    static void configureActivation(DynamicPropertyRegistry properties) {
        DATABASE.start();
        migratedJdbc(DATABASE);
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private TestRestTemplate client;

    @Test
    void startsTheIsolatedActivationReadinessWithoutProductionStorage() {
        assertThat(context.getBeansOfType(
                PostgresqlActivationReadinessController.class
        )).hasSize(1);
        assertThat(context.getBeansOfType(ReadinessController.class)).isEmpty();

        var response = client.getForEntity("/api/readiness", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("status", "ATIVACAO_PENDENTE")
                .containsEntry("service", "cortex-api")
                .containsKey("revision")
                .containsKey("timestamp")
                .doesNotContainKeys(
                        "objectStorage",
                        "runtimeInstanceSha256",
                        "offlineGrantPublicKeySha256"
                );
    }
}
