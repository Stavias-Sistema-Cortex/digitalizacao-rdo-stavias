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
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
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
public class FinanceChargeCatalogService {

    private static final Set<String> CANONICAL_VARIABLES = Set.of(
            "obraNome", "fornecedorNome", "fornecedorCnpj",
            "numeroDocumento", "descricao", "moeda", "valorTotal",
            "valorAberto", "vencimento", "tipoAlvo"
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final FinanceChargeTargetReader targetReader;
    private final FinanceChargeTemplateRenderer renderer;
    private final FinanceEmailSenderConfiguration senderConfiguration;
    private final FinanceOntologyProjector ontology;

    public FinanceChargeCatalogService(
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

    public List<SenderProfileResponse> senderProfiles() {
        return jdbcTemplate.query(
                """
                SELECT id, codigo, nome, configuracao_remetente_chave,
                       status, versao_linha
                FROM finance_email_perfil_remetente
                WHERE status <> 'ARQUIVADO'
                ORDER BY nome, id
                """,
                (rs, row) -> new SenderProfileResponse(
                        rs.getString("id"), rs.getString("codigo"),
                        rs.getString("nome"),
                        rs.getString("configuracao_remetente_chave"),
                        rs.getString("status"), rs.getLong("versao_linha")
                )
        );
    }

    @Transactional
    public SenderProfileResponse saveSenderProfile(
            SenderProfileRequest request,
            String actorId
    ) {
        if (request == null) {
            throw FinanceValidation.badRequest(
                    "Perfil remetente é obrigatório."
            );
        }
        String actor = FinanceValidation.uuid(actorId, "atorId");
        String id = optionalId(request.id());
        String code = code(request.codigo(), "codigo");
        String name = FinanceValidation.requiredText(
                request.nome(), "nome", 255
        );
        String configKey = FinanceValidation.requiredText(
                request.configuracaoRemetenteChave(),
                "configuracaoRemetenteChave",
                120
        );
        senderConfiguration.requireConfiguredProfile(configKey);
        String status = request.status() == null || request.status().isBlank()
                ? "ATIVO"
                : enumValue(request.status(), "status", Set.of("ATIVO", "INATIVO"));
        try {
            if (id == null) {
                id = UUID.randomUUID().toString();
                jdbcTemplate.update(
                        """
                        INSERT INTO finance_email_perfil_remetente (
                            id, codigo, nome, configuracao_remetente_chave,
                            status, criado_por, atualizado_por
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                        id, code, name, configKey, status, actor, actor
                );
            } else {
                requireUpdated(jdbcTemplate.update(
                        """
                        UPDATE finance_email_perfil_remetente
                        SET codigo = ?, nome = ?,
                            configuracao_remetente_chave = ?, status = ?,
                            atualizado_por = ?, versao_linha = versao_linha + 1
                        WHERE id = ? AND status <> 'ARQUIVADO'
                        """,
                        code, name, configKey, status, actor, id
                ), "Perfil remetente não encontrado.");
            }
        } catch (DataIntegrityViolationException exception) {
            throw conflict("Código ou configuração de remetente já existe.");
        }
        String resultId = id;
        return senderProfiles().stream()
                .filter(item -> item.id().equals(resultId))
                .findFirst()
                .orElseThrow(() -> notFound("Perfil remetente não encontrado."));
    }

    public List<ReplyToResponse> replyToAddresses(String obraId) {
        String worksite = FinanceValidation.uuid(obraId, "obraId");
        return jdbcTemplate.query(
                """
                SELECT id, obra_id, colaborador_id, nome, email,
                       status, versao_linha
                FROM finance_email_reply_to_permitido
                WHERE obra_id = ? AND status <> 'ARQUIVADO'
                ORDER BY nome, email, id
                """,
                (rs, row) -> new ReplyToResponse(
                        rs.getString("id"), rs.getString("obra_id"),
                        rs.getString("colaborador_id"), rs.getString("nome"),
                        rs.getString("email"), rs.getString("status"),
                        rs.getLong("versao_linha")
                ),
                worksite
        );
    }

    @Transactional
    public ReplyToResponse saveReplyTo(ReplyToRequest request, String actorId) {
        if (request == null) {
            throw FinanceValidation.badRequest("Reply-to é obrigatório.");
        }
        String actor = FinanceValidation.uuid(actorId, "atorId");
        String id = optionalId(request.id());
        String obraId = FinanceValidation.uuid(request.obraId(), "obraId");
        String collaborator = FinanceValidation.optionalUuid(
                request.colaboradorId(), "colaboradorId"
        );
        String name = FinanceValidation.requiredText(request.nome(), "nome", 255);
        String email = FinanceValidation.email(request.email());
        if (email == null) {
            throw FinanceValidation.badRequest("E-mail do reply-to é obrigatório.");
        }
        String status = request.status() == null || request.status().isBlank()
                ? "ATIVO"
                : enumValue(request.status(), "status", Set.of("ATIVO", "INATIVO"));
        try {
            if (id == null) {
                id = UUID.randomUUID().toString();
                jdbcTemplate.update(
                        """
                        INSERT INTO finance_email_reply_to_permitido (
                            id, obra_id, colaborador_id, nome, email, status,
                            criado_por, atualizado_por
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        id, obraId, collaborator, name, email, status, actor, actor
                );
            } else {
                requireUpdated(jdbcTemplate.update(
                        """
                        UPDATE finance_email_reply_to_permitido
                        SET colaborador_id = ?, nome = ?, email = ?, status = ?,
                            atualizado_por = ?, versao_linha = versao_linha + 1
                        WHERE id = ? AND obra_id = ? AND status <> 'ARQUIVADO'
                        """,
                        collaborator, name, email, status, actor, id, obraId
                ), "Reply-to não encontrado nesta obra.");
            }
        } catch (DataIntegrityViolationException exception) {
            throw conflict("Este reply-to já está cadastrado nesta obra.");
        }
        String resultId = id;
        return replyToAddresses(obraId).stream()
                .filter(item -> item.id().equals(resultId))
                .findFirst()
                .orElseThrow(() -> notFound("Reply-to não encontrado."));
    }

    public List<TemplateResponse> templates(String obraId) {
        String worksite = FinanceValidation.uuid(obraId, "obraId");
        return jdbcTemplate.query(
                """
                SELECT *
                FROM finance_modelo_email
                WHERE obra_id = ? AND status <> 'ARQUIVADO'
                ORDER BY codigo, versao DESC, id
                """,
                this::mapTemplate,
                worksite
        );
    }

    @Transactional
    public TemplateResponse saveTemplate(TemplateRequest request, String actorId) {
        if (request == null) {
            throw FinanceValidation.badRequest("Modelo de e-mail é obrigatório.");
        }
        String actor = FinanceValidation.uuid(actorId, "atorId");
        String id = optionalId(request.id());
        String obraId = FinanceValidation.uuid(request.obraId(), "obraId");
        String code = code(request.codigo(), "codigo");
        String name = FinanceValidation.requiredText(request.nome(), "nome", 255);
        int version = request.versao() == null ? 1 : request.versao();
        if (version < 1) {
            throw FinanceValidation.badRequest("versao deve ser positiva.");
        }
        String subject = FinanceValidation.requiredText(
                request.assuntoTemplate(), "assuntoTemplate", 500
        );
        String body = FinanceValidation.requiredText(
                request.corpoTextoTemplate(), "corpoTextoTemplate", 100_000
        );
        List<String> variables = variables(request.variaveisPermitidas());
        try {
            if (id == null) {
                id = UUID.randomUUID().toString();
                jdbcTemplate.update(
                        """
                        INSERT INTO finance_modelo_email (
                            id, obra_id, codigo, nome, versao, status,
                            assunto_template, corpo_texto_template,
                            variaveis_permitidas_json, criado_por, atualizado_por
                        ) VALUES (?, ?, ?, ?, ?, 'RASCUNHO', ?, ?,
                                  CAST(? AS JSON), ?, ?)
                        """,
                        id, obraId, code, name, version, subject, body,
                        json(variables), actor, actor
                );
            } else {
                requireUpdated(jdbcTemplate.update(
                        """
                        UPDATE finance_modelo_email
                        SET codigo = ?, nome = ?, versao = ?, status = 'RASCUNHO',
                            assunto_template = ?, corpo_texto_template = ?,
                            variaveis_permitidas_json = CAST(? AS JSON),
                            previsualizacao_hash = NULL,
                            previsualizado_por = NULL, previsualizado_em = NULL,
                            atualizado_por = ?, versao_linha = versao_linha + 1
                        WHERE id = ? AND obra_id = ?
                          AND status NOT IN ('ATIVO', 'ARQUIVADO')
                        """,
                        code, name, version, subject, body, json(variables),
                        actor, id, obraId
                ), "Modelo ativo não pode ser alterado; crie uma nova versão.");
            }
        } catch (DataIntegrityViolationException exception) {
            throw conflict("Esta versão do modelo já existe na obra.");
        }
        return findTemplate(id, obraId);
    }

    @Transactional
    public PreviewResponse previewTemplate(
            String id,
            String obraId,
            TargetReference targetReference,
            String actorId
    ) {
        if (targetReference == null) {
            throw FinanceValidation.badRequest("Alvo real é obrigatório.");
        }
        String actor = FinanceValidation.uuid(actorId, "atorId");
        TemplateResponse template = findTemplate(id, obraId);
        if (Set.of("ATIVO", "ARQUIVADO").contains(template.status())) {
            throw conflict("Modelo ativo ou arquivado não pode ser previsualizado.");
        }
        FinanceChargeTargetReader.TargetData target = targetReader.read(
                obraId, targetReference.alvoTipo(), targetReference.alvoId()
        );
        FinanceChargeTemplateRenderer.RenderedEmail rendered = renderer.render(
                template.assuntoTemplate(), template.corpoTextoTemplate(),
                Set.copyOf(template.variaveisPermitidas()), target.renderValues()
        );
        LocalDateTime now = utcNow();
        jdbcTemplate.update(
                """
                UPDATE finance_modelo_email
                SET status = 'PREVISUALIZADO', previsualizacao_hash = ?,
                    previsualizado_por = ?, previsualizado_em = ?,
                    atualizado_por = ?, versao_linha = versao_linha + 1
                WHERE id = ? AND obra_id = ?
                """,
                rendered.previewHash(), actor, now, actor,
                template.id(), template.obraId()
        );
        project(
                "MODELO_EMAIL", template.id(), template.obraId(),
                "MODELO_EMAIL_PREVISUALIZADO", actor,
                Map.of("previsualizacaoHash", rendered.previewHash()), Map.of()
        );
        return new PreviewResponse(
                template.id(), target.recipient(), null,
                rendered.subject(), rendered.textBody(),
                rendered.previewHash(), now
        );
    }

    @Transactional
    public TemplateResponse activateTemplate(
            String id,
            String obraId,
            String actorId
    ) {
        String actor = FinanceValidation.uuid(actorId, "atorId");
        requireUpdated(jdbcTemplate.update(
                """
                UPDATE finance_modelo_email
                SET status = 'ATIVO', atualizado_por = ?,
                    versao_linha = versao_linha + 1
                WHERE id = ? AND obra_id = ?
                  AND status = 'PREVISUALIZADO'
                  AND previsualizacao_hash IS NOT NULL
                  AND previsualizado_por IS NOT NULL
                  AND previsualizado_em IS NOT NULL
                """,
                actor,
                FinanceValidation.uuid(id, "id"),
                FinanceValidation.uuid(obraId, "obraId")
        ), "Confirme uma previsualização real antes de ativar o modelo.");
        return findTemplate(id, obraId);
    }

    public List<RuleResponse> rules(String obraId) {
        String worksite = FinanceValidation.uuid(obraId, "obraId");
        return jdbcTemplate.query(
                """
                SELECT *
                FROM finance_regra_cobranca
                WHERE obra_id = ? AND status <> 'ARQUIVADA'
                ORDER BY nome, id
                """,
                this::mapRule,
                worksite
        );
    }

    @Transactional
    public RuleResponse saveRule(RuleRequest request, String actorId) {
        if (request == null) {
            throw FinanceValidation.badRequest("Regra de cobrança é obrigatória.");
        }
        String actor = FinanceValidation.uuid(actorId, "atorId");
        String id = optionalId(request.id());
        String mutation = FinanceValidation.mutationId(request.clientMutationId());
        RuleResponse existing = findRuleByMutation(actor, mutation);
        if (existing != null) {
            return existing;
        }
        String obraId = FinanceValidation.uuid(request.obraId(), "obraId");
        String modelId = FinanceValidation.uuid(request.modeloEmailId(), "modeloEmailId");
        requireActiveTemplate(modelId, obraId);
        String senderId = FinanceValidation.uuid(request.perfilRemetenteId(), "perfilRemetenteId");
        requireSender(senderId);
        String replyId = FinanceValidation.optionalUuid(request.replyToPermitidoId(), "replyToPermitidoId");
        requireReply(replyId, obraId);
        String mode = enumValue(request.modo(), "modo", Set.of("MANUAL", "AGENDADA", "AUTOMATICA"));
        String reference = request.referencia() == null || request.referencia().isBlank()
                ? "VENCIMENTO"
                : enumValue(request.referencia(), "referencia", Set.of("VENCIMENTO", "EMISSAO", "CRIACAO"));
        int offset = request.deslocamentoDias() == null ? 0 : request.deslocamentoDias();
        if (offset < -3650 || offset > 3650) {
            throw FinanceValidation.badRequest("deslocamentoDias inválido.");
        }
        String timezone = FinanceValidation.requiredText(request.fusoHorario(), "fusoHorario", 80);
        try {
            ZoneId.of(timezone);
        } catch (RuntimeException exception) {
            throw FinanceValidation.badRequest("fusoHorario inválido.");
        }
        if ("AUTOMATICA".equals(mode) && request.horarioLocal() == null) {
            throw FinanceValidation.badRequest("horarioLocal é obrigatório para regra automática.");
        }
        if (request.vigenteDe() != null && request.vigenteAte() != null
                && request.vigenteAte().isBefore(request.vigenteDe())) {
            throw FinanceValidation.badRequest("Vigência da regra inválida.");
        }
        try {
            if (id == null) {
                id = UUID.randomUUID().toString();
                jdbcTemplate.update(
                        """
                        INSERT INTO finance_regra_cobranca (
                            id, client_mutation_id, obra_id, modelo_email_id,
                            perfil_remetente_id, reply_to_permitido_id, nome,
                            modo, referencia, deslocamento_dias, horario_local,
                            fuso_horario, vigente_de, vigente_ate, status,
                            criado_por, atualizado_por
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                                  'RASCUNHO', ?, ?)
                        """,
                        id, mutation, obraId, modelId, senderId, replyId,
                        FinanceValidation.requiredText(request.nome(), "nome", 255),
                        mode, reference, offset, request.horarioLocal(), timezone,
                        request.vigenteDe(), request.vigenteAte(), actor, actor
                );
            } else {
                requireUpdated(jdbcTemplate.update(
                        """
                        UPDATE finance_regra_cobranca
                        SET modelo_email_id = ?, perfil_remetente_id = ?,
                            reply_to_permitido_id = ?, nome = ?, modo = ?,
                            referencia = ?, deslocamento_dias = ?,
                            horario_local = ?, fuso_horario = ?, vigente_de = ?,
                            vigente_ate = ?, status = 'RASCUNHO',
                            previsualizacao_hash = NULL,
                            previsualizacao_confirmada_por = NULL,
                            previsualizacao_confirmada_em = NULL,
                            atualizado_por = ?, versao_linha = versao_linha + 1
                        WHERE id = ? AND obra_id = ?
                          AND status NOT IN ('ATIVA', 'ARQUIVADA')
                        """,
                        modelId, senderId, replyId,
                        FinanceValidation.requiredText(request.nome(), "nome", 255),
                        mode, reference, offset, request.horarioLocal(), timezone,
                        request.vigenteDe(), request.vigenteAte(), actor, id, obraId
                ), "Regra ativa não pode ser alterada; desative-a primeiro.");
            }
        } catch (DataIntegrityViolationException exception) {
            throw conflict("Nome ou mutação da regra já existe.");
        }
        return findRule(id, obraId);
    }

    @Transactional
    public PreviewResponse previewRule(
            String id,
            String obraId,
            TargetReference targetReference,
            String actorId
    ) {
        if (targetReference == null) {
            throw FinanceValidation.badRequest("Alvo real é obrigatório.");
        }
        String actor = FinanceValidation.uuid(actorId, "atorId");
        RuleResponse rule = findRule(id, obraId);
        if (Set.of("ATIVA", "ARQUIVADA").contains(rule.status())) {
            throw conflict("Regra ativa ou arquivada não pode ser previsualizada.");
        }
        TemplateResponse template = requireActiveTemplate(rule.modeloEmailId(), obraId);
        requireSender(rule.perfilRemetenteId());
        requireReply(rule.replyToPermitidoId(), obraId);
        FinanceChargeTargetReader.TargetData target = targetReader.read(
                obraId, targetReference.alvoTipo(), targetReference.alvoId()
        );
        FinanceChargeTemplateRenderer.RenderedEmail rendered = renderer.render(
                template.assuntoTemplate(), template.corpoTextoTemplate(),
                Set.copyOf(template.variaveisPermitidas()), target.renderValues()
        );
        LocalDateTime now = utcNow();
        jdbcTemplate.update(
                """
                UPDATE finance_regra_cobranca
                SET status = 'PREVISUALIZADA', previsualizacao_hash = ?,
                    previsualizacao_confirmada_por = ?,
                    previsualizacao_confirmada_em = ?, atualizado_por = ?,
                    versao_linha = versao_linha + 1
                WHERE id = ? AND obra_id = ?
                """,
                rendered.previewHash(), actor, now, actor, rule.id(), obraId
        );
        project(
                "REGRA_COBRANCA", rule.id(), obraId,
                "REGRA_COBRANCA_PREVISUALIZADA", actor,
                Map.of("previsualizacaoHash", rendered.previewHash()), Map.of()
        );
        return new PreviewResponse(
                rule.id(), target.recipient(), replyEmail(rule.replyToPermitidoId(), obraId),
                rendered.subject(), rendered.textBody(), rendered.previewHash(), now
        );
    }

    @Transactional
    public RuleResponse activateRule(String id, String obraId, String actorId) {
        String actor = FinanceValidation.uuid(actorId, "atorId");
        requireUpdated(jdbcTemplate.update(
                """
                UPDATE finance_regra_cobranca r
                JOIN finance_modelo_email m
                  ON m.id = r.modelo_email_id AND m.obra_id = r.obra_id
                SET r.status = 'ATIVA', r.atualizado_por = ?,
                    r.versao_linha = r.versao_linha + 1
                WHERE r.id = ? AND r.obra_id = ?
                  AND r.status = 'PREVISUALIZADA'
                  AND r.previsualizacao_hash IS NOT NULL
                  AND r.previsualizacao_confirmada_por IS NOT NULL
                  AND r.previsualizacao_confirmada_em IS NOT NULL
                  AND m.status = 'ATIVO'
                """,
                actor,
                FinanceValidation.uuid(id, "id"),
                FinanceValidation.uuid(obraId, "obraId")
        ), "Confirme uma previsualização real antes de ativar a regra.");
        return findRule(id, obraId);
    }

    private TemplateResponse findTemplate(String id, String obraId) {
        String templateId = FinanceValidation.uuid(id, "id");
        String worksite = FinanceValidation.uuid(obraId, "obraId");
        TemplateResponse response = jdbcTemplate.query(
                "SELECT * FROM finance_modelo_email WHERE id = ? AND obra_id = ? LIMIT 1",
                rs -> rs.next() ? mapTemplate(rs, 0) : null,
                templateId, worksite
        );
        if (response == null) {
            throw notFound("Modelo não encontrado nesta obra.");
        }
        return response;
    }

    private TemplateResponse requireActiveTemplate(String id, String obraId) {
        TemplateResponse response = findTemplate(id, obraId);
        if (!"ATIVO".equals(response.status())) {
            throw FinanceValidation.badRequest("O modelo precisa estar ativo.");
        }
        return response;
    }

    private TemplateResponse mapTemplate(ResultSet rs, int row) throws SQLException {
        return new TemplateResponse(
                rs.getString("id"), rs.getString("obra_id"),
                rs.getString("codigo"), rs.getString("nome"),
                rs.getInt("versao"), rs.getString("status"),
                rs.getString("assunto_template"),
                rs.getString("corpo_texto_template"),
                stringList(rs.getString("variaveis_permitidas_json")),
                rs.getString("previsualizacao_hash"),
                rs.getLong("versao_linha")
        );
    }

    private RuleResponse findRule(String id, String obraId) {
        String ruleId = FinanceValidation.uuid(id, "id");
        String worksite = FinanceValidation.uuid(obraId, "obraId");
        RuleResponse response = jdbcTemplate.query(
                "SELECT * FROM finance_regra_cobranca WHERE id = ? AND obra_id = ? LIMIT 1",
                rs -> rs.next() ? mapRule(rs, 0) : null,
                ruleId, worksite
        );
        if (response == null) {
            throw notFound("Regra não encontrada nesta obra.");
        }
        return response;
    }

    private RuleResponse findRuleByMutation(String actor, String mutation) {
        return jdbcTemplate.query(
                "SELECT * FROM finance_regra_cobranca WHERE criado_por = ? AND client_mutation_id = ? LIMIT 1",
                rs -> rs.next() ? mapRule(rs, 0) : null,
                actor, mutation
        );
    }

    private RuleResponse mapRule(ResultSet rs, int row) throws SQLException {
        return new RuleResponse(
                rs.getString("id"), rs.getString("obra_id"),
                rs.getString("modelo_email_id"),
                rs.getString("perfil_remetente_id"),
                rs.getString("reply_to_permitido_id"), rs.getString("nome"),
                rs.getString("modo"), rs.getString("referencia"),
                rs.getInt("deslocamento_dias"),
                rs.getObject("horario_local", LocalTime.class),
                rs.getString("fuso_horario"),
                rs.getObject("vigente_de", LocalDate.class),
                rs.getObject("vigente_ate", LocalDate.class),
                rs.getString("status"), rs.getString("previsualizacao_hash"),
                rs.getLong("versao_linha")
        );
    }

    private void requireSender(String id) {
        String key = jdbcTemplate.query(
                "SELECT configuracao_remetente_chave FROM finance_email_perfil_remetente WHERE id = ? AND status = 'ATIVO' LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : null,
                id
        );
        if (key == null) {
            throw FinanceValidation.badRequest("Perfil remetente ativo não encontrado.");
        }
        senderConfiguration.requireConfiguredProfile(key);
    }

    private void requireReply(String id, String obraId) {
        if (id != null && replyEmail(id, obraId) == null) {
            throw FinanceValidation.badRequest("Reply-to não permitido nesta obra.");
        }
    }

    private String replyEmail(String id, String obraId) {
        if (id == null) {
            return null;
        }
        return jdbcTemplate.query(
                "SELECT email FROM finance_email_reply_to_permitido WHERE id = ? AND obra_id = ? AND status = 'ATIVO' LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : null,
                id, obraId
        );
    }

    private List<String> variables(List<String> values) {
        if (values == null) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            String variable = FinanceValidation.requiredText(value, "variavel", 64);
            if (!CANONICAL_VARIABLES.contains(variable)) {
                throw FinanceValidation.badRequest("Variável não permitida: " + variable + ".");
            }
            unique.add(variable);
        }
        return List.copyOf(unique);
    }

    private List<String> stringList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Variáveis persistidas são inválidas.");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Falha ao serializar configuração.");
        }
    }

    private String optionalId(String id) {
        return id == null || id.isBlank() ? null : FinanceValidation.uuid(id, "id");
    }

    private String code(String value, String field) {
        String normalized = FinanceValidation.requiredText(value, field, 80)
                .toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z][A-Z0-9_-]{1,79}")) {
            throw FinanceValidation.badRequest(field + " inválido.");
        }
        return normalized;
    }

    private String enumValue(String value, String field, Set<String> allowed) {
        String normalized = FinanceValidation.requiredText(value, field, 30)
                .toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw FinanceValidation.badRequest(field + " inválido.");
        }
        return normalized;
    }

    private void requireUpdated(int count, String message) {
        if (count != 1) {
            throw conflict(message);
        }
    }

    private void project(
            String type,
            String id,
            String obraId,
            String event,
            String actor,
            Map<String, Object> state,
            Map<String, String> related
    ) {
        ontology.success(
                type, id, type, event, obraId,
                FinanceAuditContext.online(actor, null),
                Map.of(), state, related
        );
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
