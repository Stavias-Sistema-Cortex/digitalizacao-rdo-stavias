package com.projeto.cortex.auth.offline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class OfflineGrantSubjectRepositoryTest {

    @Test
    @SuppressWarnings("unchecked")
    void readsOnlyAnActiveSubjectAndUsesTheDatabaseUtcClock() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet resultSet = mock(ResultSet.class);
        UUID collaboratorId = UUID.fromString(
                "10000000-0000-0000-0000-000000000001"
        );
        Instant databaseNow = Instant.parse("2030-01-02T03:04:05.123456Z");
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("nome")).thenReturn("Pessoa Sintética");
        when(resultSet.getTimestamp("database_now"))
                .thenReturn(Timestamp.from(databaseNow));
        when(jdbc.query(
                anyString(),
                any(ResultSetExtractor.class),
                eq(collaboratorId.toString())
        )).thenAnswer(invocation -> invocation
                .getArgument(1, ResultSetExtractor.class)
                .extractData(resultSet));

        Optional<OfflineGrantSubject> result =
                new OfflineGrantSubjectRepository(jdbc)
                        .findActive(collaboratorId);

        assertThat(result).contains(new OfflineGrantSubject(
                "Pessoa Sintética",
                databaseNow
        ));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(
                sql.capture(),
                any(ResultSetExtractor.class),
                eq(collaboratorId.toString())
        );
        assertThat(sql.getValue().replaceAll("\\s+", " "))
                .contains("CURRENT_TIMESTAMP(6) AS database_now")
                .contains("colaborador.ativo = TRUE")
                .contains("colaborador.deletado_em IS NULL");
    }
}
