package com.projeto.cortex.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.auth.PapelAcesso;
import com.projeto.cortex.rdos.RdoController;
import com.projeto.cortex.rdos.RdoCreateRequest;
import com.projeto.cortex.rdos.RdoDeletionService;
import com.projeto.cortex.rdos.RdoDraftUpdateService;
import com.projeto.cortex.rdos.RdoQueryService;
import com.projeto.cortex.rdos.RdoService;
import com.projeto.cortex.rdos.RdoWorkflowService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

/** Cortex 3 object-authorization contracts that must hold before handlers run. */
class Cortex3ObjectAuthorizationTest {

    private static final String USER_ID =
            "00000000-0000-4000-8000-000000000001";
    private static final String RDO_ID =
            "00000000-0000-4000-8000-000000000101";
    private static final String WORKSITE_A =
            "00000000-0000-4000-8000-000000000201";
    private static final String WORKSITE_B =
            "00000000-0000-4000-8000-000000000202";

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void rejectsRdoWorksiteMismatchEvenWhenActorCanAccessBothWorksites() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Environment environment = mock(Environment.class);
        CurrentUserService currentUser = new CurrentUserService(
                jdbc, environment, false
        );
        authenticate(USER_ID);
        when(jdbc.query(
                contains("FROM rdo"),
                any(ResultSetExtractor.class),
                eq(RDO_ID)
        )).thenReturn(WORKSITE_A);
        when(jdbc.query(
                contains("FROM colaborador"),
                any(ResultSetExtractor.class),
                eq(USER_ID)
        )).thenReturn(PapelAcesso.BETA);
        when(jdbc.queryForObject(
                contains("vinculo_colaborador_obra"),
                eq(Integer.class),
                eq(USER_ID),
                any(String.class)
        )).thenReturn(1);

        RdoDraftUpdateService draftUpdate = mock(RdoDraftUpdateService.class);
        RdoController controller = new RdoController(
                mock(RdoService.class),
                mock(RdoQueryService.class),
                draftUpdate,
                mock(RdoWorkflowService.class),
                mock(RdoDeletionService.class),
                currentUser
        );

        assertThatThrownBy(() -> controller.atualizarRascunho(
                RDO_ID, request(RDO_ID, WORKSITE_B)
        )).isInstanceOf(ResponseStatusException.class);

        verifyNoInteractions(draftUpdate);
    }

    private void authenticate(String userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, userId);
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request)
        );
    }

    static RdoCreateRequest request(String id, String obraId) {
        return new RdoCreateRequest(
                id,
                obraId,
                null,
                null,
                LocalDate.of(2026, 7, 22),
                null,
                null,
                "mutation-1",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }
}
