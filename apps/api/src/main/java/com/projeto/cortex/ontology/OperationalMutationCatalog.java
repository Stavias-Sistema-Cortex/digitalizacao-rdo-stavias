package com.projeto.cortex.ontology;

import com.projeto.cortex.auth.roles.AdminRoleService;
import com.projeto.cortex.financeiro.allocation.FinanceAllocationService;
import com.projeto.cortex.financeiro.asset.FinancePurchasedAssetService;
import com.projeto.cortex.financeiro.invoice.FinanceInvoiceService;
import com.projeto.cortex.financeiro.invoice.extraction.FiscalDocumentExtractionService;
import com.projeto.cortex.financeiro.unit.FinancialUnitService;
import com.projeto.cortex.mensagens.domain.MensagemService;
import com.projeto.cortex.obras.ObraService;
import java.util.List;

/**
 * Contratos de rastreabilidade das mutações introduzidas nesta rodada.
 *
 * <p>O catálogo é deliberadamente executável: o gate de build resolve a classe
 * e o método de domínio, carrega o teste declarado e exige evento, versão,
 * projector, autorização, política StavIA, idempotência e rastreabilidade.
 * Uma mutação nova não deve ser adicionada ao produto sem entrar aqui.</p>
 */
public final class OperationalMutationCatalog {

    private OperationalMutationCatalog() {
    }

    public record Definition(
            String id,
            Class<?> implementation,
            String method,
            String eventType,
            int payloadSchemaVersion,
            String projector,
            String accessPolicy,
            String staviaEvidencePolicy,
            String idempotencyContract,
            String traceabilityContract,
            String verificationTest
    ) {
    }

    public static List<Definition> definitions() {
        return List.of(
                definition(
                        "FINANCE_CONTROL_UNIT_CREATE",
                        FinancialUnitService.class,
                        "createAdministrative",
                        "UNIDADE_FINANCEIRA_CRIADA",
                        "FinanceOntologyProjector",
                        "ALFA ou grant explícito de unidade",
                        "FINANCE_ENTITY_AUTHORIZED_ONLY",
                        "clientMutationId para fluxo offline",
                        "ator, correlação, estado e unidade referenciada",
                        "com.projeto.cortex.financeiro.unit.FinancialUnitServiceTest"
                ),
                definition(
                        "FINANCE_ALLOCATION_UPSERT",
                        FinanceAllocationService.class,
                        "save",
                        "RATEIO_FINANCEIRO_CRIADO_OU_ATUALIZADO",
                        "FinanceOntologyProjector",
                        "permissão financeira na origem e nos destinos",
                        "FINANCE_ENTITY_AUTHORIZED_ONLY",
                        "clientMutationId único por ator e origem",
                        "histórico anterior/novo, ator e RATEADO_PARA",
                        "com.projeto.cortex.financeiro.allocation.FinanceAllocationServiceTest"
                ),
                definition(
                        "FINANCE_PURCHASE_ASSETS_CONFIRM",
                        FinancePurchasedAssetService.class,
                        "confirm",
                        "ATIVOS_COMPRADOS_CONFIRMADOS",
                        "FinanceOntologyProjector",
                        "permissão de compra e acesso à unidade",
                        "ASSET_AND_FINANCE_AUTHORIZED_ONLY",
                        "clientMutationId único por ator e item",
                        "compra, ativos individuais, ator e versões",
                        "com.projeto.cortex.financeiro.asset.FinancePurchasedAssetServiceTest"
                ),
                definition(
                        "FISCAL_DOCUMENT_EXTRACT",
                        FiscalDocumentExtractionService.class,
                        "extract",
                        "DOCUMENTO_FISCAL_EXTRAIDO_OU_REVISADO",
                        "FinanceOntologyProjector",
                        "objeto armazenado e obra autorizados",
                        "FISCAL_FIELDS_WITHOUT_DOCUMENT_BODY",
                        "clientMutationId e hash cliente/servidor",
                        "uploader, job, extrator, hash, candidatos e estado",
                        "com.projeto.cortex.financeiro.invoice.extraction.FiscalDocumentExtractionServiceTest"
                ),
                definition(
                        "FISCAL_DOCUMENT_LINK_CONFIRM",
                        FinanceInvoiceService.class,
                        "linkDocument",
                        "DOCUMENTO_FISCAL_VINCULADO",
                        "FinanceOntologyProjector",
                        "nota, obra, objeto e job no mesmo escopo",
                        "FISCAL_FIELDS_WITHOUT_DOCUMENT_BODY",
                        "mutação de confirmação única por ator",
                        "ENVIOU, CONFIRMOU, DOCUMENTADA_POR e hashes",
                        "com.projeto.cortex.pdor.FinanceInvoiceServiceMysqlIntegrationTest"
                ),
                definition(
                        "MESSAGE_SEND",
                        MensagemService.class,
                        "send",
                        "MENSAGEM_ENVIADA",
                        "MessagingOperationalEventService",
                        "participação e vínculo atuais na conversa",
                        "MESSAGE_SYNC_AUTHORIZED_PARTICIPANTS_ONLY",
                        "clientMutationId único por autor",
                        "autor, conversa, visibilidade e commit",
                        "com.projeto.cortex.pdor.MessagingDomainMysqlIntegrationTest"
                ),
                definition(
                        "WORKSITE_CREATE",
                        ObraService.class,
                        "criarObra",
                        "OBRA_ATUALIZADA",
                        "ObraSyncEvento",
                        "papel ALFA no endpoint e no servidor",
                        "WORKSITE_SCOPE_AUTHORIZED_ONLY",
                        "código de contrato único",
                        "PESSOA CRIOU OBRA, ator, estado e unidade financeira",
                        "com.projeto.cortex.obras.ObraServiceTest"
                ),
                definition(
                        "ADMIN_ROLE_CHANGE",
                        AdminRoleService.class,
                        "changeRole",
                        "PAPEL_ACESSO_ALTERADO",
                        "AdminRoleService via CortexOperationalMemoryService",
                        "ALFA com ADMINISTRAR_PAPEIS",
                        "NOT_QUERIED_SENSITIVE_GOVERNANCE",
                        "papel anterior diferente do novo e transação serializada",
                        "ator, justificativa, sessão, histórico e relação",
                        "com.projeto.cortex.auth.roles.AdminRoleServiceTest"
                )
        );
    }

    private static Definition definition(
            String id,
            Class<?> implementation,
            String method,
            String eventType,
            String projector,
            String accessPolicy,
            String staviaEvidencePolicy,
            String idempotencyContract,
            String traceabilityContract,
            String verificationTest
    ) {
        return new Definition(
                id,
                implementation,
                method,
                eventType,
                1,
                projector,
                accessPolicy,
                staviaEvidencePolicy,
                idempotencyContract,
                traceabilityContract,
                verificationTest
        );
    }
}
