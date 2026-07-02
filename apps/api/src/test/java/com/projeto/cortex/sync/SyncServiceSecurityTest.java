package com.projeto.cortex.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.rdos.RdoDraftUpdateService;
import com.projeto.cortex.rdos.RdoQueryService;
import com.projeto.cortex.rdos.RdoService;
import com.projeto.cortex.rdos.RdoWorkflowService;
import java.sql.ResultSet;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SyncServiceSecurityTest {

    @Test
    void shouldBindRegisteredDeviceToAuthenticatedUser() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        when(currentUserService.requireUserId()).thenReturn("usuario-auth");

        when(
                jdbcTemplate.query(
                        contains("FROM sync_dispositivo"),
                        any(ResultSetExtractor.class),
                        eq("device-1")
                )
        ).thenReturn(null);

        when(
                jdbcTemplate.queryForObject(
                        anyString(),
                        any(RowMapper.class),
                        eq("device-1")
                )
        ).thenReturn(
                new SyncDeviceResponse(
                        "device-1",
                        "Navegador",
                        "WEB",
                        "usuario-auth",
                        true,
                        0,
                        Instant.now()
                )
        );

        SyncService service = new SyncService(
                jdbcTemplate,
                new ObjectMapper(),
                mock(TransactionTemplate.class),
                mock(RdoService.class),
                mock(RdoDraftUpdateService.class),
                mock(RdoWorkflowService.class),
                mock(RdoQueryService.class),
                currentUserService
        );

        SyncDeviceResponse response =
                service.registrarDispositivo(
                        new SyncDeviceRequest(
                                "device-1",
                                "Navegador",
                                "WEB",
                                "usuario-forjado"
                        )
                );

        assertThat(response.usuarioId()).isEqualTo("usuario-auth");
        verify(jdbcTemplate).update(
                contains("INSERT INTO sync_dispositivo"),
                eq("device-1"),
                eq("Navegador"),
                eq("WEB"),
                eq("usuario-auth")
        );
    }
}
