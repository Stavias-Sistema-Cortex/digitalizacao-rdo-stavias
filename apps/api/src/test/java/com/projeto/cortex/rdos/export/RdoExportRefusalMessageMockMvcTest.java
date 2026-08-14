package com.projeto.cortex.rdos.export;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.auth.PapelAcesso;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

/**
 * A recusa da exportação precisa chegar em português, e não como código.
 *
 * <p>O agregado da exportação recusa com 422 e escreve o motivo para ser lido:
 * quantos serviços cabem no template, qual linha de mão de obra está sem cargo,
 * qual vínculo de equipamento não é reconhecido. Sem um tratador próprio, o
 * padrão do Spring Boot apaga esse texto e o aplicativo — que procura
 * {@code message}, depois {@code detail}, depois {@code error} — acaba
 * mostrando "Unprocessable Entity" para quem só queria o arquivo.
 *
 * <p>O teste é MockMvc de fatia web justamente porque é o corpo da resposta que
 * está em jogo, e não o cálculo: o serviço é dublado a recusar, e o que se
 * verifica é o que sai pela rede.
 */
@WebMvcTest(RdoExportController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({CurrentUserService.class, RdoExportExceptionHandler.class})
class RdoExportRefusalMessageMockMvcTest {

    private static final String MOTIVO =
            "O template RDO v1 comporta 12 serviços, mas o RDO possui 15;"
                    + " nenhum item foi truncado.";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private RdoXlsxExportService service;

    @MockBean
    private RdoPdfExportService pdfService;

    @Test
    void aPlanilhaRecusadaDizPorQue() throws Exception {
        rdoWorksite("rdo-a", "obra-a");
        papel("alfa", PapelAcesso.ALFA);
        when(service.export("rdo-a")).thenThrow(
                new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        MOTIVO
                )
        );

        mockMvc.perform(get("/api/rdos/rdo-a/export.xlsx")
                        .requestAttr(
                                CurrentUserService.REQUEST_ATTRIBUTE_USER_ID,
                                "alfa"
                        ))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(MOTIVO));
    }

    @Test
    void oPdfRecusadoDizPorQue() throws Exception {
        rdoWorksite("rdo-a", "obra-a");
        papel("alfa", PapelAcesso.ALFA);
        when(pdfService.export("rdo-a")).thenThrow(
                new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        MOTIVO
                )
        );

        mockMvc.perform(get("/api/rdos/rdo-a/export.pdf")
                        .requestAttr(
                                CurrentUserService.REQUEST_ATTRIBUTE_USER_ID,
                                "alfa"
                        ))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(MOTIVO));
    }

    /*
     * Recusa sem texto não pode virar corpo sem campo: o aplicativo precisa de
     * algo para mostrar, e a frase do status é o piso — feio, mas nunca vazio.
     */
    @Test
    void recusaSemMotivoAindaTrazAlgoParaMostrar() throws Exception {
        rdoWorksite("rdo-a", "obra-a");
        papel("alfa", PapelAcesso.ALFA);
        when(service.export("rdo-a")).thenThrow(
                new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY)
        );

        mockMvc.perform(get("/api/rdos/rdo-a/export.xlsx")
                        .requestAttr(
                                CurrentUserService.REQUEST_ATTRIBUTE_USER_ID,
                                "alfa"
                        ))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Unprocessable Entity"));
    }

    private void rdoWorksite(String rdoId, String obraId) {
        when(jdbcTemplate.query(
                contains("FROM rdo"),
                any(ResultSetExtractor.class),
                any(Object[].class)
        )).thenReturn(obraId);
    }

    private void papel(String colaboradorId, PapelAcesso papel) {
        when(jdbcTemplate.query(
                contains("FROM colaborador"),
                any(ResultSetExtractor.class),
                any(Object[].class)
        )).thenReturn(papel);
    }
}
