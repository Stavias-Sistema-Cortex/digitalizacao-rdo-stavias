package com.projeto.cortex.financeiro.cobranca;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.financeiro.core.FinanceAuditContext;
import com.projeto.cortex.financeiro.core.FinanceOntologyProjector;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("legacy-finance")
public class FinanceAutomaticChargeService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final FinanceChargeTargetReader targetReader;
    private final FinanceChargeTemplateRenderer renderer;
    private final FinanceChargeOccurrenceCalculator occurrenceCalculator;
    private final FinanceEmailSenderConfiguration senderConfiguration;
    private final FinanceOntologyProjector ontology;

    public FinanceAutomaticChargeService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            FinanceChargeTargetReader targetReader,
            FinanceChargeOccurrenceCalculator occurrenceCalculator,
            FinanceEmailSenderConfiguration senderConfiguration,
            FinanceOntologyProjector ontology
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.targetReader = targetReader;
        this.renderer = new FinanceChargeTemplateRenderer();
        this.occurrenceCalculator = occurrenceCalculator;
        this.senderConfiguration = senderConfiguration;
        this.ontology = ontology;
    }

    @Transactional
    public int generate(int batchSize) {
        int limit = Math.max(1, Math.min(batchSize, 200));
        List<Candidate> candidates = jdbcTemplate.query(
                """
                SELECT r.id AS regra_id, r.obra_id, r.modelo_email_id,
                       r.perfil_remetente_id, r.reply_to_permitido_id,
                       r.referencia, r.deslocamento_dias, r.horario_local,
                       r.fuso_horario, r.previsualizacao_confirmada_por,
                       r.previsualizacao_confirmada_em,
                       m.assunto_template, m.corpo_texto_template,
                       m.variaveis_permitidas_json,
                       p.configuracao_remetente_chave,
                       l.id AS lancamento_id,
                       CASE r.referencia
                         WHEN 'VENCIMENTO' THEN l.vencimento_em
                         WHEN 'EMISSAO' THEN COALESCE(
                             l.data_emissao, l.data_competencia)
                         ELSE DATE(l.criado_em)
                       END AS referencia_em
                FROM finance_regra_cobranca r
                JOIN finance_modelo_email m
                  ON m.id = r.modelo_email_id
                 AND m.obra_id = r.obra_id
                 AND m.status = 'ATIVO'
                JOIN finance_email_perfil_remetente p
                  ON p.id = r.perfil_remetente_id
                 AND p.status = 'ATIVO'
                JOIN finance_lancamento l
                  ON l.obra_id = r.obra_id
                 AND l.fornecedor_id IS NOT NULL
                 AND l.valor_liquidado < l.valor_liquido
                 AND l.arquivado_em IS NULL
                WHERE r.status = 'ATIVA'
                  AND r.modo = 'AUTOMATICA'
                  AND r.previsualizacao_hash IS NOT NULL
                  AND r.previsualizacao_confirmada_por IS NOT NULL
                  AND r.previsualizacao_confirmada_em IS NOT NULL
                  AND (r.vigente_de IS NULL OR r.vigente_de <= UTC_DATE())
                  AND (r.vigente_ate IS NULL OR r.vigente_ate >= UTC_DATE())
                ORDER BY l.vencimento_em, r.id, l.id
                LIMIT ?
                """,
                this::mapCandidate,
                limit
        );
        int created = 0;
        for (Candidate candidate : candidates) {
            if (create(candidate)) {
                created++;
            }
        }
        return created;
    }

    private boolean create(Candidate candidate) {
        senderConfiguration.requireConfiguredProfile(
                candidate.senderConfigurationKey()
        );
        FinanceChargeTargetReader.TargetData target = targetReader.read(
                candidate.obraId(), "LANCAMENTO", candidate.ledgerId()
        );
        FinanceChargeTemplateRenderer.RenderedEmail rendered = renderer.render(
                candidate.subjectTemplate(),
                candidate.bodyTemplate(),
                candidate.allowedVariables(),
                target.renderValues()
        );
        LocalDateTime occurrence = occurrenceCalculator.utcOccurrence(
                candidate.referenceDate(),
                candidate.offsetDays(),
                candidate.localTime(),
                candidate.zoneId()
        );
        String id = UUID.randomUUID().toString();
        String naturalKey = candidate.ruleId() + ":LANCAMENTO:"
                + candidate.ledgerId() + ":" + occurrence;
        String idempotency = "finance-auto:" + sha256(naturalKey);
        String mutation = "auto:" + sha256(naturalKey).substring(0, 64);
        String status = occurrence.isAfter(utcNow()) ? "AGENDADA" : "NA_FILA";
        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO finance_cobranca_email (
                        id, client_mutation_id, idempotency_key, obra_id,
                        regra_id, modelo_email_id, perfil_remetente_id,
                        reply_to_permitido_id, fornecedor_id, lancamento_id,
                        responsavel_id, modo, status, destinatario,
                        vencimento_em, ocorrencia_prevista_em, agendada_para,
                        dados_renderizacao_json, previsualizacao_hash,
                        previsualizacao_confirmada_por,
                        previsualizacao_confirmada_em,
                        proxima_tentativa_em, criado_por, atualizado_por
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'AUTOMATICA',
                              ?, ?, ?, ?, ?, CAST(? AS JSON), ?, ?, ?, ?, ?, ?)
                    """,
                    id,
                    mutation,
                    idempotency,
                    candidate.obraId(),
                    candidate.ruleId(),
                    candidate.modelId(),
                    candidate.senderId(),
                    candidate.replyToId(),
                    target.supplierId(),
                    target.targetId(),
                    target.responsibleId(),
                    status,
                    target.recipient(),
                    target.dueDate(),
                    occurrence,
                    occurrence,
                    json(target.renderValues()),
                    rendered.previewHash(),
                    candidate.previewedBy(),
                    candidate.previewedAt(),
                    occurrence,
                    target.responsibleId(),
                    target.responsibleId()
            );
        } catch (DuplicateKeyException exception) {
            return false;
        }
        jdbcTemplate.update(
                """
                INSERT INTO finance_cobranca_email_historico (
                    id, cobranca_id, obra_id, acao, ator_id, origem,
                    client_mutation_id, estado_anterior_json,
                    estado_novo_json, resultado
                ) VALUES (?, ?, ?, 'GERADA_AUTOMATICAMENTE', ?, 'SISTEMA', ?,
                          CAST('{}' AS JSON), CAST(? AS JSON), 'SUCESSO')
                """,
                UUID.randomUUID().toString(),
                id,
                candidate.obraId(),
                target.responsibleId(),
                mutation,
                json(Map.of(
                        "status", status,
                        "regraId", candidate.ruleId(),
                        "ocorrenciaPrevistaEm", occurrence
                ))
        );
        ontology.success(
                "COBRANCA_EMAIL", id, "Cobrança automática",
                "COBRANCA_EMAIL_GERADA_AUTOMATICAMENTE",
                candidate.obraId(),
                new FinanceAuditContext(
                        target.responsibleId(), null, mutation, "SISTEMA"
                ),
                Map.of(),
                Map.of(
                        "status", status,
                        "ocorrenciaPrevistaEm", occurrence.toString()
                ),
                Map.of(
                        "REGRA_COBRANCA", candidate.ruleId(),
                        "LANCAMENTO", candidate.ledgerId(),
                        "FORNECEDOR", target.supplierId()
                )
        );
        return true;
    }

    private Candidate mapCandidate(ResultSet rs, int row) throws SQLException {
        return new Candidate(
                rs.getString("regra_id"),
                rs.getString("obra_id"),
                rs.getString("modelo_email_id"),
                rs.getString("perfil_remetente_id"),
                rs.getString("reply_to_permitido_id"),
                rs.getInt("deslocamento_dias"),
                rs.getObject("horario_local", LocalTime.class),
                ZoneId.of(rs.getString("fuso_horario")),
                rs.getString("previsualizacao_confirmada_por"),
                rs.getObject(
                        "previsualizacao_confirmada_em", LocalDateTime.class
                ),
                rs.getString("assunto_template"),
                rs.getString("corpo_texto_template"),
                stringSet(rs.getString("variaveis_permitidas_json")),
                rs.getString("configuracao_remetente_chave"),
                rs.getString("lancamento_id"),
                rs.getObject("referencia_em", LocalDate.class)
        );
    }

    private Set<String> stringSet(String json) {
        try {
            return Set.copyOf(objectMapper.readValue(
                    json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { }
            ));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Variáveis persistidas do modelo são inválidas."
            );
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Falha ao registrar cobrança automática.");
        }
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponível.");
        }
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private record Candidate(
            String ruleId,
            String obraId,
            String modelId,
            String senderId,
            String replyToId,
            int offsetDays,
            LocalTime localTime,
            ZoneId zoneId,
            String previewedBy,
            LocalDateTime previewedAt,
            String subjectTemplate,
            String bodyTemplate,
            Set<String> allowedVariables,
            String senderConfigurationKey,
            String ledgerId,
            LocalDate referenceDate
    ) {
    }
}
