package com.projeto.cortex.rdos.export;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.auth.PapelAcesso;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RdoExportController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(CurrentUserService.class)
class RdoExportControllerAuthorizationMockMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private RdoXlsxExportService service;

    @MockBean
    private RdoPdfExportService pdfService;

    @Test
    void betaCannotExportRdoFromAnotherWorksite() throws Exception {
        rdoWorksite("rdo-b", "obra-b");
        papel("beta", PapelAcesso.BETA);
        vinculo("beta", "obra-b", false);

        mockMvc.perform(get("/api/rdos/rdo-b/export.xlsx")
                        .requestAttr(
                                CurrentUserService.REQUEST_ATTRIBUTE_USER_ID,
                                "beta"
                        ))
                .andExpect(status().isForbidden());

        verify(service, never()).export("rdo-b");
    }

    @Test
    void missingRdoReturnsNotFoundBeforeExporterReadsAggregate()
            throws Exception {
        rdoWorksite("missing", null);

        mockMvc.perform(get("/api/rdos/missing/export.xlsx")
                        .requestAttr(
                                CurrentUserService.REQUEST_ATTRIBUTE_USER_ID,
                                "beta"
                        ))
                .andExpect(status().isNotFound());

        verify(service, never()).export("missing");
    }

    @Test
    void betaCannotExportPdfFromAnotherWorksite() throws Exception {
        rdoWorksite("rdo-pdf-b", "obra-b");
        papel("beta", PapelAcesso.BETA);
        vinculo("beta", "obra-b", false);

        mockMvc.perform(get("/api/rdos/rdo-pdf-b/export.pdf")
                        .requestAttr(
                                CurrentUserService.REQUEST_ATTRIBUTE_USER_ID,
                                "beta"
                        ))
                .andExpect(status().isForbidden());

        verify(pdfService, never()).export("rdo-pdf-b");
    }

    @Test
    void missingPdfRdoReturnsNotFoundBeforePdfServiceReadsAggregate()
            throws Exception {
        rdoWorksite("missing-pdf", null);

        mockMvc.perform(get("/api/rdos/missing-pdf/export.pdf")
                        .requestAttr(
                                CurrentUserService.REQUEST_ATTRIBUTE_USER_ID,
                                "beta"
                        ))
                .andExpect(status().isNotFound());

        verify(pdfService, never()).export("missing-pdf");
    }

    @SuppressWarnings("unchecked")
    private void rdoWorksite(String rdoId, String obraId) {
        when(jdbcTemplate.query(
                contains("FROM rdo"),
                any(ResultSetExtractor.class),
                eq(rdoId)
        )).thenReturn(obraId);
    }

    @SuppressWarnings("unchecked")
    private void papel(String userId, PapelAcesso papel) {
        when(jdbcTemplate.query(
                contains("FROM colaborador"),
                any(ResultSetExtractor.class),
                eq(userId)
        )).thenReturn(papel);
    }

    private void vinculo(String userId, String obraId, boolean ativo) {
        when(jdbcTemplate.queryForObject(
                contains("vinculo_colaborador_obra"),
                eq(Integer.class),
                eq(userId),
                eq(obraId)
        )).thenReturn(ativo ? 1 : 0);
    }
}
