package com.projeto.cortex.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.ontology.OperationalMemoryController;
import com.projeto.cortex.ontology.OperationalMemoryQueryService;
import com.projeto.cortex.ontology.graph.OntologyGraphController;
import com.projeto.cortex.ontology.graph.OntologyGraphQueryService;
import com.projeto.cortex.pdor.PdorApplicationService;
import com.projeto.cortex.pdor.PdorController;
import com.projeto.cortex.rdos.RdoController;
import com.projeto.cortex.rdos.RdoCreateRequest;
import com.projeto.cortex.rdos.RdoDraftUpdateService;
import com.projeto.cortex.rdos.RdoQueryService;
import com.projeto.cortex.rdos.RdoResponse;
import com.projeto.cortex.rdos.RdoService;
import com.projeto.cortex.rdos.RdoWorkflowService;
import com.projeto.cortex.rdos.export.RdoExportWorksiteReader;
import com.projeto.cortex.rdos.export.RdoXlsxExportService;
import com.projeto.cortex.storage.ReopenableInput;
import com.projeto.cortex.storage.StoredObjectContentInspector;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

/** Boundary contracts for externally supplied Cortex 3 resource consumption. */
class Cortex3ResourceLimitTest {

    private static final int MEMORY_QUERY_LIMIT = 200;
    private static final int MEMORY_PAGE_LIMIT = 100;
    private static final int GRAPH_DEPTH_LIMIT = 3;
    private static final int GRAPH_RESULT_LIMIT = 100;
    private static final int RDO_WORKFORCE_LIMIT = 26;
    private static final int RDO_SERVICE_LIMIT = 21;
    private static final int RDO_MATERIAL_LIMIT = 30;
    private static final int RDO_ATTACHMENT_LIMIT = 5;
    private static final int XLSX_RDO_OBSERVATION_VALUE_LIMIT =
            100 - "RDO: ".length();
    private static final long ATTACHMENT_BYTES_LIMIT = 25L * 1024L * 1024L;
    private static final int PDOR_HISTORY_PAGE_LIMIT = 100;

