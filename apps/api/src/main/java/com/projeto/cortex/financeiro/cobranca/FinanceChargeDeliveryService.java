package com.projeto.cortex.financeiro.cobranca;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.email.EmailGateway;
import com.projeto.cortex.email.EmailMessage;
import com.projeto.cortex.financeiro.core.FinanceAuditContext;
import com.projeto.cortex.financeiro.core.FinanceOntologyProjector;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class FinanceChargeDeliveryService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final EmailGateway emailGateway;
    private final FinanceChargeTargetReader targetReader;
    private final FinanceChargeTemplateRenderer renderer;
    private final FinanceEmailSenderConfiguration senderConfiguration;
    private final FinanceChargeRetryPolicy retryPolicy;
    private final FinanceOntologyProjector ontology;
    private final TransactionTemplate transactions;
    private final int leaseSeconds;

    public FinanceChargeDeliveryService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            EmailGateway emailGateway,
            FinanceChargeTargetReader targetReader,
            FinanceEmailSenderConfiguration senderConfiguration,
            FinanceChargeRetryPolicy retryPolicy,
            FinanceOntologyProjector ontology,
            PlatformTransactionManager transactionManager,
            @Value("${cortex.finance.email.dispatch.lease-seconds:120}")
            int leaseSeconds
    ) {
        if (leaseSeconds < 10 || leaseSeconds > 3600) {
            throw new IllegalArgumentException(
                    "Lease de envio financeiro inválido."
            );
        }
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.emailGateway = emailGateway;
        this.targetReader = targetReader;
        this.renderer = new FinanceChargeTemplateRenderer();
        this.senderConfiguration = senderConfiguration;
        this.retryPolicy = retryPolicy;
        this.ontology = ontology;
        this.transactions = new TransactionTemplate(transactionManager);
        this.leaseSeconds = leaseSeconds;
    }

    public void dispatchOne(String chargeId) {
        Claim claim = transactions.execute(status -> claim(chargeId));
        if (claim == null) {
            return;
        }

        Instant started = Instant.now();
        try {
            senderConfiguration.requireConfiguredProfile(
                    claim.senderConfigurationKey()
            );
            FinanceChargeTargetReader.TargetData target = targetReader.read(
                    claim.obraId(), claim.targetType(), claim.targetId()
            );
            FinanceChargeTemplateRenderer.RenderedEmail rendered =
                    renderer.render(
                            claim.subjectTemplate(),
                            claim.bodyTemplate(),
                            claim.allowedVariables(),
                            target.renderValues()
                    );
            if (!rendered.previewHash().equals(claim.previewHash())) {
                finishFailure(
                        claim,
                        started,
                        "PREVIEW_DESATUALIZADA",
                        "Os dados ou o modelo mudaram; confirme uma nova previsualização.",
                        false
                );
                return;
            }
            EmailGateway.DeliveryReceipt receipt = emailGateway.send(
                    new EmailMessage(
                            target.recipient(),
                            rendered.subject(),
                            rendered.textBody(),
                            claim.attemptIdempotencyKey(),
                            claim.replyTo()
                    )
            );
            finishSuccess(claim, started, receipt);
        } catch (EmailGateway.DeliveryException exception) {
            finishFailure(
                    claim,
                    started,
                    exception.category(),
                    exception.getMessage(),
                    exception.knownSafeToRetry()
            );
        } catch (RuntimeException exception) {
            finishFailure(
                    claim,
                    started,
                    "FALHA_INTERNA_AMBIGUA",
                    "Não foi possível confirmar o envio pelo provider.",
                    false
            );
        }
    }

    public int dispatchDue(int batchSize) {
        int limit = Math.max(1, Math.min(batchSize, 100));
        List<String> ids = jdbcTemplate.queryForList(
                """
                SELECT id
                FROM finance_cobranca_email
                WHERE (
                    status = 'NA_FILA'
                    OR (status = 'AGENDADA' AND agendada_para <= CURRENT_TIMESTAMP(6))
                    OR (status = 'FALHOU'
                        AND proxima_tentativa_em IS NOT NULL
                        AND proxima_tentativa_em <= CURRENT_TIMESTAMP(6))
                )
                ORDER BY COALESCE(
                    proxima_tentativa_em,
                    agendada_para,
                    ocorrencia_prevista_em
                ), id
                LIMIT ?
                """,
                String.class,
                limit
        );
        ids.forEach(this::dispatchOne);
        return ids.size();
    }

    private Claim claim(String rawId) {
        String id;
        try {
            id = UUID.fromString(rawId).toString();
        } catch (RuntimeException exception) {
            return null;
        }
        Claim candidate = jdbcTemplate.query(
                """
                SELECT c.id, c.obra_id, c.alvo_tipo, c.alvo_id,
                       c.responsavel_id, c.tentativas, c.idempotency_key,
                       c.previsualizacao_hash,
                       m.assunto_template, m.corpo_texto_template,
                       m.variaveis_permitidas_json,
                       p.configuracao_remetente_chave,
                       r.email AS reply_to
                FROM finance_cobranca_email c
                JOIN finance_modelo_email m
                  ON m.id = c.modelo_email_id AND m.obra_id = c.obra_id
                JOIN finance_email_perfil_remetente p
                  ON p.id = c.perfil_remetente_id
                LEFT JOIN finance_email_reply_to_permitido r
                  ON r.id = c.reply_to_permitido_id
                 AND r.obra_id = c.obra_id
                 AND r.status = 'ATIVO'
                WHERE c.id = ?
                  AND (
                    c.status = 'NA_FILA'
                    OR (c.status = 'AGENDADA'
                        AND c.agendada_para <= CURRENT_TIMESTAMP(6))
                    OR (c.status = 'FALHOU'
                        AND c.proxima_tentativa_em IS NOT NULL
                        AND c.proxima_tentativa_em <= CURRENT_TIMESTAMP(6))
                  )
                LIMIT 1
                FOR UPDATE
                """,
                rs -> rs.next() ? mapClaim(rs) : null,
                id
        );
        if (candidate == null) {
            return null;
        }
        String lockToken = UUID.randomUUID().toString();
        int updated = jdbcTemplate.update(
                """
                UPDATE finance_cobranca_email
                SET status = 'ENVIANDO', lock_token = ?,
                    lock_ate = CURRENT_TIMESTAMP(6) + (? * INTERVAL '1 second'),
                    erro_categoria = NULL, erro_sanitizado = NULL,
                    atualizado_por = responsavel_id,
                    versao_linha = versao_linha + 1
                WHERE id = ?
                  AND status IN ('NA_FILA', 'AGENDADA', 'FALHOU')
                """,
                lockToken,
                leaseSeconds,
                candidate.id()
        );
        return updated == 1 ? candidate.withLock(lockToken) : null;
    }

    private void finishSuccess(
            Claim claim,
            Instant started,
            EmailGateway.DeliveryReceipt receipt
    ) {
        LocalDateTime completed = utc(Instant.now());
        transactions.executeWithoutResult(status -> {
            int updated = jdbcTemplate.update(
                    """
                    UPDATE finance_cobranca_email
                    SET status = 'ENVIADA', tentativas = ?,
                        proxima_tentativa_em = NULL,
                        lock_token = NULL, lock_ate = NULL,
                        provider = ?, provider_message_id = ?, enviada_em = ?,
                        erro_categoria = NULL, erro_sanitizado = NULL,
                        atualizado_por = responsavel_id,
                        versao_linha = versao_linha + 1
                    WHERE id = ? AND status = 'ENVIANDO' AND lock_token = ?
                    """,
                    claim.attemptNumber(),
                    receipt.provider(),
                    receipt.messageId(),
                    completed,
                    claim.id(),
                    claim.lockToken()
            );
            requireFinalize(updated);
            insertAttempt(
                    claim,
                    "ENVIADA",
                    receipt.provider(),
                    receipt.messageId(),
                    null,
                    null,
                    null,
                    utc(started),
                    completed
            );
            history(
                    claim,
                    "ENVIADA",
                    Map.of(
                            "status", "ENVIADA",
                            "provider", receipt.provider(),
                            "providerMessageId", receipt.messageId(),
                            "tentativa", claim.attemptNumber()
                    )
            );
        });
        projectAttempt(
                claim,
                "COBRANCA_EMAIL_ENVIADA",
                Map.of(
                        "resultado", "ENVIADA",
                        "provider", receipt.provider(),
                        "providerMessageId", receipt.messageId(),
                        "tentativa", claim.attemptNumber()
                )
        );
    }

    private void finishFailure(
            Claim claim,
            Instant started,
            String category,
            String sanitizedMessage,
            boolean knownSafeToRetry
    ) {
        Instant now = Instant.now();
        Optional<Instant> retryAt = retryPolicy.nextAttemptAt(
                claim.attemptNumber(), now, knownSafeToRetry
        );
        LocalDateTime completed = utc(now);
        LocalDateTime next = retryAt.map(this::utc).orElse(null);
        String safeCategory = limited(category, 80, "FALHA_PROVIDER");
        String safeMessage = limited(
                sanitizedMessage,
                1000,
                "Não foi possível confirmar o envio pelo provider."
        );
        transactions.executeWithoutResult(status -> {
            int updated = jdbcTemplate.update(
                    """
                    UPDATE finance_cobranca_email
                    SET status = 'FALHOU', tentativas = ?,
                        proxima_tentativa_em = ?,
                        lock_token = NULL, lock_ate = NULL,
                        erro_categoria = ?, erro_sanitizado = ?,
                        atualizado_por = responsavel_id,
                        versao_linha = versao_linha + 1
                    WHERE id = ? AND status = 'ENVIANDO' AND lock_token = ?
                    """,
                    claim.attemptNumber(),
                    next,
                    safeCategory,
                    safeMessage,
                    claim.id(),
                    claim.lockToken()
            );
            requireFinalize(updated);
            insertAttempt(
                    claim,
                    "FALHOU",
                    null,
                    null,
                    safeCategory,
                    safeMessage,
                    next,
                    utc(started),
                    completed
            );
            history(
                    claim,
                    "FALHOU",
                    Map.of(
                            "status", "FALHOU",
                            "erroCategoria", safeCategory,
                            "retentativaAutomatica", next != null,
                            "tentativa", claim.attemptNumber()
                    )
            );
        });
        projectAttempt(
                claim,
                "COBRANCA_EMAIL_FALHOU",
                Map.of(
                        "resultado", "FALHOU",
                        "erroCategoria", safeCategory,
                        "retentativaAutomatica", next != null,
                        "tentativa", claim.attemptNumber()
                )
        );
    }

    private void insertAttempt(
            Claim claim,
            String result,
            String provider,
            String providerMessageId,
            String errorCategory,
            String errorMessage,
            LocalDateTime nextAttempt,
            LocalDateTime started,
            LocalDateTime completed
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO finance_cobranca_tentativa (
                    id, cobranca_id, obra_id, numero_tentativa,
                    idempotency_key, resultado, provider,
                    provider_message_id, erro_categoria, erro_sanitizado,
                    proxima_tentativa_em, iniciada_em, concluida_em, criado_por
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(),
                claim.id(),
                claim.obraId(),
                claim.attemptNumber(),
                claim.attemptIdempotencyKey(),
                result,
                provider,
                providerMessageId,
                errorCategory,
                errorMessage,
                nextAttempt,
                started,
                completed,
                claim.responsibleId()
        );
    }

    private void history(Claim claim, String action, Map<String, ?> state) {
        jdbcTemplate.update(
                """
                INSERT INTO finance_cobranca_email_historico (
                    id, cobranca_id, obra_id, acao, ator_id, origem,
                    estado_anterior_json, estado_novo_json, resultado
                ) VALUES (?, ?, ?, ?, ?, 'SISTEMA', CAST(? AS JSON),
                          CAST(? AS JSON), 'SUCESSO')
                """,
                UUID.randomUUID().toString(),
                claim.id(),
                claim.obraId(),
                action,
                claim.responsibleId(),
                json(Map.of("status", "ENVIANDO")),
                json(state)
        );
    }

    private void projectAttempt(
            Claim claim,
            String event,
            Map<String, Object> state
    ) {
        ontology.success(
                "COBRANCA_TENTATIVA",
                claim.id() + ":" + claim.attemptNumber(),
                "Tentativa de cobrança por e-mail",
                event,
                claim.obraId(),
                new FinanceAuditContext(
                        claim.responsibleId(), null,
                        claim.idempotencyKey(), "SISTEMA"
                ),
                Map.of(),
                state,
                Map.of("COBRANCA_EMAIL", claim.id())
        );
    }

    private Claim mapClaim(ResultSet rs) throws SQLException {
        int attempt = rs.getInt("tentativas") + 1;
        String rootKey = rs.getString("idempotency_key");
        return new Claim(
                rs.getString("id"),
                rs.getString("obra_id"),
                rs.getString("alvo_tipo"),
                rs.getString("alvo_id"),
                rs.getString("responsavel_id"),
                attempt,
                rootKey,
                rootKey + ":attempt:" + attempt,
                rs.getString("previsualizacao_hash"),
                rs.getString("assunto_template"),
                rs.getString("corpo_texto_template"),
                stringSet(rs.getString("variaveis_permitidas_json")),
                rs.getString("configuracao_remetente_chave"),
                rs.getString("reply_to"),
                null
        );
    }

    private Set<String> stringSet(String json) {
        try {
            return Set.copyOf(objectMapper.readValue(
                    json,
                    new TypeReference<List<String>>() {
                    }
            ));
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
            throw new IllegalStateException("Falha ao registrar auditoria.");
        }
    }

    private String limited(String value, int max, String fallback) {
        String normalized = value == null || value.isBlank()
                ? fallback : value.strip();
        return normalized.length() <= max
                ? normalized : normalized.substring(0, max);
    }

    private void requireFinalize(int updated) {
        if (updated != 1) {
            throw new IllegalStateException(
                    "A posse da cobrança expirou antes da persistência."
            );
        }
    }

    private LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record Claim(
            String id,
            String obraId,
            String targetType,
            String targetId,
            String responsibleId,
            int attemptNumber,
            String idempotencyKey,
            String attemptIdempotencyKey,
            String previewHash,
            String subjectTemplate,
            String bodyTemplate,
            Set<String> allowedVariables,
            String senderConfigurationKey,
            String replyTo,
            String lockToken
    ) {
        Claim withLock(String token) {
            return new Claim(
                    id, obraId, targetType, targetId, responsibleId,
                    attemptNumber, idempotencyKey, attemptIdempotencyKey,
                    previewHash, subjectTemplate, bodyTemplate,
                    allowedVariables, senderConfigurationKey, replyTo, token
            );
        }
    }
}
