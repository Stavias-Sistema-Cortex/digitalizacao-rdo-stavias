package com.projeto.cortex.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class WakeEndpointTest {

    private static final String REVISION =
            "0123456789abcdef0123456789abcdef01234567";

    @Test
    void reportsAWarmDatabaseAfterTheConnectionIsOpened() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(eq("SELECT 1"), eq(Integer.class)))
                .thenReturn(1);

        assertThat(wake(new JdbcDatabaseWake(jdbc)))
                .containsEntry("status", "AWAKE")
                .containsEntry("database", "WARM")
                .containsEntry("revision", REVISION);
    }

    /**
     * Um toque de aquecimento não decide nada, então não pode fechar nada: com o
     * banco ainda suspenso a resposta continua sendo uma resposta.
     */
    @Test
    void staysAvailableWhileTheDatabaseIsStillCold() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(eq("SELECT 1"), eq(Integer.class)))
                .thenThrow(new DataAccessResourceFailureException("suspenso"));

        assertThat(wake(new JdbcDatabaseWake(jdbc)))
                .containsEntry("status", "AWAKE")
                .containsEntry("database", "COLD");
    }

    @Test
    void reportsAnAbsentDatabaseWithoutFailing() {
        assertThat(wake(new JdbcDatabaseWake((JdbcTemplate) null)))
                .containsEntry("status", "AWAKE")
                .containsEntry("database", "ABSENT");
    }

    /**
     * O readiness carrega evidência de release e o round trip do object storage.
     * Nada disso pertence ao caminho de acordar, e o contrato aqui é justamente
     * a ausência: quem acorda a instância não paga por eles.
     */
    @Test
    void neverPublishesReleaseEvidenceOrTouchesObjectStorage() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(eq("SELECT 1"), eq(Integer.class)))
                .thenReturn(1);

        assertThat(wake(new JdbcDatabaseWake(jdbc)))
                .containsOnlyKeys(
                        "status",
                        "service",
                        "revision",
                        "database",
                        "timestamp"
                );
    }

    @Test
    void opensExactlyOneConnectionPerTouch() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(eq("SELECT 1"), eq(Integer.class)))
                .thenReturn(1);

        wake(new JdbcDatabaseWake(jdbc));

        verify(jdbc).queryForObject(eq("SELECT 1"), eq(Integer.class));
    }

    private java.util.Map<String, String> wake(DatabaseWake database) {
        return new WakeController(database, new RuntimeRevision(REVISION))
                .wake();
    }
}
