package com.projeto.cortex.rdos.export;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.projeto.cortex.auth.CurrentUserService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class RdoExportControllerTest {

    private final RdoXlsxExportService service = mock(RdoXlsxExportService.class);
    private final CurrentUserService currentUserService =
            mock(CurrentUserService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new RdoExportController(service, currentUserService)
        ).build();
    }

    @Test
    void streamsPrivateXlsxWithSafeAttachmentHeadersAfterAuthorization()
            throws Exception {
        byte[] bytes = "xlsx".getBytes(StandardCharsets.UTF_8);
        when(service.export("rdo-7")).thenReturn(
                new RdoXlsxExportService.ExportedRdo(
                        bytes,
                        "rdo-RDO-0007.xlsx"
                )
        );

        mockMvc.perform(get("/api/rdos/rdo-7/export.xlsx"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(bytes))
                .andExpect(content().contentType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                ))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"rdo-RDO-0007.xlsx\""
                ));

        verify(currentUserService).requireRdoAccess("rdo-7");
        verify(service).export("rdo-7");
    }

    @Test
    void deniesCrossWorksiteAccessBeforeReadingOrExportingTheRdo()
            throws Exception {
        doThrow(new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Você não possui permissão para acessar esta obra."
        )).when(currentUserService).requireRdoAccess("rdo-other-worksite");

        mockMvc.perform(get("/api/rdos/rdo-other-worksite/export.xlsx"))
                .andExpect(status().isForbidden());

        verify(service, never()).export("rdo-other-worksite");
    }

    @Test
    void preservesNotFoundWithoutTouchingTheExporter() throws Exception {
        doThrow(new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "RDO não encontrado."
        )).when(currentUserService).requireRdoAccess("missing");

        mockMvc.perform(get("/api/rdos/missing/export.xlsx"))
                .andExpect(status().isNotFound());

        verify(service, never()).export("missing");
    }
}
