package com.projeto.cortex.financeiro.invoice;

import static com.projeto.cortex.financeiro.invoice.FinanceInvoiceDtos.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.core.FinanceAuditContext;
import com.projeto.cortex.financeiro.core.FinanceOntologyProjector;
import com.projeto.cortex.financeiro.core.FinanceOntologyRelation;
import com.projeto.cortex.financeiro.core.FinanceValidation;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FinanceInvoiceService {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private static final Set<String> INVOICE_TYPES = Set.of(
            "NFE", "NFSE", "CTE", "RECIBO", "OUTRO"
    );
    private static final Set<String> DOCUMENT_TYPES = Set.of(
            "DANFE", "XML", "BOLETO", "RECIBO", "COMPROVANTE", "OUTRO"
    );
    private static final String INVOICE_SELECT = """
            SELECT nf.*, f.razao_social AS fornecedor_nome,
                   f.cnpj_normalizado AS fornecedor_cnpj,
                   r.nome AS responsavel_nome,
                   st.codigo AS status_codigo,
                   st.nome AS status_nome
            FROM finance_nota_fiscal nf
            JOIN finance_fornecedor f ON f.id = nf.fornecedor_id
            JOIN colaborador r ON r.id = nf.responsavel_id
            JOIN finance_status_definicao st ON st.id = nf.status_id
            """;

    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUser;
    private final FinanceOntologyProjector ontology;
    private final ObjectMapper objectMapper;

    public FinanceInvoiceService(
            JdbcTemplate jdbc,
            CurrentUserService currentUser,
            FinanceOntologyProjector ontology,
            ObjectMapper objectMapper
    ) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.ontology = ontology;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public InvoiceResponse save(
            InvoiceRequest request,
            FinanceAuditContext audit
    ) {
        requireActor(audit);
        ValidatedInvoice input = validate(request);
        String replayId = mutationEntity(audit.actorId(), input.mutationId());
        if (replayId != null) {
            InvoiceResponse replay = get(replayId);
            if (!sameInvoice(replay, input)) {
                throw conflict(
                        "clientMutationId já foi usado com outro conteúdo."
                );
            }
            return replay;
        }

        InvoiceResponse previous = find(input.id());
        boolean created = previous == null;
        requireStatus(input.obraId(), input.statusId());
        try {
            if (created) {
                if (input.baseVersion() != null) {
                    throw FinanceValidation.badRequest(
                            "baseVersao não deve ser enviada ao criar uma nota fiscal."
                    );
                }
                jdbc.update(
                        """
                        INSERT INTO finance_nota_fiscal (
                            id, client_mutation_id, obra_id, fornecedor_id,
                            centro_custo_id, categoria_id, responsavel_id,
                            status_id, numero, serie, chave_acesso,
                            tipo_documento, data_emissao, data_competencia,
                            data_recebimento, vencimento_em, moeda,
                            valor_bruto, desconto, acrescimo, retencoes,
                            valor_liquido, observacoes, criado_por
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                                  ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        input.id(), input.mutationId(), input.obraId(),
                        input.supplierId(), input.costCenterId(),
                        input.categoryId(), input.responsibleId(),
                        input.statusId(), input.number(), input.series(),
                        input.accessKey(), input.documentType(),
                        input.issueDate(), input.competenceDate(),
                        input.receivedDate(), input.dueDate(), input.currency(),
                        input.gross(), input.discount(), input.addition(),
                        input.withholding(), input.net(), input.notes(),
                        audit.actorId()
                );
            } else {
                requireSameWorksite(previous.obraId(), input.obraId());
                requireVersion(previous.versao(), input.baseVersion());
                if (!previous.statusId().equals(input.statusId())) {
                    requireTransition(
                            input.obraId(), previous.statusId(), input.statusId()
                    );
                }
                int changed = jdbc.update(
                        """
                        UPDATE finance_nota_fiscal
                        SET fornecedor_id = ?, centro_custo_id = ?,
                            categoria_id = ?, responsavel_id = ?, status_id = ?,
                            numero = ?, serie = ?, chave_acesso = ?,
                            tipo_documento = ?, data_emissao = ?,
                            data_competencia = ?, data_recebimento = ?,
                            vencimento_em = ?, moeda = ?, valor_bruto = ?,
                            desconto = ?, acrescimo = ?, retencoes = ?,
                            valor_liquido = ?, observacoes = ?,
                            atualizado_por = ?, versao_linha = versao_linha + 1
                        WHERE id = ? AND versao_linha = ?
                          AND arquivado_em IS NULL
                        """,
                        input.supplierId(), input.costCenterId(),
                        input.categoryId(), input.responsibleId(),
                        input.statusId(), input.number(), input.series(),
                        input.accessKey(), input.documentType(),
                        input.issueDate(), input.competenceDate(),
                        input.receivedDate(), input.dueDate(), input.currency(),
                        input.gross(), input.discount(), input.addition(),
                        input.withholding(), input.net(), input.notes(),
                        audit.actorId(), input.id(), input.baseVersion()
                );
                if (changed != 1) {
                    throw conflict("A nota fiscal foi alterada em outro dispositivo.");
                }
            }
            syncPurchaseLinks(input, audit.actorId());
        } catch (DataIntegrityViolationException exception) {
            throw conflict(
                    "A nota fiscal conflita com outro registro ou referência da obra."
            );
        }

        InvoiceResponse saved = get(input.id());
        recordHistory(
                saved,
                created ? "NOTA_FISCAL_CRIADA" : "NOTA_FISCAL_ATUALIZADA",
                audit,
                input.mutationId(),
                previous == null ? Map.of() : state(previous),
                state(saved)
        );
        if (created || !previous.statusId().equals(saved.statusId())) {
            recordStatus(previous, saved, audit, input.mutationId());
        }
        ontology.success(
                "NOTA_FISCAL",
                saved.id(),
                saved.numero(),
                created ? "NOTA_FISCAL_CRIADA" : "NOTA_FISCAL_ATUALIZADA",
                saved.obraId(),
                audit,
                previous == null ? Map.of() : state(previous),
                state(saved),
                related(saved)
        );
        return saved;
    }

    public InvoiceResponse get(String id) {
        InvoiceResponse response = find(FinanceValidation.uuid(id, "id"));
        if (response == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Nota fiscal não encontrada."
            );
        }
        return response;
    }

    public List<InvoiceResponse> list(InvoiceFilter filter) {
        InvoiceFilter value = filter.normalized();
        StringBuilder sql = new StringBuilder(INVOICE_SELECT)
                .append(" WHERE nf.obra_id = ? AND nf.arquivado_em IS NULL ");
        List<Object> args = new ArrayList<>();
        args.add(value.obraId());
        if (value.from() != null) {
            sql.append(" AND nf.data_emissao >= ? ");
            args.add(value.from());
        }
        if (value.to() != null) {
            sql.append(" AND nf.data_emissao <= ? ");
            args.add(value.to());
        }
        appendEquals(sql, args, "nf.responsavel_id", value.responsibleId());
        appendEquals(sql, args, "nf.fornecedor_id", value.supplierId());
        appendEquals(sql, args, "nf.status_id", value.statusId());
        appendEquals(sql, args, "nf.centro_custo_id", value.costCenterId());
        appendEquals(sql, args, "nf.categoria_id", value.categoryId());
        if (value.query() != null) {
            sql.append("""
                     AND (
                        LOWER(nf.numero) LIKE ?
                        OR LOWER(nf.serie) LIKE ?
                        OR LOWER(COALESCE(nf.chave_acesso, '')) LIKE ?
                        OR LOWER(f.razao_social) LIKE ?
                        OR f.cnpj_normalizado LIKE ?
                     )
                    """);
            String like = "%" + value.query().toLowerCase(Locale.ROOT) + "%";
            for (int index = 0; index < 5; index++) {
                args.add(like);
            }
        }
        sql.append(" ORDER BY nf.data_emissao DESC, nf.id DESC LIMIT ? OFFSET ?");
        args.add(value.limit());
        args.add(value.offset());
        return jdbc.query(sql.toString(), this::mapInvoice, args.toArray());
    }

    @Transactional
    public InvoiceDocumentResponse linkDocument(
            String invoiceId,
            DocumentLinkRequest request,
            FinanceAuditContext audit
    ) {
        requireActor(audit);
        InvoiceResponse invoice = get(invoiceId);
        if (request == null) {
            throw FinanceValidation.badRequest("Documento é obrigatório.");
        }
        String confirmationMutation = FinanceValidation.mutationId(
                request.clientMutationId()
        );
        FinanceAuditContext context = new FinanceAuditContext(
                audit.actorId(),
                optionalTrace(request.dispositivoId(), "dispositivoId"),
                audit.correlationId(),
                audit.origin()
        );
        String objectId = FinanceValidation.uuid(
                request.storedObjectId(), "storedObjectId"
        );
        String extractionJobId = FinanceValidation.uuid(
                request.extracaoJobId(), "extracaoJobId"
        );
        String clientHash = optionalHash(request.sha256Cliente());
        String documentType = FinanceInvoiceValidation.enumValue(
                request.tipoDocumento(), "tipoDocumento", DOCUMENT_TYPES
        );

        InvoiceDocumentResponse replay = documentByConfirmationMutation(
                context.actorId(), confirmationMutation
        );
        if (replay != null) {
            if (!replay.notaFiscalId().equals(invoice.id())
                    || !replay.storedObjectId().equals(objectId)
                    || !Objects.equals(replay.extracaoJobId(), extractionJobId)
                    || !replay.tipoDocumento().equals(documentType)
                    || replay.principal() != request.principal()
                    || (clientHash != null
                    && !clientHash.equals(replay.sha256Cliente()))) {
                throw conflict(
                        "clientMutationId já foi usado para outro vínculo fiscal."
                );
            }
            return replay;
        }

        FiscalDocumentTrace trace = fiscalDocumentTrace(
                extractionJobId, objectId, invoice.obraId()
        );
        if (clientHash != null && !clientHash.equals(trace.serverSha256())) {
            throw conflict(
                    "O hash informado não corresponde ao documento inspecionado."
            );
        }
        if (!List.of("EXTRAIDO", "REVISAO_NECESSARIA")
                .contains(trace.status())) {
            throw FinanceValidation.badRequest(
                    "Somente extração concluída ou revisável pode ser vinculada."
            );
        }
        Integer available = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM stored_object
                WHERE id = ? AND obra_id = ? AND status = 'DISPONIVEL'
                  AND arquivado_em IS NULL AND sha256 = ?
                """,
                Integer.class,
                objectId,
                invoice.obraId(),
                trace.serverSha256()
        );
        if (available == null || available != 1) {
            throw FinanceValidation.badRequest(
                    "O objeto não está disponível nesta obra."
            );
        }
        String existingId = jdbc.query(
                """
                SELECT id FROM finance_nota_fiscal_documento
                WHERE nota_fiscal_id = ? AND stored_object_id = ?
                LIMIT 1
                """,
                rs -> rs.next() ? rs.getString(1) : null,
                invoice.id(),
                objectId
        );
        String id = existingId == null
                ? optionalId(request.id())
                : existingId;
        if (request.principal()) {
            jdbc.update(
                    """
                    UPDATE finance_nota_fiscal_documento
                    SET principal = FALSE, versao_linha = versao_linha + 1
                    WHERE nota_fiscal_id = ? AND principal = TRUE
                      AND arquivado_em IS NULL
                    """,
                    invoice.id()
            );
        }
        if (existingId == null) {
            jdbc.update(
                    """
                    INSERT INTO finance_nota_fiscal_documento (
                        id, nota_fiscal_id, obra_id, stored_object_id,
                        tipo_documento, principal, criado_por,
                        enviado_por, enviado_em, dispositivo_id,
                        client_mutation_id, sha256_cliente, sha256_servidor,
                        inspecao_status, extracao_job_id, extrator_versao,
                        confirmado_por, confirmado_em,
                        confirmacao_client_mutation_id,
                        confirmacao_dispositivo_id,
                        confirmacao_correlacao_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                              'APROVADO', ?, ?, ?, CURRENT_TIMESTAMP(6),
                              ?, ?, ?)
                    """,
                    id, invoice.id(), invoice.obraId(), objectId,
                    documentType, request.principal(), context.actorId(),
                    trace.uploadedBy(), trace.uploadedAt(), trace.deviceId(),
                    trace.clientMutationId(), trace.clientSha256(),
                    trace.serverSha256(), trace.id(), trace.extractorVersion(),
                    context.actorId(), confirmationMutation,
                    context.deviceId(), context.correlationId()
            );
        } else {
            jdbc.update(
                    """
                    UPDATE finance_nota_fiscal_documento
                    SET tipo_documento = ?, principal = ?,
                        enviado_por = ?, enviado_em = ?, dispositivo_id = ?,
                        client_mutation_id = ?, sha256_cliente = ?,
                        sha256_servidor = ?, inspecao_status = 'APROVADO',
                        extracao_job_id = ?, extrator_versao = ?,
                        confirmado_por = ?, confirmado_em = CURRENT_TIMESTAMP(6),
                        confirmacao_client_mutation_id = ?,
                        confirmacao_dispositivo_id = ?,
                        confirmacao_correlacao_id = ?,
                        arquivado_por = NULL, arquivado_em = NULL,
                        versao_linha = versao_linha + 1
                    WHERE id = ?
                    """,
                    documentType, request.principal(), trace.uploadedBy(),
                    trace.uploadedAt(), trace.deviceId(),
                    trace.clientMutationId(), trace.clientSha256(),
                    trace.serverSha256(), trace.id(), trace.extractorVersion(),
                    context.actorId(), confirmationMutation,
                    context.deviceId(), context.correlationId(), id
            );
        }
        jdbc.update(
                """
                UPDATE finance_nota_fiscal
                SET atualizado_por = ?, versao_linha = versao_linha + 1
                WHERE id = ? AND arquivado_em IS NULL
                """,
                context.actorId(), invoice.id()
        );
        InvoiceResponse updated = get(invoice.id());
        InvoiceDocumentResponse linked = document(id);
        Map<String, Object> updatedState = new LinkedHashMap<>(state(updated));
        updatedState.put("documentoFiscalId", linked.extracaoJobId());
        updatedState.put("storedObjectId", linked.storedObjectId());
        updatedState.put("enviadoPor", linked.enviadoPor());
        updatedState.put("confirmadoPor", linked.confirmadoPor());
        updatedState.put("sha256Servidor", linked.sha256Servidor());
        recordHistory(
                updated,
                "DOCUMENTO_VINCULADO",
                context,
                confirmationMutation,
                state(invoice),
                updatedState
        );
        ontology.successWithRelations(
                "NOTA_FISCAL",
                updated.id(),
                updated.numero(),
                "DOCUMENTO_FISCAL_VINCULADO",
                updated.obraId(),
                context,
                state(invoice),
                updatedState,
                List.of(new FinanceOntologyRelation(
                        "DOCUMENTO_FISCAL",
                        extractionJobId,
                        "DOCUMENTADA_POR",
                        "Vínculo confirmado com hashes e autoria persistidos."
                ))
        );
        return linked;
    }

    public List<InvoiceDocumentResponse> documents(
            String invoiceId,
            String obraId
    ) {
        InvoiceResponse invoice = get(invoiceId);
        requireSameWorksite(invoice.obraId(), FinanceValidation.uuid(obraId, "obraId"));
        return jdbc.query(
                """
                SELECT d.*, o.nome_original, o.media_type_detectado,
                       o.tamanho_bytes
                FROM finance_nota_fiscal_documento d
                JOIN stored_object o ON o.id = d.stored_object_id
                WHERE d.nota_fiscal_id = ? AND d.arquivado_em IS NULL
                  AND o.status = 'DISPONIVEL'
                ORDER BY d.principal DESC, d.criado_em, d.id
                """,
                this::mapDocument,
                invoice.id()
        );
    }

    private InvoiceDocumentResponse documentByConfirmationMutation(
            String actorId,
            String clientMutationId
    ) {
        List<InvoiceDocumentResponse> values = jdbc.query(
                """
                SELECT d.*, o.nome_original, o.media_type_detectado,
                       o.tamanho_bytes
                FROM finance_nota_fiscal_documento d
                JOIN stored_object o ON o.id = d.stored_object_id
                WHERE d.confirmado_por = ?
                  AND d.confirmacao_client_mutation_id = ?
                  AND d.arquivado_em IS NULL
                LIMIT 1
                """,
                this::mapDocument,
                actorId,
                clientMutationId
        );
        return values.isEmpty() ? null : values.getFirst();
    }

    private FiscalDocumentTrace fiscalDocumentTrace(
            String jobId,
            String objectId,
            String worksiteId
    ) {
        List<FiscalDocumentTrace> values = jdbc.query(
                """
                SELECT j.id, j.stored_object_id, j.obra_id,
                       j.client_mutation_id, j.status, j.extrator_versao,
                       j.sha256_cliente, j.sha256_servidor, j.criado_por,
                       j.dispositivo_id, o.proprietario_id, o.criado_em
                FROM finance_documento_extracao_job j
                JOIN stored_object o
                  ON o.id = j.stored_object_id AND o.obra_id = j.obra_id
                WHERE j.id = ? AND j.stored_object_id = ? AND j.obra_id = ?
                  AND o.status = 'DISPONIVEL' AND o.arquivado_em IS NULL
                """,
                (rs, row) -> new FiscalDocumentTrace(
                        rs.getString("id"),
                        rs.getString("stored_object_id"),
                        rs.getString("obra_id"),
                        rs.getString("client_mutation_id"),
                        rs.getString("status"),
                        rs.getString("extrator_versao"),
                        rs.getString("sha256_cliente"),
                        rs.getString("sha256_servidor"),
                        rs.getString("criado_por"),
                        rs.getString("dispositivo_id"),
                        rs.getTimestamp("criado_em").toLocalDateTime(),
                        rs.getString("proprietario_id")
                ),
                jobId,
                objectId,
                worksiteId
        );
        if (values.isEmpty()) {
            throw FinanceValidation.badRequest(
                    "A extração, o objeto e a obra não são compatíveis."
            );
        }
        FiscalDocumentTrace trace = values.getFirst();
        if (!trace.serverSha256().matches("[0-9a-f]{64}")
                || !trace.uploadedBy().equals(trace.objectOwner())) {
            throw conflict(
                    "A autoria ou a integridade do documento não foi confirmada."
            );
        }
        return trace;
    }

    public String documentObjectId(
            String invoiceId,
            String documentId,
            String obraId
    ) {
        String normalizedInvoice = FinanceValidation.uuid(invoiceId, "id");
        String normalizedDocument = FinanceValidation.uuid(
                documentId, "documentId"
        );
        String normalizedWorksite = FinanceValidation.uuid(obraId, "obraId");
        String objectId = jdbc.query(
                """
                SELECT d.stored_object_id
                FROM finance_nota_fiscal_documento d
                JOIN finance_nota_fiscal nf
                  ON nf.id = d.nota_fiscal_id AND nf.obra_id = d.obra_id
                JOIN stored_object o ON o.id = d.stored_object_id
                WHERE d.id = ? AND d.nota_fiscal_id = ? AND d.obra_id = ?
                  AND d.arquivado_em IS NULL AND nf.arquivado_em IS NULL
                  AND o.status = 'DISPONIVEL'
                LIMIT 1
                """,
                rs -> rs.next() ? rs.getString(1) : null,
                normalizedDocument,
                normalizedInvoice,
                normalizedWorksite
        );
        if (objectId == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Documento fiscal não encontrado."
            );
        }
        return objectId;
    }

    @Transactional
    public void archive(
            String invoiceId,
            long baseVersion,
            String mutationId,
            FinanceAuditContext audit
    ) {
        requireActor(audit);
        String normalizedMutation = FinanceValidation.mutationId(mutationId);
        String replayId = mutationEntity(audit.actorId(), normalizedMutation);
        if (replayId != null) {
            if (!replayId.equals(invoiceId)) {
                throw conflict(
                        "clientMutationId já foi usado para outra nota fiscal."
                );
            }
            return;
        }
        InvoiceResponse invoice = get(invoiceId);
        requireVersion(invoice.versao(), baseVersion);
        int changed = jdbc.update(
                """
                UPDATE finance_nota_fiscal
                SET arquivado_por = ?, arquivado_em = CURRENT_TIMESTAMP(6),
                    atualizado_por = ?, versao_linha = versao_linha + 1
                WHERE id = ? AND versao_linha = ? AND arquivado_em IS NULL
                """,
                audit.actorId(), audit.actorId(), invoice.id(), baseVersion
        );
        if (changed != 1) {
            throw conflict("A nota fiscal foi alterada em outro dispositivo.");
        }
        Map<String, Object> archived = new LinkedHashMap<>(state(invoice));
        archived.put("arquivado", true);
        recordHistory(
                invoice,
                "NOTA_FISCAL_ARQUIVADA",
                audit,
                normalizedMutation,
                state(invoice),
                archived
        );
        ontology.success(
                "NOTA_FISCAL",
                invoice.id(),
                invoice.numero(),
                "NOTA_FISCAL_ARQUIVADA",
                invoice.obraId(),
                audit,
                state(invoice),
                archived,
                related(invoice)
        );
    }

    private ValidatedInvoice validate(InvoiceRequest request) {
        if (request == null) {
            throw FinanceValidation.badRequest("Nota fiscal é obrigatória.");
        }
        LocalDate issueDate = request.dataEmissao();
        if (issueDate == null) {
            throw FinanceValidation.badRequest("dataEmissao é obrigatória.");
        }
        if (request.vencimentoEm() != null
                && request.vencimentoEm().isBefore(issueDate)) {
            throw FinanceValidation.badRequest(
                    "vencimentoEm não pode ser anterior à emissão."
            );
        }
        BigDecimal net = FinanceInvoiceValidation.invoiceTotal(
                request.valorBruto(), request.desconto(), request.acrescimo(),
                request.retencoes(), request.valorLiquido()
        );
        List<ValidatedPurchaseLink> purchases = validatePurchaseLinks(
                request.compras(), net
        );
        return new ValidatedInvoice(
                optionalId(request.id()),
                FinanceValidation.mutationId(request.clientMutationId()),
                FinanceValidation.uuid(request.obraId(), "obraId"),
                FinanceValidation.uuid(request.fornecedorId(), "fornecedorId"),
                FinanceValidation.optionalUuid(
                        request.centroCustoId(), "centroCustoId"
                ),
                FinanceValidation.optionalUuid(
                        request.categoriaId(), "categoriaId"
                ),
                FinanceValidation.uuid(request.responsavelId(), "responsavelId"),
                FinanceValidation.uuid(request.statusId(), "statusId"),
                FinanceValidation.requiredText(request.numero(), "numero", 80),
                request.serie() == null || request.serie().isBlank()
                        ? ""
                        : FinanceValidation.requiredText(
                                request.serie(), "serie", 40
                        ),
                FinanceInvoiceValidation.accessKey(request.chaveAcesso()),
                FinanceInvoiceValidation.enumValue(
                        request.tipoDocumento(), "tipoDocumento", INVOICE_TYPES
                ),
                issueDate, request.dataCompetencia(), request.dataRecebimento(),
                request.vencimentoEm(),
                FinanceValidation.currency(request.moeda()),
                FinanceValidation.money(request.valorBruto(), "valorBruto"),
                FinanceInvoiceValidation.zeroIfNull(
                        request.desconto(), "desconto"
                ),
                FinanceInvoiceValidation.zeroIfNull(
                        request.acrescimo(), "acrescimo"
                ),
                FinanceInvoiceValidation.zeroIfNull(
                        request.retencoes(), "retencoes"
                ),
                net,
                FinanceValidation.optionalText(
                        request.observacoes(), "observacoes", 4000
                ),
                purchases,
                request.baseVersao()
        );
    }

    private List<ValidatedPurchaseLink> validatePurchaseLinks(
            List<PurchaseLinkRequest> requests,
            BigDecimal invoiceNet
    ) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        Set<String> purchaseIds = new HashSet<>();
        List<ValidatedPurchaseLink> links = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (PurchaseLinkRequest request : requests) {
            if (request == null) {
                throw FinanceValidation.badRequest(
                        "Vínculo de compra inválido."
                );
            }
            String purchaseId = FinanceValidation.uuid(
                    request.compraId(), "compraId"
            );
            if (!purchaseIds.add(purchaseId)) {
                throw FinanceValidation.badRequest(
                        "A mesma compra não pode ser vinculada duas vezes."
                );
            }
            BigDecimal value = FinanceValidation.money(
                    request.valorVinculado(), "valorVinculado"
            );
            if (value.signum() <= 0) {
                throw FinanceValidation.badRequest(
                        "valorVinculado deve ser maior que zero."
                );
            }
            total = total.add(value);
            links.add(new ValidatedPurchaseLink(
                    request.id() == null || request.id().isBlank()
                            ? null
                            : FinanceValidation.uuid(request.id(), "id"),
                    purchaseId,
                    value
            ));
        }
        if (total.compareTo(invoiceNet) > 0) {
            throw FinanceValidation.badRequest(
                    "O valor vinculado a compras não pode superar o valor líquido da nota."
            );
        }
        return List.copyOf(links);
    }

    private void syncPurchaseLinks(ValidatedInvoice input, String actorId) {
        Map<String, String> existing = new LinkedHashMap<>();
        jdbc.query(
                "SELECT id, compra_id FROM finance_nota_fiscal_compra WHERE nota_fiscal_id = ?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        existing.put(
                                rs.getString("compra_id"),
                                rs.getString("id")
                        ),
                input.id()
        );
        Set<String> kept = new HashSet<>();
        for (ValidatedPurchaseLink link : input.purchases()) {
            String existingId = existing.get(link.purchaseId());
            String id = existingId == null
                    ? (link.id() == null ? UUID.randomUUID().toString() : link.id())
                    : existingId;
            if (existingId == null) {
                jdbc.update(
                        """
                        INSERT INTO finance_nota_fiscal_compra (
                            id, nota_fiscal_id, compra_id, obra_id,
                            valor_vinculado, criado_por
                        ) VALUES (?, ?, ?, ?, ?, ?)
                        """,
                        id, input.id(), link.purchaseId(), input.obraId(),
                        link.value(), actorId
                );
            } else {
                jdbc.update(
                        """
                        UPDATE finance_nota_fiscal_compra
                        SET valor_vinculado = ?, arquivado_por = NULL,
                            arquivado_em = NULL,
                            versao_linha = versao_linha + 1
                        WHERE id = ?
                        """,
                        link.value(), id
                );
            }
            kept.add(link.purchaseId());
        }
        for (Map.Entry<String, String> entry : existing.entrySet()) {
            if (!kept.contains(entry.getKey())) {
                jdbc.update(
                        """
                        UPDATE finance_nota_fiscal_compra
                        SET arquivado_por = ?, arquivado_em = CURRENT_TIMESTAMP(6),
                            versao_linha = versao_linha + 1
                        WHERE id = ? AND arquivado_em IS NULL
                        """,
                        actorId, entry.getValue()
                );
            }
        }
    }

    private InvoiceResponse find(String id) {
        List<InvoiceResponse> values = jdbc.query(
                INVOICE_SELECT
                        + " WHERE nf.id = ? AND nf.arquivado_em IS NULL",
                this::mapInvoice,
                id
        );
        return values.isEmpty() ? null : values.getFirst();
    }

    private InvoiceResponse mapInvoice(ResultSet rs, int row) throws SQLException {
        String id = rs.getString("id");
        return new InvoiceResponse(
                id,
                rs.getString("obra_id"),
                rs.getString("fornecedor_id"),
                rs.getString("fornecedor_nome"),
                rs.getString("fornecedor_cnpj"),
                rs.getString("centro_custo_id"),
                rs.getString("categoria_id"),
                rs.getString("responsavel_id"),
                rs.getString("responsavel_nome"),
                rs.getString("status_id"),
                rs.getString("status_codigo"),
                rs.getString("status_nome"),
                rs.getString("numero"),
                rs.getString("serie"),
                rs.getString("chave_acesso"),
                rs.getString("tipo_documento"),
                date(rs, "data_emissao"),
                date(rs, "data_competencia"),
                date(rs, "data_recebimento"),
                date(rs, "vencimento_em"),
                rs.getString("moeda"),
                rs.getBigDecimal("valor_bruto"),
                rs.getBigDecimal("desconto"),
                rs.getBigDecimal("acrescimo"),
                rs.getBigDecimal("retencoes"),
                rs.getBigDecimal("valor_liquido"),
                rs.getString("ocr_status"),
                rs.getString("observacoes"),
                purchaseLinks(id),
                rs.getLong("versao_linha"),
                rs.getTimestamp("criado_em").toLocalDateTime(),
                rs.getTimestamp("atualizado_em").toLocalDateTime()
        );
    }

    private List<PurchaseLinkResponse> purchaseLinks(String invoiceId) {
        return jdbc.query(
                """
                SELECT id, compra_id, valor_vinculado
                FROM finance_nota_fiscal_compra
                WHERE nota_fiscal_id = ? AND arquivado_em IS NULL
                ORDER BY criado_em, id
                """,
                (rs, row) -> new PurchaseLinkResponse(
                        rs.getString("id"),
                        rs.getString("compra_id"),
                        rs.getBigDecimal("valor_vinculado")
                ),
                invoiceId
        );
    }

    private InvoiceDocumentResponse document(String id) {
        List<InvoiceDocumentResponse> values = jdbc.query(
                """
                SELECT d.*, o.nome_original, o.media_type_detectado,
                       o.tamanho_bytes
                FROM finance_nota_fiscal_documento d
                JOIN stored_object o ON o.id = d.stored_object_id
                WHERE d.id = ? AND d.arquivado_em IS NULL
                """,
                this::mapDocument,
                id
        );
        if (values.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Documento fiscal não encontrado."
            );
        }
        return values.getFirst();
    }

    private InvoiceDocumentResponse mapDocument(ResultSet rs, int row)
            throws SQLException {
        return new InvoiceDocumentResponse(
                rs.getString("id"),
                rs.getString("nota_fiscal_id"),
                rs.getString("stored_object_id"),
                rs.getString("tipo_documento"),
                rs.getBoolean("principal"),
                rs.getString("nome_original"),
                rs.getString("media_type_detectado"),
                rs.getLong("tamanho_bytes"),
                rs.getString("enviado_por"),
                rs.getTimestamp("enviado_em").toLocalDateTime(),
                rs.getString("dispositivo_id"),
                rs.getString("client_mutation_id"),
                rs.getString("sha256_cliente"),
                rs.getString("sha256_servidor"),
                rs.getString("inspecao_status"),
                rs.getString("extracao_job_id"),
                rs.getString("extrator_versao"),
                rs.getString("confirmado_por"),
                rs.getTimestamp("confirmado_em").toLocalDateTime(),
                rs.getString("confirmacao_client_mutation_id"),
                rs.getString("confirmacao_dispositivo_id"),
                rs.getString("confirmacao_correlacao_id"),
                rs.getTimestamp("criado_em").toLocalDateTime()
        );
    }

    private String optionalHash(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (!SHA256.matcher(normalized).matches()) {
            throw FinanceValidation.badRequest(
                    "sha256Cliente deve conter 64 caracteres hexadecimais."
            );
        }
        return normalized;
    }

    private String optionalTrace(String value, String field) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        if (normalized.length() > 120) {
            throw FinanceValidation.badRequest(
                    field + " deve ter até 120 caracteres."
            );
        }
        return normalized;
    }

    private void requireStatus(String worksiteId, String statusId) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM finance_status_definicao
                WHERE id = ? AND obra_id = ?
                  AND agregado_tipo = 'NOTA_FISCAL'
                  AND ativo = TRUE AND arquivado_em IS NULL
                """,
                Integer.class,
                statusId,
                worksiteId
        );
        if (count == null || count != 1) {
            throw FinanceValidation.badRequest(
                    "Status de nota fiscal inválido para esta obra."
            );
        }
    }

    private void requireTransition(
            String worksiteId,
            String fromStatus,
            String toStatus
    ) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM finance_status_transicao
                WHERE obra_id = ? AND agregado_tipo = 'NOTA_FISCAL'
                  AND status_origem_id = ? AND status_destino_id = ?
                  AND ativo = TRUE
                """,
                Integer.class,
                worksiteId,
                fromStatus,
                toStatus
        );
        if (count == null || count != 1) {
            throw conflict("A transição de status não está autorizada.");
        }
    }

    private void recordHistory(
            InvoiceResponse invoice,
            String action,
            FinanceAuditContext audit,
            String mutationId,
            Map<String, ?> before,
            Map<String, ?> after
    ) {
        jdbc.update(
                """
                INSERT INTO finance_nota_fiscal_historico (
                    id, nota_fiscal_id, obra_id, acao, ator_id, origem,
                    dispositivo_id, client_mutation_id, correlacao_id,
                    estado_anterior_json, estado_novo_json, resultado
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SUCESSO')
                """,
                UUID.randomUUID().toString(),
                invoice.id(), invoice.obraId(), action, audit.actorId(),
                audit.origin(), audit.deviceId(), mutationId,
                audit.correlationId(), json(before), json(after)
        );
    }

    private void recordStatus(
            InvoiceResponse previous,
            InvoiceResponse saved,
            FinanceAuditContext audit,
            String mutationId
    ) {
        jdbc.update(
                """
                INSERT INTO finance_status_historico (
                    id, entidade_tipo, entidade_id, obra_id,
                    status_anterior_id, status_novo_id, ator_id, origem,
                    dispositivo_id, client_mutation_id, correlacao_id,
                    estado_anterior_json, estado_novo_json, resultado
                ) VALUES (?, 'NOTA_FISCAL', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                          'SUCESSO')
                """,
                UUID.randomUUID().toString(),
                saved.id(), saved.obraId(),
                previous == null ? null : previous.statusId(),
                saved.statusId(), audit.actorId(), audit.origin(),
                audit.deviceId(), mutationId, audit.correlationId(),
                json(previous == null ? Map.of() : state(previous)),
                json(state(saved))
        );
    }

    private String mutationEntity(String actorId, String mutationId) {
        return jdbc.query(
                """
                SELECT nota_fiscal_id
                FROM finance_nota_fiscal_historico
                WHERE ator_id = ? AND client_mutation_id = ?
                LIMIT 1
                """,
                rs -> rs.next() ? rs.getString(1) : null,
                actorId,
                mutationId
        );
    }

    private boolean sameInvoice(
            InvoiceResponse existing,
            ValidatedInvoice input
    ) {
        return existing.obraId().equals(input.obraId())
                && existing.fornecedorId().equals(input.supplierId())
                && Objects.equals(existing.centroCustoId(), input.costCenterId())
                && Objects.equals(existing.categoriaId(), input.categoryId())
                && existing.responsavelId().equals(input.responsibleId())
                && existing.statusId().equals(input.statusId())
                && existing.numero().equals(input.number())
                && existing.serie().equals(input.series())
                && Objects.equals(existing.chaveAcesso(), input.accessKey())
                && existing.tipoDocumento().equals(input.documentType())
                && existing.dataEmissao().equals(input.issueDate())
                && Objects.equals(existing.dataCompetencia(), input.competenceDate())
                && Objects.equals(existing.dataRecebimento(), input.receivedDate())
                && Objects.equals(existing.vencimentoEm(), input.dueDate())
                && existing.moeda().equals(input.currency())
                && existing.valorBruto().compareTo(input.gross()) == 0
                && existing.desconto().compareTo(input.discount()) == 0
                && existing.acrescimo().compareTo(input.addition()) == 0
                && existing.retencoes().compareTo(input.withholding()) == 0
                && existing.valorLiquido().compareTo(input.net()) == 0
                && Objects.equals(existing.observacoes(), input.notes())
                && samePurchaseLinks(existing.compras(), input.purchases());
    }

    private boolean samePurchaseLinks(
            List<PurchaseLinkResponse> existing,
            List<ValidatedPurchaseLink> requested
    ) {
        if (existing.size() != requested.size()) {
            return false;
        }
        Map<String, PurchaseLinkResponse> byPurchase = new LinkedHashMap<>();
        for (PurchaseLinkResponse link : existing) {
            byPurchase.put(link.compraId(), link);
        }
        for (ValidatedPurchaseLink link : requested) {
            PurchaseLinkResponse current = byPurchase.get(link.purchaseId());
            if (current == null
                    || current.valorVinculado().compareTo(link.value()) != 0
                    || (link.id() != null && !link.id().equals(current.id()))) {
                return false;
            }
        }
        return true;
    }

    private Map<String, Object> state(InvoiceResponse value) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", value.id());
        state.put("obraId", value.obraId());
        state.put("fornecedorId", value.fornecedorId());
        state.put("statusId", value.statusId());
        state.put("statusCodigo", value.statusCodigo());
        state.put("numero", value.numero());
        state.put("serie", value.serie());
        state.put("moeda", value.moeda());
        state.put("valorLiquido", value.valorLiquido());
        state.put("vencimentoEm", value.vencimentoEm());
        state.put("versao", value.versao());
        return state;
    }

    private Map<String, String> related(InvoiceResponse value) {
        Map<String, String> related = new LinkedHashMap<>();
        related.put("FORNECEDOR", value.fornecedorId());
        if (value.centroCustoId() != null) {
            related.put("CENTRO_CUSTO", value.centroCustoId());
        }
        if (value.categoriaId() != null) {
            related.put("CATEGORIA_FINANCEIRA", value.categoriaId());
        }
        related.put("RESPONSAVEL", value.responsavelId());
        return related;
    }

    private void appendEquals(
            StringBuilder sql,
            List<Object> args,
            String column,
            String value
    ) {
        if (value != null) {
            sql.append(" AND ").append(column).append(" = ? ");
            args.add(value);
        }
    }

    private void requireActor(FinanceAuditContext audit) {
        String actor = currentUser.requireUserId();
        if (audit == null || !actor.equals(audit.actorId())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Ator financeiro divergente da sessão."
            );
        }
    }

    private void requireSameWorksite(String actual, String requested) {
        if (!actual.equals(requested)) {
            throw conflict("A nota fiscal pertence a outra obra.");
        }
    }

    private void requireVersion(long actual, Long requested) {
        if (requested == null || actual != requested) {
            throw conflict("A versão da nota fiscal está desatualizada.");
        }
    }

    private void requireVersion(long actual, long requested) {
        requireVersion(actual, Long.valueOf(requested));
    }

    private String optionalId(String value) {
        return value == null || value.isBlank()
                ? UUID.randomUUID().toString()
                : FinanceValidation.uuid(value, "id");
    }

    private LocalDate date(ResultSet rs, String column) throws SQLException {
        return rs.getDate(column) == null
                ? null
                : rs.getDate(column).toLocalDate();
    }

    private String json(Map<String, ?> value) {
        try {
            return objectMapper.writeValueAsString(
                    value == null ? Map.of() : value
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Falha ao serializar histórico de nota fiscal.",
                    exception
            );
        }
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public record InvoiceFilter(
            String obraId,
            LocalDate from,
            LocalDate to,
            String responsibleId,
            String supplierId,
            String statusId,
            String costCenterId,
            String categoryId,
            String query,
            Integer limit,
            Integer offset
    ) {
        InvoiceFilter normalized() {
            String worksite = FinanceValidation.uuid(obraId, "obraId");
            if (from != null && to != null && to.isBefore(from)) {
                throw FinanceValidation.badRequest("Período inválido.");
            }
            return new InvoiceFilter(
                    worksite, from, to,
                    FinanceValidation.optionalUuid(
                            responsibleId, "responsavelId"
                    ),
                    FinanceValidation.optionalUuid(
                            supplierId, "fornecedorId"
                    ),
                    FinanceValidation.optionalUuid(statusId, "statusId"),
                    FinanceValidation.optionalUuid(
                            costCenterId, "centroCustoId"
                    ),
                    FinanceValidation.optionalUuid(categoryId, "categoriaId"),
                    FinanceValidation.optionalText(query, "query", 200),
                    limit == null ? 100 : Math.min(200, Math.max(1, limit)),
                    offset == null ? 0 : Math.max(0, offset)
            );
        }
    }

    private record ValidatedPurchaseLink(
            String id,
            String purchaseId,
            BigDecimal value
    ) {
    }

    private record FiscalDocumentTrace(
            String id,
            String storedObjectId,
            String obraId,
            String clientMutationId,
            String status,
            String extractorVersion,
            String clientSha256,
            String serverSha256,
            String uploadedBy,
            String deviceId,
            LocalDateTime uploadedAt,
            String objectOwner
    ) {
    }

    private record ValidatedInvoice(
            String id,
            String mutationId,
            String obraId,
            String supplierId,
            String costCenterId,
            String categoryId,
            String responsibleId,
            String statusId,
            String number,
            String series,
            String accessKey,
            String documentType,
            LocalDate issueDate,
            LocalDate competenceDate,
            LocalDate receivedDate,
            LocalDate dueDate,
            String currency,
            BigDecimal gross,
            BigDecimal discount,
            BigDecimal addition,
            BigDecimal withholding,
            BigDecimal net,
            String notes,
            List<ValidatedPurchaseLink> purchases,
            Long baseVersion
    ) {
    }
}
