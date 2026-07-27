package com.projeto.cortex.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.rdos.RdoAttachmentService;
import com.projeto.cortex.rdos.RdoCreateRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class OperationalEntityReferenceValidationTest {

    private static final String RDO_ID =
            "00000000-0000-4000-8000-000000000101";
    private static final String FOREIGN_RDO_ID =
            "00000000-0000-4000-8000-000000000102";
    private static final String WORKSITE_A =
            "00000000-0000-4000-8000-000000000201";
    private static final String WORKSITE_B =
            "00000000-0000-4000-8000-000000000202";
    private static final String ATTACHMENT_ID =
            "00000000-0000-4000-8000-000000000301";

    @Test
    void rejectsAttachmentWithClientSuppliedForeignWorksiteBeforeWrite() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RdoAttachmentService service = new RdoAttachmentService(
                jdbc, new ObjectMapper()
        );

        assertThatThrownBy(() -> service.substituirAttachments(
                RDO_ID,
                WORKSITE_A,
                List.of(attachment(RDO_ID, WORKSITE_B))
        )).isInstanceOf(ResponseStatusException.class);

        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsAttachmentBoundToAnotherRdoBeforeWrite() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RdoAttachmentService service = new RdoAttachmentService(
                jdbc, new ObjectMapper()
        );

        assertThatThrownBy(() -> service.substituirAttachments(
                RDO_ID,
                WORKSITE_A,
                List.of(attachment(FOREIGN_RDO_ID, WORKSITE_A))
        )).isInstanceOf(ResponseStatusException.class);

        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsAttachmentIdAlreadyOwnedByAnotherParent() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        RdoAttachmentService service = new RdoAttachmentService(
                jdbc, new ObjectMapper()
        );

        assertThatThrownBy(() -> service.substituirAttachments(
                RDO_ID,
                WORKSITE_A,
                List.of(attachment(RDO_ID, WORKSITE_A))
        )).isInstanceOfSatisfying(
                ResponseStatusException.class,
                exception -> org.assertj.core.api.Assertions.assertThat(
                        exception.getStatusCode().value()
                ).isEqualTo(409)
        );
    }

    private RdoCreateRequest.AttachmentItem attachment(
            String rdoId,
            String obraId
    ) {
        return new RdoCreateRequest.AttachmentItem(
                ATTACHMENT_ID,
                rdoId,
                obraId,
                "FOTO",
                "frente.jpg",
                "frente.jpg",
                "image/jpeg",
                100L,
                80L,
                80L,
                "PENDING_SYNC",
                null,
                null,
                null,
                Map.of()
        );
    }
}
