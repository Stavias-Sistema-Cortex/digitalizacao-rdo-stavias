package com.projeto.cortex.financeiro.cobranca;

import static com.projeto.cortex.financeiro.cobranca.FinanceChargeDtos.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.financeiro.core.FinanceAuditContext;
import com.projeto.cortex.financeiro.core.FinanceOntologyProjector;
import com.projeto.cortex.financeiro.core.FinanceValidation;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("legacy-finance")
public class FinanceChargeService {

    private static final Set<String> CANONICAL_VARIABLES = Set.of(
            "obraNome",
            "fornecedorNome",
            "fornecedorCnpj",
            "numeroDocumento",
            "descricao",
            "moeda",
            "valorTotal",
            "valorAberto",
            "vencimento",
            "tipoAlvo"
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final FinanceChargeTargetReader targetReader;
    private final FinanceChargeTemplateRenderer renderer;
    private final FinanceEmailSenderConfiguration senderConfiguration;
    private final FinanceOntologyProjector ontology;

    public FinanceChargeService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            FinanceChargeTargetReader targetReader,
            FinanceEmailSenderConfiguration senderConfiguration,
            FinanceOntologyProjector ontology
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.targetReader = targetReader;
        this.renderer = new FinanceChargeTemplateRenderer();
        this.senderConfiguration = senderConfiguration;
        this.ontology = ontology;
    }

    public List<ChargeResponse> list(
            String obraId,
            String status,
            int limit,
            int offset
    ) {
        String worksite = FinanceValidation.uuid(obraId, "obraId");
        int safeLimit = Math.max(1, Math.min(limit, 200));
        int safeOffset = Math.max(0, offset);
        String normalizedStatus = status == null || status.isBlank()
                ? null : FinanceValidation.requiredText(status, "status", 30)
                        .toUpperCase(Locale.ROOT);
        return jdbcTemplate.query(
                """
                SELECT *
                FROM finance_cobranca_email
                WHERE obra_id = ?
                  AND (? IS NULL OR status = ?)
                ORDER BY ocorrencia_prevista_em DESC, id DESC
                LIMIT ? OFFSET ?
                """,
                this::mapCharge,
                worksite,
                normalizedStatus,
                normalizedStatus,
                safeLimit,
                safeOffset
        );
    }

    public ChargeResponse get(String id, String obraId) {
        String chargeId = FinanceValidation.uuid(id, "id");
        String worksite = FinanceValidation.uuid(obraId, "obraId");
        ChargeResponse response = jdbcTemplate.query(
                """
                SELECT *
                FROM finance_cobranca_email
                WHERE id = ? AND obra_id = ?
                LIMIT 1
                """,
                rs -> rs.next() ? mapCharge(rs, 0) : null,
                chargeId,
                worksite
        );
        if (response == null) {
            throw notFound();
        }
        return response;
    }

