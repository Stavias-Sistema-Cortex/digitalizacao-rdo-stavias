package com.projeto.cortex.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import com.projeto.cortex.financeiro.core.FinanceAuditContext;
import com.projeto.cortex.financeiro.core.FinanceCatalogService;
import com.projeto.cortex.financeiro.core.FinanceDtos;
import com.projeto.cortex.financeiro.core.FinanceOntologyProjector;
import com.projeto.cortex.financeiro.core.FinancePurchaseService;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

/** Regression coverage for persisted-worksite authorization in Financeiro. */
class FinanceStoredWorksiteAuthorizationTest {

    private static final String ACTOR = "00000000-0000-4000-8000-000000000001";
    private static final String WORKSITE_A = "00000000-0000-4000-8000-000000000101";
    private static final String WORKSITE_B = "00000000-0000-4000-8000-000000000102";
    private static final String PURCHASE_ID = "00000000-0000-4000-8000-000000000201";
    private static final String STATUS_ID = "00000000-0000-4000-8000-000000000301";
    private static final String MUTATION_ID = "00000000-0000-4000-8000-000000000401";

    @Test
    @SuppressWarnings("unchecked")
    void statusAndDecisionAuthorizeThePersistedPurchaseWorksite() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        FinancialAccessService access = mock(FinancialAccessService.class);
        when(currentUser.requireUserId()).thenReturn(ACTOR);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(PURCHASE_ID)))
                .thenReturn(List.of(purchase()));
        doThrow(forbidden()).when(access).requirePermission(
                WORKSITE_B, FinancialPermission.FINANCEIRO_OPERAR
        );
        doThrow(forbidden()).when(access).requirePermission(
                WORKSITE_B, FinancialPermission.FINANCEIRO_APROVAR
        );
        FinancePurchaseService service = new FinancePurchaseService(
                jdbc,
                currentUser,
                access,
                mock(FinanceOntologyProjector.class),
                new ObjectMapper()
        );
        FinanceAuditContext audit = FinanceAuditContext.online(ACTOR, "security-test");

        assertThatThrownBy(() -> service.changePurchaseStatus(
                PURCHASE_ID,
                new FinanceDtos.StatusChangeRequest(STATUS_ID, MUTATION_ID, 7L),
                audit
        )).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.decidePurchase(
                PURCHASE_ID,
                new FinanceDtos.ApprovalDecisionRequest(
                        "APROVAR", null, MUTATION_ID
                ),
                audit
        )).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void archiveAuthorizesTheWorksiteReadFromTheStoredObject() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        FinancialAccessService access = mock(FinancialAccessService.class);
        when(currentUser.requireUserId()).thenReturn(ACTOR);
        when(jdbc.queryForMap(anyString(), eq(PURCHASE_ID))).thenReturn(Map.of(
                "obra_id", WORKSITE_B,
                "status_id", STATUS_ID,
                "versao_linha", 7L
        ));
        doThrow(forbidden()).when(access).requirePermission(
                WORKSITE_B, FinancialPermission.FINANCEIRO_OPERAR
        );
        FinancePurchaseService service = new FinancePurchaseService(
                jdbc,
                currentUser,
                access,
                mock(FinanceOntologyProjector.class),
                new ObjectMapper()
        );

        assertThatThrownBy(() -> service.archivePurchase(
                PURCHASE_ID,
                7L,
                MUTATION_ID,
                FinanceAuditContext.online(ACTOR, "security-test")
        )).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void financialReplayIsBoundToOperationEntityAndCompleteRequestHash()
            throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        FinancialAccessService access = mock(FinancialAccessService.class);
        when(currentUser.requireUserId()).thenReturn(ACTOR);
        ResultSet receiptRows = mock(ResultSet.class);
        when(receiptRows.next()).thenReturn(true);
        when(receiptRows.getString("entidade_id")).thenReturn(PURCHASE_ID);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String originalDecisionHash = decisionHash(
                mapper, "APROVAR", "Justificativa original"
        );
        when(receiptRows.getString("estado_novo_json")).thenReturn(
                "{\"_mutationOperation\":\"DECIDE_PURCHASE\","
                        + "\"_mutationRequestHash\":\""
                        + originalDecisionHash + "\"}"
        );
        when(jdbc.query(
                anyString(),
                any(ResultSetExtractor.class),
                eq(ACTOR),
                eq(MUTATION_ID),
                eq("COMPRA")
        )).thenAnswer(invocation -> invocation
                .<ResultSetExtractor<?>>getArgument(1)
                .extractData(receiptRows));
        when(jdbc.query(anyString(), any(RowMapper.class), eq(PURCHASE_ID)))
                .thenReturn(List.of(purchase()));
        FinancePurchaseService service = new FinancePurchaseService(
                jdbc,
                currentUser,
                access,
                mock(FinanceOntologyProjector.class),
                mapper
        );
        FinanceDtos.PurchaseRequest changedPayload = new FinanceDtos.PurchaseRequest(
                PURCHASE_ID,
                MUTATION_ID,
                null,
                WORKSITE_A,
                "00000000-0000-4000-8000-000000000501",
                "00000000-0000-4000-8000-000000000502",
                "00000000-0000-4000-8000-000000000503",
                ACTOR,
                STATUS_ID,
                "PED-SEC-1",
                "Conteúdo divergente",
                "ALTA",
                "BRL",
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.TEN,
                new BigDecimal("9.00"),
                LocalDate.of(2026, 7, 23),
                LocalDate.of(2026, 7, 30),
                List.of(new FinanceDtos.LineItemRequest(
                        null, "Item", BigDecimal.ONE, "un",
                        BigDecimal.TEN, BigDecimal.TEN, "CONSUMO"
                )),
                null
        );

        assertThatThrownBy(() -> service.savePurchase(
                changedPayload,
                FinanceAuditContext.online(ACTOR, "security-test")
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("outro conteúdo");
        assertThatThrownBy(() -> service.decidePurchase(
                PURCHASE_ID,
                new FinanceDtos.ApprovalDecisionRequest(
                        "APROVAR", "Justificativa alterada", MUTATION_ID
                ),
                FinanceAuditContext.online(ACTOR, "security-test")
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("outro conteúdo");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void approvalRuleCannotReplaceAnotherWorksitesPolicy() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        FinancialAccessService access = mock(FinancialAccessService.class);
        when(currentUser.requireUserId()).thenReturn(ACTOR);
        when(jdbc.query(
                anyString(),
                any(ResultSetExtractor.class),
                eq(PURCHASE_ID)
        )).thenReturn(WORKSITE_B);
        doThrow(forbidden()).when(access).requirePermission(
                WORKSITE_B, FinancialPermission.FINANCEIRO_ADMINISTRAR
        );
        FinanceCatalogService service = new FinanceCatalogService(
                jdbc,
                currentUser,
                access,
                mock(FinanceOntologyProjector.class)
        );
        FinanceDtos.ApprovalRuleRequest request =
                new FinanceDtos.ApprovalRuleRequest(
                        PURCHASE_ID,
                        null,
                        null,
                        "Regra A",
                        null,
                        "BRL",
                        BigDecimal.ZERO,
                        null,
                        LocalDateTime.of(2026, 7, 23, 0, 0),
                        null,
                        List.of(new FinanceDtos.ApprovalStepRequest(
                                null, 1, "PERMISSAO", null,
                                "FINANCEIRO_APROVAR", 1
                        ))
                );

        assertThatThrownBy(() -> service.saveApprovalRule(
                WORKSITE_A,
                request,
                FinanceAuditContext.online(ACTOR, "security-test")
        )).isInstanceOf(ResponseStatusException.class);
    }

    private FinanceDtos.PurchaseResponse purchase() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 23, 9, 0);
        return new FinanceDtos.PurchaseResponse(
                PURCHASE_ID, null, WORKSITE_B, "Obra B", STATUS_ID,
                "Centro", STATUS_ID, "Categoria", STATUS_ID, "Fornecedor",
                ACTOR, "Responsável", STATUS_ID, "PENDENTE", "Pendente",
                "PED-1", "Compra B", "NORMAL", "BRL", BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO,
                null, null, now, now, 7L, List.of()
        );
    }

    private ResponseStatusException forbidden() {
        return new ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN,
                "sem acesso"
        );
    }

    private String decisionHash(
            ObjectMapper mapper,
            String decision,
            String justification
    ) throws Exception {
        var payload = mapper.createObjectNode();
        payload.put("purchaseId", PURCHASE_ID);
        payload.put("decision", decision);
        payload.put("justification", justification);
        payload.put("clientMutationId", MUTATION_ID);
        var material = mapper.createObjectNode();
        material.put("operation", "DECIDE_PURCHASE");
        material.set("payload", payload);
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(mapper.writeValueAsBytes(material))
        );
    }
}