    @Test
    void memoryAcceptsExactQueryAndPageLimitsAndCapsOneOverPage() {
        OperationalMemoryQueryService search = mock(OperationalMemoryQueryService.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        FinancialAccessService financialAccess = mock(FinancialAccessService.class);
        when(currentUser.requireUserId()).thenReturn("user-1");
        when(currentUser.allowedObraIds("user-1")).thenReturn(Optional.empty());
        OperationalMemoryController controller = new OperationalMemoryController(
                search, currentUser, financialAccess
        );

        controller.memory("x".repeat(MEMORY_QUERY_LIMIT), null, null, null,
                null, null, null, null, null, null, null, MEMORY_PAGE_LIMIT,
                null, null, null);
        verify(search).search(any(), any(), eq(MEMORY_PAGE_LIMIT), eq(null));

        controller.memory(null, null, null, null, null, null, null, null,
                null, null, null, MEMORY_PAGE_LIMIT + 1, null, null, null);
        verify(search, times(2)).search(
                any(), any(), eq(MEMORY_PAGE_LIMIT), eq(null)
        );

        assertThatThrownBy(() -> controller.memory(
                "x".repeat(MEMORY_QUERY_LIMIT + 1), null, null, null,
                null, null, null, null, null, null, null, MEMORY_PAGE_LIMIT,
                null, null, null
        )).isInstanceOf(RuntimeException.class);
    }

    @Test
    void graphAllowsExactDepthAndResultLimitButRejectsOneOver() {
        OntologyGraphQueryService query = mock(OntologyGraphQueryService.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        when(currentUser.requireUserId()).thenReturn("admin-1");
        OntologyGraphController controller = new OntologyGraphController(query, currentUser);
        when(query.listEntitiesScoped(null, null, null, 0, GRAPH_RESULT_LIMIT))
                .thenReturn(List.of());
        when(query.listRelationsScoped(null, null, null, GRAPH_DEPTH_LIMIT, 0,
                GRAPH_RESULT_LIMIT)).thenReturn(List.of());

        controller.entities(null, null, null, 0, GRAPH_RESULT_LIMIT, null);
        controller.relations(null, null, null, GRAPH_DEPTH_LIMIT, 0,
                GRAPH_RESULT_LIMIT, null);

        assertThatThrownBy(() -> controller.entities(
                null, null, null, 0, GRAPH_RESULT_LIMIT + 1, null
        )).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> controller.relations(
                null, null, null, GRAPH_DEPTH_LIMIT + 1, 0,
                GRAPH_RESULT_LIMIT, null
        )).isInstanceOf(RuntimeException.class);
    }

    @Test
    void rdoCreateRejectsOneOverEachPrintableCollectionBeforeServiceExecution()
            throws Exception {
        RdoService service = mock(RdoService.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new RdoController(
                service,
                mock(RdoQueryService.class),
                mock(RdoDraftUpdateService.class),
                mock(RdoWorkflowService.class),
                mock(CurrentUserService.class)
        )).build();

        assertRdoLimit(mockMvc, service, "maoObra", RDO_WORKFORCE_LIMIT);
        assertRdoLimit(mockMvc, service, "servicosExecutados", RDO_SERVICE_LIMIT);
        assertRdoLimit(mockMvc, service, "materiais", RDO_MATERIAL_LIMIT);
        assertRdoLimit(mockMvc, service, "attachments", RDO_ATTACHMENT_LIMIT);
    }

    @Test
    void xlsxAcceptsExactRowsAndTextButRejectsOneOver() {
        RdoQueryService query = mock(RdoQueryService.class);
        RdoExportWorksiteReader worksite = mock(RdoExportWorksiteReader.class);
        when(worksite.read("obra-1")).thenReturn(
                new RdoExportWorksiteReader.Worksite("Obra Norte", "CW-001")
        );
        RdoXlsxExportService exporter = new RdoXlsxExportService(query, worksite);

        when(query.buscarPorId("exact")).thenReturn(rdo(
                RDO_WORKFORCE_LIMIT, RDO_SERVICE_LIMIT, RDO_MATERIAL_LIMIT,
                "x".repeat(XLSX_RDO_OBSERVATION_VALUE_LIMIT)
        ));
        exporter.export("exact");

        when(query.buscarPorId("workforce-over")).thenReturn(rdo(
                RDO_WORKFORCE_LIMIT + 1, 0, 0, null
        ));
        assertUnprocessable(() -> exporter.export("workforce-over"));

        when(query.buscarPorId("service-over")).thenReturn(rdo(
                0, RDO_SERVICE_LIMIT + 1, 0, null
        ));
        assertUnprocessable(() -> exporter.export("service-over"));

        when(query.buscarPorId("material-over")).thenReturn(rdo(
                0, 0, RDO_MATERIAL_LIMIT + 1, null
        ));
        assertUnprocessable(() -> exporter.export("material-over"));

        when(query.buscarPorId("text-over")).thenReturn(rdo(
                0, 0, 0, "x".repeat(XLSX_RDO_OBSERVATION_VALUE_LIMIT + 1)
        ));
        assertUnprocessable(() -> exporter.export("text-over"));
    }

    @Test
    void attachmentInspectorAcceptsConfiguredByteLimitAndRejectsOneOver() {
        StoredObjectContentInspector inspector = new StoredObjectContentInspector(
                ATTACHMENT_BYTES_LIMIT, Set.of("text/plain")
        );
        ReopenableInput text = () -> new ByteArrayInputStream("ok".getBytes());

        inspector.inspect(text, ATTACHMENT_BYTES_LIMIT, "text/plain");
        assertPayloadTooLarge(() -> inspector.inspect(
                text, ATTACHMENT_BYTES_LIMIT + 1, "text/plain"
        ));
    }

    @Test
    void pdorHistoryRejectsOneOverPageLimitBeforeCallingApplicationService() {
        PdorApplicationService service = mock(PdorApplicationService.class);
        FinancialAccessService access = mock(FinancialAccessService.class);
        PdorController controller = new PdorController(service, access);

        controller.historico("obra-1", 0, PDOR_HISTORY_PAGE_LIMIT);
        verify(service).buscarHistorico("obra-1", 0, PDOR_HISTORY_PAGE_LIMIT);
        org.mockito.Mockito.reset(service);

        assertThatThrownBy(() -> controller.historico(
                "obra-1", 0, PDOR_HISTORY_PAGE_LIMIT + 1
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.historico(
                "obra-1", Integer.MAX_VALUE, PDOR_HISTORY_PAGE_LIMIT
        )).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(service);
    }

    private void assertRdoLimit(
            MockMvc mockMvc,
            RdoService service,
            String field,
            int limit
    ) throws Exception {
        mockMvc.perform(post("/api/rdos")
                        .contentType("application/json")
                        .content(rdoPayload(field, limit)))
                .andExpect(status().isCreated());
        verify(service).criarRascunho(any(RdoCreateRequest.class));

        org.mockito.Mockito.reset(service);
        mockMvc.perform(post("/api/rdos")
                        .contentType("application/json")
                        .content(rdoPayload(field, limit + 1)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    private String rdoPayload(String field, int count) {
        String item = switch (field) {
            case "maoObra" -> "{\"id\":\"worker\",\"cargo\":\"Apontador\"}";
            case "servicosExecutados" -> "{\"id\":\"service\",\"servicoNome\":\"Fresagem\"}";
            case "materiais" -> "{\"id\":\"material\",\"materialNome\":\"CAP\"}";
            case "attachments" -> "{\"id\":\"photo\"}";
            default -> throw new IllegalArgumentException("campo desconhecido");
        };
        return "{\"obraId\":\"obra-1\",\"" + field + "\":["
                + String.join(",", java.util.Collections.nCopies(count, item))
                + "]}";
    }

    private void assertUnprocessable(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action
    ) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
                );
    }

    private void assertPayloadTooLarge(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action
    ) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE)
                );
    }

    private RdoResponse rdo(
            int workforceCount,
            int serviceCount,
            int materialCount,
            String observations
    ) {
        List<RdoResponse.MaoObraItem> workforce = java.util.stream.IntStream
                .range(0, workforceCount)
                .mapToObj(index -> new RdoResponse.MaoObraItem(
                        "worker-" + index, null, null, "Cargo " + index,
                        null, BigDecimal.ONE, null, null, null, null
                )).toList();
        List<RdoResponse.ServicoExecutadoItem> services = java.util.stream.IntStream
                .range(0, serviceCount)
                .mapToObj(index -> new RdoResponse.ServicoExecutadoItem(
                        "service-" + index, null, null, "Serviço " + index,
                        null, BigDecimal.ONE, "UN", null, null, null, null,
                        null, null, null, null, null, null, false, false, null
                )).toList();
        List<RdoResponse.MaterialItem> materials = java.util.stream.IntStream
                .range(0, materialCount)
                .mapToObj(index -> new RdoResponse.MaterialItem(
                        "material-" + index, "Material " + index, "UN",
                        BigDecimal.ONE, null, null, null, null, null, null
                )).toList();
        return new RdoResponse(
                "rdo-1", "obra-1", null, "RDO-0001", LocalDate.of(2026, 7, 22),
                null, null, null, null, null, null, null, "BR-101", null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                "RASCUNHO", observations, null, null, null, null, workforce, List.of(),
                materials, List.of(), services, List.of(), List.of()
        );
    }
}