    @Transactional
    public ChargeResponse create(
            ChargeRequest request,
            String actorId,
            String correlationId
    ) {
        if (request == null) {
            throw FinanceValidation.badRequest("Cobrança é obrigatória.");
        }
        String actor = FinanceValidation.uuid(actorId, "atorId");
        String mutationId = FinanceValidation.mutationId(
                request.clientMutationId()
        );
        ChargeResponse existing = findByMutation(actor, mutationId);
        if (existing != null) {
            return existing;
        }
        String id = request.id() == null || request.id().isBlank()
                ? UUID.randomUUID().toString()
                : FinanceValidation.uuid(request.id(), "id");
        String obraId = FinanceValidation.uuid(request.obraId(), "obraId");
        String modelId = FinanceValidation.uuid(
                request.modeloEmailId(), "modeloEmailId"
        );
        String senderId = FinanceValidation.uuid(
                request.perfilRemetenteId(), "perfilRemetenteId"
        );
        String replyId = FinanceValidation.optionalUuid(
                request.replyToPermitidoId(), "replyToPermitidoId"
        );
        String mode = mode(request.modo(), false);
        LocalDateTime scheduledAt = request.agendadaPara();
        if ("AGENDADA".equals(mode) && scheduledAt == null) {
            throw FinanceValidation.badRequest(
                    "agendadaPara é obrigatória para cobrança agendada."
            );
        }
        if (!"AGENDADA".equals(mode) && scheduledAt != null) {
            throw FinanceValidation.badRequest(
                    "agendadaPara só pode ser usada no modo AGENDADA."
            );
        }

        FinanceChargeTargetReader.TargetData target = targetReader.read(
                obraId, request.alvoTipo(), request.alvoId()
        );
        TemplateData template = requireTemplate(modelId, obraId);
        requireSender(senderId);
        requireReplyTo(replyId, obraId);
        validateAllowedVariables(template.allowedVariables());

        String purchaseId = "COMPRA".equals(target.targetType())
                ? target.targetId() : null;
        String invoiceId = "NOTA_FISCAL".equals(target.targetType())
                ? target.targetId() : null;
        String ledgerId = "LANCAMENTO".equals(target.targetType())
                ? target.targetId() : null;
        LocalDateTime now = utcNow();
        String idempotencyKey = "finance-charge:" + id;
        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO finance_cobranca_email (
                        id, client_mutation_id, idempotency_key, obra_id,
                        regra_id, modelo_email_id, perfil_remetente_id,
                        reply_to_permitido_id, fornecedor_id, compra_id,
                        nota_fiscal_id, lancamento_id, responsavel_id, modo,
                        status, destinatario, vencimento_em,
                        ocorrencia_prevista_em, agendada_para,
                        dados_renderizacao_json, criado_por, atualizado_por
                    ) VALUES (?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                              'RASCUNHO', ?, ?, ?, ?, CAST(? AS JSON), ?, ?)
                    """,
                    id,
                    mutationId,
                    idempotencyKey,
                    obraId,
                    modelId,
                    senderId,
                    replyId,
                    target.supplierId(),
                    purchaseId,
                    invoiceId,
                    ledgerId,
                    target.responsibleId(),
                    mode,
                    target.recipient(),
                    target.dueDate(),
                    scheduledAt == null ? now : scheduledAt,
                    scheduledAt,
                    json(target.renderValues()),
                    actor,
                    actor
            );
        } catch (DataIntegrityViolationException exception) {
            ChargeResponse raced = findByMutation(actor, mutationId);
            if (raced != null) {
                return raced;
            }
            throw FinanceValidation.badRequest(
                    "A cobrança conflita com outro registro persistido."
            );
        }
        history(
                id,
                obraId,
                "CRIADA",
                actor,
                mutationId,
                correlationId,
                Map.of(),
                Map.of("status", "RASCUNHO", "modo", mode)
        );
        project(
                id,
                obraId,
                "COBRANCA_CRIADA",
                actor,
                correlationId,
                Map.of("status", "RASCUNHO", "modo", mode),
                target
        );
        return get(id, obraId);
    }

    @Transactional
    public PreviewResponse preview(
            String id,
            String obraId,
            String actorId,
            String correlationId
    ) {
        ChargeData charge = requireCharge(id, obraId, true);
        String actor = FinanceValidation.uuid(actorId, "atorId");
        if (Set.of("ENVIANDO", "ENVIADA", "ENTREGUE", "CANCELADA")
                .contains(charge.status())) {
            throw conflict("A cobrança não pode mais ser previsualizada.");
        }
        TemplateData template = requireTemplate(
                charge.modelId(), charge.obraId()
        );
        FinanceChargeTargetReader.TargetData target = targetReader.read(
                charge.obraId(), charge.targetType(), charge.targetId()
        );
        FinanceChargeTemplateRenderer.RenderedEmail rendered = renderer.render(
                template.subjectTemplate(),
                template.bodyTemplate(),
                template.allowedVariables(),
                target.renderValues()
        );
        LocalDateTime now = utcNow();
        jdbcTemplate.update(
                """
                UPDATE finance_cobranca_email
                SET destinatario = ?, vencimento_em = ?,
                    dados_renderizacao_json = CAST(? AS JSON),
                    previsualizacao_hash = ?,
                    previsualizacao_confirmada_por = ?,
                    previsualizacao_confirmada_em = ?,
                    status = 'PREVISUALIZADA',
                    erro_categoria = NULL, erro_sanitizado = NULL,
                    proxima_tentativa_em = NULL,
                    atualizado_por = ?, versao_linha = versao_linha + 1
                WHERE id = ? AND obra_id = ?
                """,
                target.recipient(),
                target.dueDate(),
                json(target.renderValues()),
                rendered.previewHash(),
                actor,
                now,
                actor,
                charge.id(),
                charge.obraId()
        );
        history(
                charge.id(), charge.obraId(), "PREVISUALIZADA", actor,
                null, correlationId,
                Map.of("status", charge.status()),
                Map.of(
                        "status", "PREVISUALIZADA",
                        "previsualizacaoHash", rendered.previewHash()
                )
        );
        project(
                charge.id(),
                charge.obraId(),
                "COBRANCA_PREVISUALIZADA",
                actor,
                correlationId,
                Map.of(
                        "status", "PREVISUALIZADA",
                        "previsualizacaoHash", rendered.previewHash()
                ),
                target
        );
        return new PreviewResponse(
                charge.id(),
                target.recipient(),
                replyToEmail(charge.replyToId(), charge.obraId()),
                rendered.subject(),
                rendered.textBody(),
                rendered.previewHash(),
                now
        );
    }

    @Transactional
    public ChargeResponse schedule(
            String id,
            String obraId,
            LocalDateTime scheduledAt,
            String actorId,
            String correlationId
    ) {
        ChargeData charge = requireCharge(id, obraId, true);
        String actor = FinanceValidation.uuid(actorId, "atorId");
        if (!"PREVISUALIZADA".equals(charge.status())) {
            throw conflict(
                    "Previsualize e confirme a cobrança antes de agendar."
            );
        }
        if (scheduledAt == null || !scheduledAt.isAfter(utcNow())) {
            throw FinanceValidation.badRequest(
                    "agendadaPara deve estar no futuro em UTC."
            );
        }
        jdbcTemplate.update(
                """
                UPDATE finance_cobranca_email
                SET modo = 'AGENDADA', status = 'AGENDADA',
                    agendada_para = ?, ocorrencia_prevista_em = ?,
                    proxima_tentativa_em = ?, atualizado_por = ?,
                    versao_linha = versao_linha + 1
                WHERE id = ? AND obra_id = ? AND status = 'PREVISUALIZADA'
                """,
                scheduledAt,
                scheduledAt,
                scheduledAt,
                actor,
                charge.id(),
                charge.obraId()
        );
        history(
                charge.id(), charge.obraId(), "AGENDADA", actor,
                null, correlationId,
                Map.of("status", "PREVISUALIZADA"),
                Map.of("status", "AGENDADA", "agendadaPara", scheduledAt)
        );
        return get(charge.id(), charge.obraId());
    }

    @Transactional
    public ChargeResponse queueNow(
            String id,
            String obraId,
            String actorId,
            String correlationId
    ) {
        ChargeData charge = requireCharge(id, obraId, true);
        String actor = FinanceValidation.uuid(actorId, "atorId");
        if (!"PREVISUALIZADA".equals(charge.status())) {
            throw conflict(
                    "Previsualize e confirme a cobrança antes de enviar."
            );
        }
        LocalDateTime now = utcNow();
        jdbcTemplate.update(
                """
                UPDATE finance_cobranca_email
                SET status = 'NA_FILA', proxima_tentativa_em = ?,
                    atualizado_por = ?, versao_linha = versao_linha + 1
                WHERE id = ? AND obra_id = ? AND status = 'PREVISUALIZADA'
                """,
                now,
                actor,
                charge.id(),
                charge.obraId()
        );
        history(
                charge.id(), charge.obraId(), "ENFILEIRADA", actor,
                null, correlationId,
                Map.of("status", "PREVISUALIZADA"),
                Map.of("status", "NA_FILA")
        );
        return get(charge.id(), charge.obraId());
    }

    TemplateData requireTemplate(String id, String obraId) {
        TemplateData template = jdbcTemplate.query(
                """
                SELECT id, obra_id, status, assunto_template,
                       corpo_texto_template, variaveis_permitidas_json
                FROM finance_modelo_email
                WHERE id = ? AND obra_id = ?
                  AND status IN ('PREVISUALIZADO', 'ATIVO')
                LIMIT 1
                """,
                rs -> rs.next() ? new TemplateData(
                        rs.getString("id"),
                        rs.getString("obra_id"),
                        rs.getString("status"),
                        rs.getString("assunto_template"),
                        rs.getString("corpo_texto_template"),
                        stringList(rs.getString("variaveis_permitidas_json"))
                ) : null,
                id,
                obraId
        );
        if (template == null) {
            throw FinanceValidation.badRequest(
                    "Modelo de e-mail ativo e previsualizado não encontrado."
            );
        }
        return template;
    }

    void requireSender(String id) {
        String configKey = jdbcTemplate.query(
                """
                SELECT configuracao_remetente_chave
                FROM finance_email_perfil_remetente
                WHERE id = ? AND status = 'ATIVO'
                LIMIT 1
                """,
                rs -> rs.next()
                        ? rs.getString("configuracao_remetente_chave") : null,
                id
        );
        if (configKey == null) {
            throw FinanceValidation.badRequest(
                    "Perfil remetente ativo não encontrado."
            );
        }
        senderConfiguration.requireConfiguredProfile(configKey);
    }

    String replyToEmail(String id, String obraId) {
        if (id == null) {
            return null;
        }
        return jdbcTemplate.query(
                """
                SELECT email
                FROM finance_email_reply_to_permitido
                WHERE id = ? AND obra_id = ? AND status = 'ATIVO'
                LIMIT 1
                """,
                rs -> rs.next() ? rs.getString("email") : null,
                id,
                obraId
        );
    }

    private void requireReplyTo(String id, String obraId) {
        if (id != null && replyToEmail(id, obraId) == null) {
            throw FinanceValidation.badRequest(
                    "Reply-to não pertence à lista permitida desta obra."
            );
        }
    }

    private ChargeData requireCharge(
            String id,
            String obraId,
            boolean forUpdate
    ) {
        String chargeId = FinanceValidation.uuid(id, "id");
        String worksite = FinanceValidation.uuid(obraId, "obraId");
        String lock = forUpdate ? " FOR UPDATE" : "";
        ChargeData data = jdbcTemplate.query(
                """
                SELECT id, obra_id, modelo_email_id, perfil_remetente_id,
                       reply_to_permitido_id, alvo_tipo, alvo_id, status
                FROM finance_cobranca_email
                WHERE id = ? AND obra_id = ?
                LIMIT 1
                """ + lock,
                rs -> rs.next() ? new ChargeData(
                        rs.getString("id"),
                        rs.getString("obra_id"),
                        rs.getString("modelo_email_id"),
                        rs.getString("perfil_remetente_id"),
                        rs.getString("reply_to_permitido_id"),
                        rs.getString("alvo_tipo"),
                        rs.getString("alvo_id"),
                        rs.getString("status")
                ) : null,
                chargeId,
                worksite
        );
        if (data == null) {
            throw notFound();
        }
        return data;
    }

    private ChargeResponse findByMutation(String actor, String mutationId) {
        return jdbcTemplate.query(
                """
                SELECT *
                FROM finance_cobranca_email
                WHERE criado_por = ? AND client_mutation_id = ?
                LIMIT 1
                """,
                rs -> rs.next() ? mapCharge(rs, 0) : null,
                actor,
                mutationId
        );
    }

    private ChargeResponse mapCharge(ResultSet rs, int rowNum)
            throws SQLException {
        return new ChargeResponse(
                rs.getString("id"),
                rs.getString("obra_id"),
                rs.getString("regra_id"),
                rs.getString("modelo_email_id"),
                rs.getString("perfil_remetente_id"),
                rs.getString("reply_to_permitido_id"),
                rs.getString("fornecedor_id"),
                rs.getString("alvo_tipo"),
                rs.getString("alvo_id"),
                rs.getString("responsavel_id"),
                rs.getString("modo"),
                rs.getString("status"),
                rs.getString("destinatario"),
                rs.getObject("vencimento_em", LocalDate.class),
                rs.getObject("ocorrencia_prevista_em", LocalDateTime.class),
                rs.getObject("agendada_para", LocalDateTime.class),
                rs.getInt("tentativas"),
                rs.getObject("proxima_tentativa_em", LocalDateTime.class),
                rs.getString("provider"),
                rs.getString("provider_message_id"),
                rs.getObject("enviada_em", LocalDateTime.class),
                rs.getString("erro_categoria"),
                rs.getString("erro_sanitizado"),
                rs.getLong("versao_linha")
        );
    }

    private void validateAllowedVariables(Set<String> variables) {
        for (String variable : variables) {
            if (!CANONICAL_VARIABLES.contains(variable)) {
                throw FinanceValidation.badRequest(
                        "O modelo usa variável não permitida: " + variable + "."
                );
            }
        }
    }

    private Set<String> stringList(String json) {
        try {
            List<String> values = objectMapper.readValue(
                    json,
                    new TypeReference<List<String>>() {
                    }
            );
            return Set.copyOf(values);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Variáveis persistidas do modelo são inválidas."
            );
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Falha ao serializar dados da cobrança."
            );
        }
    }

    private String mode(String value, boolean automaticAllowed) {
        String normalized = FinanceValidation.requiredText(
                value, "modo", 20
        ).toUpperCase(Locale.ROOT);
        if (!Set.of("MANUAL", "AGENDADA", "AUTOMATICA")
                .contains(normalized)
                || (!automaticAllowed && "AUTOMATICA".equals(normalized))) {
            throw FinanceValidation.badRequest("Modo de cobrança inválido.");
        }
        return normalized;
    }

    private void history(
            String chargeId,
            String obraId,
            String action,
            String actor,
            String mutationId,
            String correlationId,
            Map<String, ?> before,
            Map<String, ?> after
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO finance_cobranca_email_historico (
                    id, cobranca_id, obra_id, acao, ator_id, origem,
                    client_mutation_id, correlacao_id,
                    estado_anterior_json, estado_novo_json, resultado
                ) VALUES (?, ?, ?, ?, ?, 'ONLINE', ?, ?,
                          CAST(? AS JSON), CAST(? AS JSON), 'SUCESSO')
                """,
                UUID.randomUUID().toString(),
                chargeId,
                obraId,
                action,
                actor,
                mutationId,
                correlationId,
                json(before),
                json(after)
        );
    }

    private void project(
            String id,
            String obraId,
            String event,
            String actor,
            String correlation,
            Map<String, Object> state,
            FinanceChargeTargetReader.TargetData target
    ) {
        ontology.success(
                "COBRANCA_EMAIL",
                id,
                "Cobrança por e-mail",
                event,
                obraId,
                FinanceAuditContext.online(actor, correlation),
                Map.of(),
                state,
                Map.of(
                        "FORNECEDOR", target.supplierId(),
                        target.targetType(), target.targetId()
                )
        );
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Cobrança não encontrada nesta obra."
        );
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    record TemplateData(
            String id,
            String obraId,
            String status,
            String subjectTemplate,
            String bodyTemplate,
            Set<String> allowedVariables
    ) {
    }

    private record ChargeData(
            String id,
            String obraId,
            String modelId,
            String senderId,
            String replyToId,
            String targetType,
            String targetId,
            String status
    ) {
    }
}
