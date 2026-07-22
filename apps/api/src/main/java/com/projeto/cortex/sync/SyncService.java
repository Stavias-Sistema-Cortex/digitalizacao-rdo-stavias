package com.projeto.cortex.sync;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;

@Service
public class SyncService {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;
    private static final int MAX_MUTACOES_POR_PUSH = 100;
    private static final int CANONICAL_SCHEMA_VERSION = 13;
    private static final Set<String> CANONICAL_ENTITY_TYPES = Set.of(
            "RDO",
            "CONVERSA",
            "MENSAGEM",
            "MENSAGEM_ANEXO",
            "SOLICITACAO_COMPRA",
            "COMPRA"
    );
    private static final Map<String, String> CANONICAL_OPERATION_BY_TRANSPORT = Map.ofEntries(
            Map.entry("CRIAR_RDO", "CREATE"),
            Map.entry("ATUALIZAR_RDO_RASCUNHO", "UPDATE"),
            Map.entry("ENVIAR_RDO", "TRANSITION"),
            Map.entry("CRIAR_CONVERSA", "CREATE"),
            Map.entry("ADICIONAR_PARTICIPANTE_CONVERSA", "UPDATE"),
            Map.entry("REMOVER_PARTICIPANTE_CONVERSA", "UPDATE"),
            Map.entry("CRIAR_MENSAGEM", "CREATE"),
            Map.entry("EDITAR_MENSAGEM", "UPDATE"),
            Map.entry("EXCLUIR_MENSAGEM", "DELETE"),
            Map.entry("ADICIONAR_MENSAGEM_ANEXO", "CREATE"),
            Map.entry("CRIAR_SOLICITACAO_COMPRA", "CREATE"),
            Map.entry("ATUALIZAR_SOLICITACAO_COMPRA", "UPDATE"),
            Map.entry("ARQUIVAR_SOLICITACAO_COMPRA", "TRANSITION"),
            Map.entry("CRIAR_COMPRA", "CREATE"),
            Map.entry("ATUALIZAR_COMPRA", "UPDATE"),
            Map.entry("ALTERAR_STATUS_COMPRA", "TRANSITION"),
            Map.entry("DECIDIR_APROVACAO_COMPRA", "TRANSITION"),
            Map.entry("ARQUIVAR_COMPRA", "TRANSITION")
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final SyncOperationRegistry operationRegistry;
    private final CurrentUserService currentUserService;
    private final FinancialAccessService financialAccessService;

    public SyncService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate,
            SyncOperationRegistry operationRegistry,
            CurrentUserService currentUserService,
            FinancialAccessService financialAccessService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.operationRegistry = operationRegistry;
        this.currentUserService = currentUserService;
        this.financialAccessService = financialAccessService;
    }

    public SyncPullResponse pull(
            String dispositivoId,
            long afterCommitSeq,
            Integer requestedLimit
    ) {
        String currentUserId = currentUserService.requireUserId();
        validarDispositivoDoUsuario(dispositivoId, currentUserId);

        if (afterCommitSeq < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "afterCommitSeq não pode ser negativo.");
        }

        int limit = normalizarLimit(requestedLimit);
        int queryLimit = limit + 1;

        // Escopo por obra: o carregamento offline só traz eventos das obras
        // autorizadas (§13). Alfa recebe tudo; Beta recebe suas obras + catálogos
        // globais de referência, nunca eventos confidenciais de outras obras.
        FiltroPull filtro = filtroPorEscopo(
                currentUserService.allowedObraIds(currentUserId),
                financialAccessService.allowedObraIds(
                        currentUserId,
                        FinancialPermission.FINANCEIRO_VISUALIZAR
                ),
                financialAccessService.allowedUnitIds(
                        currentUserId,
                        FinancialPermission.FINANCEIRO_VISUALIZAR
                ).orElse(Set.of()),
                currentUserId
        );

        List<Object> parametros = new ArrayList<>();
        parametros.add(afterCommitSeq);
        parametros.addAll(filtro.parametros());
        parametros.add(queryLimit);

        List<SyncPullResponse.EventoSync> eventosComExtra = jdbcTemplate.query(
                "SELECT commit_seq, id, tipo_entidade, entidade_id, tipo_evento, "
                        + "fonte, payload_json, ocorrido_em, criado_em, versao_entidade "
                        + "FROM cortex_evento_operacional "
                        + "WHERE commit_seq > ?" + filtro.condicaoSql() + " "
                        + "ORDER BY commit_seq "
                        + "LIMIT ?",
                (rs, rowNum) -> new SyncPullResponse.EventoSync(
                        rs.getLong("commit_seq"),
                        rs.getString("id"),
                        rs.getString("tipo_entidade"),
                        rs.getString("entidade_id"),
                        rs.getString("tipo_evento"),
                        rs.getString("fonte"),
                        parseJson(rs.getString("payload_json")),
                        rs.getTimestamp("ocorrido_em").toLocalDateTime(),
                        rs.getTimestamp("criado_em").toLocalDateTime(),
                        rs.getObject("versao_entidade", Long.class)
                ),
                parametros.toArray()
        );

        boolean hasMore = eventosComExtra.size() > limit;

        List<SyncPullResponse.EventoSync> eventos = hasMore
                ? new ArrayList<>(eventosComExtra.subList(0, limit))
                : eventosComExtra;

        long nextCommitSeq = eventos.isEmpty()
                ? afterCommitSeq
                : eventos.get(eventos.size() - 1).commitSeq();

        jdbcTemplate.update(
                """
                UPDATE sync_dispositivo
                SET visto_por_ultimo_em = CURRENT_TIMESTAMP(6)
                WHERE id = ?
                  AND usuario_id = ?
                """,
                dispositivoId.trim(),
                currentUserId
        );

        return new SyncPullResponse(
                afterCommitSeq,
                nextCommitSeq,
                limit,
                hasMore,
                Instant.now(),
                eventos
        );
    }

    // Catálogos globais não-pessoais que o cliente offline precisa e que podem
    // ser entregues a qualquer usuário. Dados pessoais/confidenciais (colaborador,
    // frequência) NÃO entram nesta lista — só chegam via obra vinculada.
    private static final List<String> EVENTOS_REFERENCIA_GLOBAL =
            List.of("ATIVO", "EQUIPAMENTO", "SERVICO");

    private static final List<String> EVENTOS_FINANCEIROS = List.of(
            "ITEM_CONTRATUAL",
            "PREVISAO_FINANCEIRA",
            "PDOR",
            "CENTRO_CUSTO",
            "FORNECEDOR",
            "SOLICITACAO_COMPRA",
            "COMPRA",
            "PEDIDO_COMPRA",
            "REGRA_APROVACAO",
            "DECISAO_APROVACAO",
            "NOTA_FISCAL",
            "LANCAMENTO",
            "LIQUIDACAO",
            "LANCAMENTO_FINANCEIRO",
            "PAGAMENTO",
            "COBRANCA_EMAIL",
            "UNIDADE_FINANCEIRA",
            "RATEIO_FINANCEIRO",
            "COMPRA_ITEM",
            "DOCUMENTO_FISCAL"
    );

    private static final List<String> EVENTOS_FINANCEIROS_ALFA =
            List.of("PERMISSAO_FINANCEIRA");

    private static final List<String> EVENTOS_MENSAGENS = List.of(
            "CONVERSA",
            "MENSAGEM",
            "MENSAGEM_ANEXO"
    );

    /** Filtro de escopo do pull: condição SQL adicional e seus parâmetros. */
    record FiltroPull(String condicaoSql, List<Object> parametros) {
    }

    /**
     * Monta o filtro de obra do pull. Alfa (escopo global) não recebe filtro.
     * Beta recebe apenas eventos das obras vinculadas e catálogos globais de
     * referência — nunca eventos confidenciais de outras obras nem dados
     * pessoais. Visível no pacote para teste.
     */
    FiltroPull filtroPorEscopo(
            Optional<Set<String>> obrasAutorizadas,
            Set<String> obrasFinanceirasAutorizadas
    ) {
        return filtroPorEscopo(
                obrasAutorizadas,
                obrasFinanceirasAutorizadas,
                Set.of(),
                null
        );
    }

    FiltroPull filtroPorEscopo(
            Optional<Set<String>> obrasAutorizadas,
            Set<String> obrasFinanceirasAutorizadas,
            String currentUserId
    ) {
        return filtroPorEscopo(
                obrasAutorizadas,
                obrasFinanceirasAutorizadas,
                Set.of(),
                currentUserId
        );
    }

    FiltroPull filtroPorEscopo(
            Optional<Set<String>> obrasAutorizadas,
            Set<String> obrasFinanceirasAutorizadas,
            Set<String> unidadesFinanceirasAutorizadas,
            String currentUserId
    ) {
        if (obrasAutorizadas.isEmpty()) {
            return new FiltroPull("", List.of());
        }

        Set<String> obras = obrasAutorizadas.get();
        List<Object> parametros = new ArrayList<>();
        StringBuilder escopo = new StringBuilder();

        if (!obras.isEmpty()) {
            escopo.append("obra_id IN (")
                    .append(placeholders(obras.size()))
                    .append(")");
            parametros.addAll(obras);
        }

        if (escopo.length() > 0) {
            escopo.append(" OR ");
        }
        escopo.append("(obra_id IS NULL AND tipo_entidade IN (")
                .append(placeholders(EVENTOS_REFERENCIA_GLOBAL.size()))
                .append("))");
        parametros.addAll(EVENTOS_REFERENCIA_GLOBAL);

        List<Object> scopedParameters = new ArrayList<>();
        String tiposRestritos = placeholders(
                EVENTOS_FINANCEIROS.size()
                        + EVENTOS_FINANCEIROS_ALFA.size()
                        + EVENTOS_MENSAGENS.size()
        );
        scopedParameters.addAll(EVENTOS_FINANCEIROS);
        scopedParameters.addAll(EVENTOS_FINANCEIROS_ALFA);
        scopedParameters.addAll(EVENTOS_MENSAGENS);
        scopedParameters.addAll(parametros);

        StringBuilder condition = new StringBuilder(
                " AND ((tipo_entidade NOT IN (" + tiposRestritos + ") AND ("
                        + escopo + "))"
        );

        Set<String> financialScope = obrasFinanceirasAutorizadas == null
                ? Set.of()
                : obrasFinanceirasAutorizadas;
        if (!financialScope.isEmpty()) {
            condition.append(" OR (tipo_entidade IN (")
                    .append(placeholders(EVENTOS_FINANCEIROS.size()))
                    .append(") AND obra_id IN (")
                    .append(placeholders(financialScope.size()))
                    .append("))");
            scopedParameters.addAll(EVENTOS_FINANCEIROS);
            scopedParameters.addAll(financialScope);
        }
        Set<String> unitScope = unidadesFinanceirasAutorizadas == null
                ? Set.of()
                : unidadesFinanceirasAutorizadas;
        if (!unitScope.isEmpty()) {
            condition.append(" OR (tipo_entidade IN (")
                    .append(placeholders(EVENTOS_FINANCEIROS.size()))
                    .append(") AND ((tipo_entidade = 'UNIDADE_FINANCEIRA' ")
                    .append("AND entidade_id IN (")
                    .append(placeholders(unitScope.size()))
                    .append(")) OR EXISTS (")
                    .append("SELECT 1 FROM cortex_relacao relacao ")
                    .append("WHERE relacao.origem_tipo = tipo_entidade ")
                    .append("AND relacao.origem_id = entidade_id ")
                    .append("AND relacao.destino_tipo = 'UNIDADE_FINANCEIRA' ")
                    .append("AND relacao.destino_id IN (")
                    .append(placeholders(unitScope.size()))
                    .append(") AND relacao.ativa = TRUE)))");
            scopedParameters.addAll(EVENTOS_FINANCEIROS);
            scopedParameters.addAll(unitScope);
            scopedParameters.addAll(unitScope);
        }
        if (currentUserId != null && !currentUserId.isBlank()) {
            condition.append(" OR (tipo_entidade IN (")
                    .append(placeholders(EVENTOS_MENSAGENS.size()))
                    .append(") AND EXISTS (")
                    .append("SELECT 1 ")
                    .append("FROM cortex_evento_visibilidade cev ")
                    .append("JOIN conversa cv ON cv.id = cev.escopo_id ")
                    .append("JOIN conversa_participante cp ")
                    .append("ON cp.conversa_id = cv.id ")
                    .append("AND cp.colaborador_id = ? ")
                    .append("AND cp.status = 'ATIVO' ")
                    .append("AND cp.removido_em IS NULL ")
                    .append("AND cp.deletado_em IS NULL ")
                    .append("WHERE cev.evento_id = cortex_evento_operacional.id ")
                    .append("AND cev.escopo_tipo = 'CONVERSATION_PARTICIPANT' ")
                    .append("AND cv.status = 'ATIVA' ")
                    .append("AND cv.deletado_em IS NULL ")
                    .append("AND (cv.tipo IN ('DIRETA', 'GRUPO') ")
                    .append("OR (cv.tipo = 'OBRA' AND EXISTS (")
                    .append("SELECT 1 FROM vinculo_colaborador_obra v ")
                    .append("WHERE v.obra_id = cv.obra_id ")
                    .append("AND v.colaborador_id = ? AND v.status = 'ATIVO')) ")
                    .append("OR (cv.tipo = 'EQUIPE' AND EXISTS (")
                    .append("SELECT 1 FROM equipe e ")
                    .append("JOIN equipe_membro em ON em.equipe_id = e.id ")
                    .append("JOIN vinculo_colaborador_obra v ON v.obra_id = e.obra_id ")
                    .append("WHERE e.id = cv.equipe_id AND e.obra_id = cv.obra_id ")
                    .append("AND e.status = 'ATIVA' AND e.deletado_em IS NULL ")
                    .append("AND em.colaborador_id = ? AND em.status = 'ATIVO' ")
                    .append("AND em.removido_em IS NULL AND em.deletado_em IS NULL ")
                    .append("AND v.colaborador_id = ? AND v.status = 'ATIVO')))" )
                    .append("))");
            scopedParameters.addAll(EVENTOS_MENSAGENS);
            scopedParameters.add(currentUserId);
            scopedParameters.add(currentUserId);
            scopedParameters.add(currentUserId);
            scopedParameters.add(currentUserId);
        }
        condition.append(")");

        return new FiltroPull(condition.toString(), scopedParameters);
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    public SyncDeviceResponse registrarDispositivo(SyncDeviceRequest request) {
        if (request == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Dados do dispositivo são obrigatórios."
            );
        }

        String currentUserId = currentUserService.requireUserId();
        String id = primeiroNaoVazio(
                request.id(),
                UUID.randomUUID().toString()
        ).trim();
        validarDispositivoDisponivelOuDoUsuario(id, currentUserId);
        String tipo = primeiroNaoVazio(request.tipo(), "WEB").trim();

        jdbcTemplate.update(
                """
                INSERT INTO sync_dispositivo (
                    id,
                    nome,
                    tipo,
                    usuario_id,
                    ativo
                ) VALUES (?, ?, ?, ?, TRUE)
                ON CONFLICT (id) DO UPDATE SET
                    nome = EXCLUDED.nome,
                    tipo = EXCLUDED.tipo,
                    usuario_id = EXCLUDED.usuario_id,
                    ativo = TRUE,
                    visto_por_ultimo_em = CURRENT_TIMESTAMP(6),
                    desativado_em = NULL
                """,
                id,
                request.nome(),
                tipo,
                currentUserId
        );

        jdbcTemplate.update(
                """
                INSERT INTO sync_estado_dispositivo (
                    dispositivo_id,
                    ultimo_evento_recebido_seq,
                    ultimo_evento_recebido_commit_seq
                ) VALUES (?, 0, 0)
                ON CONFLICT (dispositivo_id) DO UPDATE SET
                    atualizado_em = CURRENT_TIMESTAMP(6)
                """,
                id
        );

        return buscarDispositivo(id);
    }

    public SyncAckResponse ack(SyncAckRequest request) {
        if (request == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Dados de ACK são obrigatórios."
            );
        }
        String currentUserId = currentUserService.requireUserId();
        validarDispositivoDoUsuario(request.dispositivoId(), currentUserId);

        if (request.ultimoEventoRecebidoCommitSeq() < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "ultimoEventoRecebidoCommitSeq não pode ser negativo."
            );
        }

        long maxCommitSeq = maxCommitSeq();

        if (request.ultimoEventoRecebidoCommitSeq() > maxCommitSeq) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "ACK não pode avançar além do último commit_seq existente."
            );
        }

        jdbcTemplate.update(
                """
                INSERT INTO sync_estado_dispositivo (
                    dispositivo_id,
                    ultimo_evento_recebido_seq,
                    ultimo_evento_recebido_commit_seq,
                    ultimo_pull_em
                ) VALUES (?, 0, ?, CURRENT_TIMESTAMP(6))
                ON CONFLICT (dispositivo_id) DO UPDATE SET
                    ultimo_evento_recebido_commit_seq = GREATEST(
                        sync_estado_dispositivo.ultimo_evento_recebido_commit_seq,
                        EXCLUDED.ultimo_evento_recebido_commit_seq
                    ),
                    ultimo_pull_em = CURRENT_TIMESTAMP(6)
                """,
                request.dispositivoId(),
                request.ultimoEventoRecebidoCommitSeq()
        );

        jdbcTemplate.update(
                """
                UPDATE sync_dispositivo
                SET visto_por_ultimo_em = CURRENT_TIMESTAMP(6)
                WHERE id = ?
                  AND usuario_id = ?
                """,
                request.dispositivoId(),
                currentUserId
        );

        return new SyncAckResponse(
                request.dispositivoId(),
                request.ultimoEventoRecebidoCommitSeq(),
                Instant.now()
        );
    }

    public SyncPushResponse push(SyncPushRequest request) {
        if (request == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Dados de push são obrigatórios."
            );
        }
        String currentUserId = currentUserService.requireUserId();
        validarDispositivoDoUsuario(request.dispositivoId(), currentUserId);

        List<SyncPushRequest.MutacaoCliente> mutacoes = request.mutacoes() == null
                ? List.of()
                : request.mutacoes();

        if (mutacoes.size() > MAX_MUTACOES_POR_PUSH) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Máximo de " + MAX_MUTACOES_POR_PUSH + " mutações por push."
            );
        }

        List<SyncPushResponse.ResultadoMutacao> resultados = new ArrayList<>();

        for (SyncPushRequest.MutacaoCliente mutacao : mutacoes) {
            resultados.add(processarMutacaoComSeguranca(
                    request.dispositivoId(),
                    currentUserId,
                    mutacao
            ));
        }

        jdbcTemplate.update(
                """
                UPDATE sync_dispositivo
                SET visto_por_ultimo_em = CURRENT_TIMESTAMP(6)
                WHERE id = ?
                  AND usuario_id = ?
                """,
                request.dispositivoId(),
                currentUserId
        );

        jdbcTemplate.update(
                """
                UPDATE sync_estado_dispositivo
                SET ultimo_push_em = CURRENT_TIMESTAMP(6)
                WHERE dispositivo_id = ?
                """,
                request.dispositivoId()
        );

        return new SyncPushResponse(
                request.dispositivoId(),
                Instant.now(),
                resultados
        );
    }

    private SyncPushResponse.ResultadoMutacao processarMutacaoComSeguranca(
            String dispositivoId,
            String currentUserId,
            SyncPushRequest.MutacaoCliente mutacao
    ) {
        try {
            validarMutacao(mutacao);

            SyncPushResponse.ResultadoMutacao existente = buscarResultadoMutacaoExistenteOuNull(
                    dispositivoId,
                    currentUserId,
                    mutacao
            );

            if (existente != null) {
                if (!requestMatchesExisting(dispositivoId, currentUserId, mutacao)) {
                    return idempotencyMismatch(mutacao);
                }
                if ("ERRO".equals(existente.status())) {
                    validarRastroCanonicoParaAplicacao(
                            dispositivoId,
                            currentUserId,
                            mutacao
                    );
                    return transactionTemplate.execute(
                            status -> reprocessarMutacaoComErro(dispositivoId, mutacao)
                    );
                }
                return existente;
            }

            validarRastroCanonicoParaAplicacao(
                    dispositivoId,
                    currentUserId,
                    mutacao
            );

            return transactionTemplate.execute(status -> processarMutacaoAplicavel(dispositivoId, mutacao));
        } catch (DuplicateKeyException exception) {
            SyncPushResponse.ResultadoMutacao existing = buscarResultadoMutacaoExistente(
                    dispositivoId,
                    currentUserId,
                    mutacao.clientMutationId(),
                    isCanonical(mutacao)
            );
            return requestMatchesExisting(dispositivoId, currentUserId, mutacao)
                    ? existing
                    : idempotencyMismatch(mutacao);
        } catch (SyncBaseVersionConflictException exception) {
            return registrarConflitoEmNovaTransacao(dispositivoId, mutacao, exception);
        } catch (SyncTraceRejectionException exception) {
            return registrarRejeicaoEmNovaTransacao(
                    dispositivoId,
                    mutacao,
                    exception.getMessage(),
                    exception.category
            );
        } catch (ResponseStatusException exception) {
            String erro = exception.getReason() == null
                    ? "Mutação rejeitada pelo backend."
                    : exception.getReason();

            return isCanonical(mutacao)
                    ? registrarRejeicaoEmNovaTransacao(
                            dispositivoId,
                            mutacao,
                            erro,
                            "VALIDATION_OR_AUTHORIZATION"
                    )
                    : registrarErroEmNovaTransacao(
                            dispositivoId,
                            mutacao,
                            erro,
                            "VALIDATION_OR_AUTHORIZATION"
                    );
        } catch (RuntimeException exception) {
            String erro = exception.getClass().getSimpleName() + ": " + primeiroNaoVazio(
                    exception.getMessage(),
                    "Erro inesperado ao processar mutação."
            );

            return registrarErroEmNovaTransacao(
                    dispositivoId,
                    mutacao,
                    erro,
                    "INTERNAL"
            );
        }
    }

    private SyncPushResponse.ResultadoMutacao processarMutacaoAplicavel(
            String dispositivoId,
            SyncPushRequest.MutacaoCliente mutacao
    ) {
        inserirMutacaoPendente(dispositivoId, mutacao);
        return aplicarMutacaoRegistrada(dispositivoId, mutacao);
    }

    private SyncPushResponse.ResultadoMutacao reprocessarMutacaoComErro(
            String dispositivoId,
            SyncPushRequest.MutacaoCliente mutacao
    ) {
        reabrirMutacaoComErro(dispositivoId, mutacao);
        return aplicarMutacaoRegistrada(dispositivoId, mutacao);
    }

    private SyncPushResponse.ResultadoMutacao aplicarMutacaoRegistrada(
            String dispositivoId,
            SyncPushRequest.MutacaoCliente mutacao
    ) {
        SyncOperationHandler handler = operationRegistry.require(
                mutacao.operacao()
        );
        validarBaseVersao(mutacao, handler);

        AppliedSyncMutation applied = handler.apply(
                mutacao,
                new SyncMutationContext(
                        currentUserService.requireUserId(),
                        dispositivoId
                )
        );
        requireAppliedContract(handler, applied);
        long commitSeq = commitSeqEntidade(
                applied.entityType(),
                applied.entityId()
        );
        long versaoEntidade = versaoAtualEntidade(
                applied.entityType(),
                applied.entityId()
        );
        if (isCanonical(mutacao)) {
            vincularEventoCanonico(
                    commitSeq,
                    dispositivoId,
                    mutacao,
                    versaoEntidade
            );
        }

        ObjectNode resultado = applied.result() != null
                && applied.result().isObject()
                ? (ObjectNode) applied.result().deepCopy()
                : objectMapper.createObjectNode();
        resultado.put("versaoEntidade", versaoEntidade);

        jdbcTemplate.update(
                """
                UPDATE sync_mutacao_cliente
                SET
                    status = 'APLICADA',
                    entidade_tipo = ?,
                    entidade_id = ?,
                    evento_servidor_commit_seq = ?,
                    resultado_json = ?::jsonb,
                    erro = NULL,
                    erro_categoria = NULL,
                    conflito_json = NULL,
                    aplicada_em = CURRENT_TIMESTAMP(6)
                WHERE client_mutation_id = ?
                  AND (
                    (schema_version = 13 AND proprietario_id = ?)
                    OR (schema_version < 13 AND dispositivo_id = ?)
                  )
                """,
                applied.entityType(),
                applied.entityId(),
                commitSeq,
                toJson(resultado),
                mutacao.clientMutationId(),
                currentUserService.requireUserId(),
                dispositivoId
        );

        return new SyncPushResponse.ResultadoMutacao(
                mutacao.clientMutationId(),
                "APLICADA",
                applied.entityType(),
                applied.entityId(),
                mutacao.operacao(),
                commitSeq,
                resultado,
                objectMapper.createObjectNode(),
                null
        );
    }

    private void reabrirMutacaoComErro(
            String dispositivoId,
            SyncPushRequest.MutacaoCliente mutacao
    ) {
        int updated = jdbcTemplate.update(
                """
                UPDATE sync_mutacao_cliente
                SET
                    entidade_tipo = ?,
                    entidade_id = ?,
                    operacao = ?,
                    base_versao = ?,
                    payload_json = ?::jsonb,
                    payload_hash = ?,
                    envelope_hash = ?,
                    correlacao_id = ?,
                    dispositivo_id = ?,
                    status = 'PENDENTE',
                    erro = NULL,
                    erro_categoria = NULL,
                    resultado_json = NULL,
                    conflito_json = NULL,
                    evento_servidor_commit_seq = NULL,
                    recebida_em = CURRENT_TIMESTAMP(6),
                    aplicada_em = NULL
                WHERE client_mutation_id = ?
                  AND (
                    (schema_version = 13 AND proprietario_id = ?)
                    OR (schema_version < 13 AND dispositivo_id = ?)
                  )
                  AND status = 'ERRO'
                """,
                primeiroNaoVazio(mutacao.entidadeTipo(), "RDO"),
                mutacao.entidadeId(),
                operacaoSeguraParaBanco(mutacao.operacao()),
                mutacao.baseVersao(),
                toJson(mutacao.payload()),
                persistedPayloadHash(mutacao),
                envelopeHash(mutacao),
                correlationId(mutacao),
                dispositivoId,
                mutacao.clientMutationId(),
                currentUserService.requireUserId(),
                dispositivoId
        );

        if (updated == 0) {
            throw new DuplicateKeyException("Mutação já reprocessada por outra requisição.");
        }
    }

    private SyncPushResponse.ResultadoMutacao registrarConflitoEmNovaTransacao(
            String dispositivoId,
            SyncPushRequest.MutacaoCliente mutacao,
            SyncBaseVersionConflictException exception
    ) {
        return transactionTemplate.execute(status -> {
            JsonNode conflito = objectMapper.valueToTree(new ConflitoVersao(
                    exception.entidadeTipo,
                    exception.entidadeId,
                    exception.baseVersao,
                    exception.versaoAtual
            ));

            registrarMutacaoFinalizada(
                    dispositivoId,
                    mutacao,
                    "DESCARTADA",
                    "Conflito de versão.",
                    objectMapper.createObjectNode(),
                    conflito,
                    null,
                    "VERSION_CONFLICT"
            );

            return new SyncPushResponse.ResultadoMutacao(
                    mutacao.clientMutationId(),
                    "DESCARTADA",
                    mutacao.entidadeTipo(),
                    mutacao.entidadeId(),
                    mutacao.operacao(),
                    null,
                    objectMapper.createObjectNode(),
                    conflito,
                    "Conflito de versão."
            );
        });
    }

    private SyncPushResponse.ResultadoMutacao registrarErroEmNovaTransacao(
            String dispositivoId,
            SyncPushRequest.MutacaoCliente mutacao,
            String erro,
            String errorCategory
    ) {
        if (mutacao == null
                || !identificadorClienteSeguro(mutacao.clientMutationId())) {
            return new SyncPushResponse.ResultadoMutacao(
                    null,
                    "ERRO",
                    null,
                    null,
                    null,
                    null,
                    objectMapper.createObjectNode(),
                    objectMapper.createObjectNode(),
                    erro
            );
        }

        return transactionTemplate.execute(status -> {
            registrarMutacaoFinalizada(
                    dispositivoId,
                    mutacao,
                    "ERRO",
                    erro,
                    objectMapper.createObjectNode(),
                    objectMapper.createObjectNode(),
                    null,
                    errorCategory
            );

            return new SyncPushResponse.ResultadoMutacao(
                    mutacao.clientMutationId(),
                    "ERRO",
                    mutacao.entidadeTipo(),
                    mutacao.entidadeId(),
                    mutacao.operacao(),
                    null,
                    objectMapper.createObjectNode(),
                    objectMapper.createObjectNode(),
                    erro
            );
        });
    }

    private SyncPushResponse.ResultadoMutacao registrarRejeicaoEmNovaTransacao(
            String dispositivoId,
            SyncPushRequest.MutacaoCliente mutacao,
            String erro,
            String errorCategory
    ) {
        ObjectNode resultado = rejectionResult(errorCategory, erro);
        if (mutacao == null
                || !identificadorClienteSeguro(mutacao.clientMutationId())) {
            return new SyncPushResponse.ResultadoMutacao(
                    null,
                    "REJEITADA",
                    null,
                    null,
                    null,
                    null,
                    resultado,
                    objectMapper.createObjectNode(),
                    erro
            );
        }

        // Malformed canonical provenance is intentionally not copied into the
        // durable ledger. The safe category remains in the response, while a
        // valid canonical rejection is persisted for deterministic replay.
        if (isCanonical(mutacao) && !canonicalReceiptPersistable(mutacao)) {
            return rejectedResult(mutacao, erro, resultado);
        }

        return transactionTemplate.execute(status -> {
            registrarMutacaoFinalizada(
                    dispositivoId,
                    mutacao,
                    "REJEITADA",
                    erro,
                    resultado,
                    objectMapper.createObjectNode(),
                    null,
                    errorCategory
            );
            return rejectedResult(mutacao, erro, resultado);
        });
    }

    private SyncPushResponse.ResultadoMutacao rejectedResult(
            SyncPushRequest.MutacaoCliente mutacao,
            String erro,
            ObjectNode resultado
    ) {
        return new SyncPushResponse.ResultadoMutacao(
                mutacao.clientMutationId(),
                "REJEITADA",
                mutacao.entidadeTipo(),
                mutacao.entidadeId(),
                mutacao.operacao(),
                null,
                resultado,
                objectMapper.createObjectNode(),
                erro
        );
    }

    private ObjectNode rejectionResult(String category, String message) {
        ObjectNode rejection = objectMapper.createObjectNode();
        rejection.put("categoria", category);
        rejection.put("mensagem", message);
        ObjectNode result = objectMapper.createObjectNode();
        result.set("rejeicao", rejection);
        return result;
    }

    private void registrarMutacaoFinalizada(
            String dispositivoId,
            SyncPushRequest.MutacaoCliente mutacao,
            String status,
            String erro,
            JsonNode resultado,
            JsonNode conflito,
            Long eventoServidorCommitSeq,
            String errorCategory
    ) {
        if (isCanonical(mutacao)) {
            registrarMutacaoCanonicaFinalizada(
                    dispositivoId,
                    mutacao,
                    status,
                    erro,
                    resultado,
                    conflito,
                    eventoServidorCommitSeq,
                    errorCategory
            );
            return;
        }
        jdbcTemplate.update(
                """
                INSERT INTO sync_mutacao_cliente (
                    id,
                    dispositivo_id,
                    proprietario_id,
                    client_mutation_id,
                    correlacao_id,
                    entidade_tipo,
                    entidade_id,
                    operacao,
                    base_versao,
                    payload_json,
                    payload_hash,
                    status,
                    erro,
                    resultado_json,
                    conflito_json,
                    erro_categoria,
                    evento_servidor_commit_seq,
                    criada_no_cliente_em,
                    aplicada_em
                ) VALUES (
                    ?, ?,
                    (SELECT usuario_id FROM sync_dispositivo WHERE id = ?),
                    ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?,
                    CURRENT_TIMESTAMP(6)
                )
                ON CONFLICT (dispositivo_id, client_mutation_id) DO UPDATE SET
                    status = EXCLUDED.status,
                    erro = EXCLUDED.erro,
                    erro_categoria = EXCLUDED.erro_categoria,
                    resultado_json = EXCLUDED.resultado_json,
                    conflito_json = EXCLUDED.conflito_json,
                    evento_servidor_commit_seq = EXCLUDED.evento_servidor_commit_seq,
                    aplicada_em = CURRENT_TIMESTAMP(6)
                """,
                UUID.randomUUID().toString(),
                dispositivoId,
                dispositivoId,
                mutacao.clientMutationId(),
                correlationId(mutacao),
                primeiroNaoVazio(mutacao.entidadeTipo(), "RDO"),
                mutacao.entidadeId(),
                operacaoSeguraParaBanco(mutacao.operacao()),
                mutacao.baseVersao(),
                toJson(mutacao.payload()),
                payloadHash(mutacao),
                status,
                erro,
                toJson(resultado),
                toJson(conflito),
                errorCategory,
                eventoServidorCommitSeq,
                mutacao.criadaNoClienteEm()
        );
    }

    private void inserirMutacaoPendente(String dispositivoId, SyncPushRequest.MutacaoCliente mutacao) {
        if (isCanonical(mutacao)) {
            inserirMutacaoCanonicaPendente(dispositivoId, mutacao);
            return;
        }
        jdbcTemplate.update(
                """
                INSERT INTO sync_mutacao_cliente (
                    id,
                    dispositivo_id,
                    proprietario_id,
                    client_mutation_id,
                    correlacao_id,
                    entidade_tipo,
                    entidade_id,
                    operacao,
                    base_versao,
                    payload_json,
                    payload_hash,
                    status,
                    criada_no_cliente_em
                ) VALUES (
                    ?, ?,
                    (SELECT usuario_id FROM sync_dispositivo WHERE id = ?),
                    ?, ?, ?, ?, ?, ?, ?::jsonb, ?, 'PENDENTE', ?
                )
                """,
                UUID.randomUUID().toString(),
                dispositivoId,
                dispositivoId,
                mutacao.clientMutationId(),
                correlationId(mutacao),
                primeiroNaoVazio(mutacao.entidadeTipo(), "RDO"),
                mutacao.entidadeId(),
                operacaoSeguraParaBanco(mutacao.operacao()),
                mutacao.baseVersao(),
                toJson(mutacao.payload()),
                payloadHash(mutacao),
                mutacao.criadaNoClienteEm()
        );
    }

    private void inserirMutacaoCanonicaPendente(
            String dispositivoId,
            SyncPushRequest.MutacaoCliente mutacao
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO sync_mutacao_cliente (
                    id, dispositivo_id, proprietario_id, client_mutation_id,
                    correlacao_id, entidade_tipo, entidade_id, operacao,
                    base_versao, payload_json, payload_hash, schema_version,
                    obra_id, operacao_canonica, changed_fields_json,
                    entidades_relacionadas_json, ocorrido_em, envelope_hash,
                    escopo_autorizacao_json, field_patch_json, base_values_json,
                    evento_cliente_id, causacao_id, dependencias_json,
                    status, criada_no_cliente_em
                ) VALUES (
                    ?, ?, (SELECT usuario_id FROM sync_dispositivo WHERE id = ?),
                    ?, ?, ?, ?, ?, ?, ?::jsonb, ?, 13, ?, ?, ?::jsonb,
                    ?::jsonb, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?,
                    ?::jsonb, 'PENDENTE', ?
                )
                """,
                UUID.randomUUID().toString(),
                dispositivoId,
                dispositivoId,
                mutacao.clientMutationId(),
                mutacao.trace().correlationId(),
                mutacao.entityType(),
                mutacao.entityId(),
                mutacao.operacao(),
                mutacao.baseVersion(),
                toJson(mutacao.payload()),
                persistedPayloadHash(mutacao),
                mutacao.obraId(),
                mutacao.operation(),
                toJson(objectMapper.valueToTree(mutacao.changedFields())),
                toJson(objectMapper.valueToTree(mutacao.relatedEntities())),
                Timestamp.from(Instant.parse(mutacao.occurredAt())),
                envelopeHash(mutacao),
                toJson(objectMapper.valueToTree(mutacao.trace().authorizationScope())),
                toJson(mutacao.fieldPatch().changed()),
                toJson(mutacao.fieldPatch().baseValues()),
                mutacao.trace().ontologyEventId(),
                mutacao.trace().causationId(),
                toJson(objectMapper.valueToTree(mutacao.dependsOnMutationIds())),
                canonicalOccurredAtLocal(mutacao)
        );
    }

    private void registrarMutacaoCanonicaFinalizada(
            String dispositivoId,
            SyncPushRequest.MutacaoCliente mutacao,
            String status,
            String erro,
            JsonNode resultado,
            JsonNode conflito,
            Long eventoServidorCommitSeq,
            String errorCategory
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO sync_mutacao_cliente (
                    id, dispositivo_id, proprietario_id, client_mutation_id,
                    correlacao_id, entidade_tipo, entidade_id, operacao,
                    base_versao, payload_json, payload_hash, schema_version,
                    obra_id, operacao_canonica, changed_fields_json,
                    entidades_relacionadas_json, ocorrido_em, envelope_hash,
                    escopo_autorizacao_json, field_patch_json, base_values_json,
                    evento_cliente_id, causacao_id, dependencias_json, status,
                    erro, resultado_json, conflito_json, erro_categoria,
                    evento_servidor_commit_seq, criada_no_cliente_em, aplicada_em
                ) VALUES (
                    ?, ?, (SELECT usuario_id FROM sync_dispositivo WHERE id = ?),
                    ?, ?, ?, ?, ?, ?, ?::jsonb, ?, 13, ?, ?, ?::jsonb,
                    ?::jsonb, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?,
                    ?::jsonb, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?,
                    CURRENT_TIMESTAMP(6)
                )
                ON CONFLICT (proprietario_id, client_mutation_id)
                    WHERE schema_version = 13 AND proprietario_id IS NOT NULL
                DO UPDATE SET
                    status = EXCLUDED.status,
                    erro = EXCLUDED.erro,
                    erro_categoria = EXCLUDED.erro_categoria,
                    resultado_json = EXCLUDED.resultado_json,
                    conflito_json = EXCLUDED.conflito_json,
                    evento_servidor_commit_seq = EXCLUDED.evento_servidor_commit_seq,
                    aplicada_em = CURRENT_TIMESTAMP(6)
                """,
                UUID.randomUUID().toString(),
                dispositivoId,
                dispositivoId,
                mutacao.clientMutationId(),
                mutacao.trace().correlationId(),
                mutacao.entityType(),
                mutacao.entityId(),
                mutacao.operacao(),
                mutacao.baseVersion(),
                toJson(mutacao.payload()),
                persistedPayloadHash(mutacao),
                mutacao.obraId(),
                mutacao.operation(),
                toJson(objectMapper.valueToTree(mutacao.changedFields())),
                toJson(objectMapper.valueToTree(mutacao.relatedEntities())),
                Timestamp.from(Instant.parse(mutacao.occurredAt())),
                envelopeHash(mutacao),
                toJson(objectMapper.valueToTree(mutacao.trace().authorizationScope())),
                toJson(mutacao.fieldPatch().changed()),
                toJson(mutacao.fieldPatch().baseValues()),
                mutacao.trace().ontologyEventId(),
                mutacao.trace().causationId(),
                toJson(objectMapper.valueToTree(mutacao.dependsOnMutationIds())),
                status,
                erro,
                toJson(resultado),
                toJson(conflito),
                errorCategory,
                eventoServidorCommitSeq,
                canonicalOccurredAtLocal(mutacao)
        );
    }

    private void validarBaseVersao(
            SyncPushRequest.MutacaoCliente mutacao,
            SyncOperationHandler handler
    ) {
        if (!handler.requiresBaseVersion(mutacao.operacao())) {
            return;
        }

        String entidadeId = exigirEntidadeId(mutacao);

        if (mutacao.baseVersao() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "baseVersao é obrigatória para operação em entidade existente."
            );
        }

        long versaoAtual = versaoAtualEntidade(
                handler.entityType(),
                entidadeId
        );

        if (versaoAtual != mutacao.baseVersao()) {
            throw new SyncBaseVersionConflictException(
                    handler.entityType(),
                    entidadeId,
                    mutacao.baseVersao(),
                    versaoAtual
            );
        }
    }

    private long versaoAtualEntidade(String entidadeTipo, String entidadeId) {
        try {
            Long versao = jdbcTemplate.queryForObject(
                    """
                    SELECT versao_entidade
                    FROM cortex_estado_entidade
                    WHERE tipo_entidade = ?
                      AND entidade_id = ?
                    """,
                    Long.class,
                    entidadeTipo,
                    entidadeId
            );

            return versao == null ? 0 : versao;
        } catch (EmptyResultDataAccessException exception) {
            return 0;
        }
    }

    private long commitSeqEntidade(String entidadeTipo, String entidadeId) {
        Long commitSeq = jdbcTemplate.queryForObject(
                """
                SELECT ev.commit_seq
                FROM cortex_estado_entidade e
                JOIN cortex_evento_operacional ev
                    ON ev.sequencia = e.ultimo_evento_seq
                WHERE e.tipo_entidade = ?
                  AND e.entidade_id = ?
                """,
                Long.class,
                entidadeTipo,
                entidadeId
        );

        if (commitSeq == null) {
            throw new IllegalStateException("Evento da entidade não encontrado para o cursor de sync.");
        }

        return commitSeq;
    }

    private SyncPushResponse.ResultadoMutacao buscarResultadoMutacaoExistenteOuNull(
            String dispositivoId,
            String currentUserId,
            SyncPushRequest.MutacaoCliente mutation
    ) {
        try {
            return buscarResultadoMutacaoExistente(
                    dispositivoId,
                    currentUserId,
                    mutation.clientMutationId(),
                    isCanonical(mutation)
            );
        } catch (EmptyResultDataAccessException exception) {
            return null;
        }
    }

    private boolean requestMatchesExisting(
            String dispositivoId,
            String currentUserId,
            SyncPushRequest.MutacaoCliente mutation
    ) {
        boolean canonical = isCanonical(mutation);
        String storedHash = jdbcTemplate.queryForObject(
                canonical
                        ? """
                          SELECT envelope_hash
                          FROM sync_mutacao_cliente
                          WHERE proprietario_id = ?
                            AND client_mutation_id = ?
                            AND schema_version = 13
                          """
                        : """
                          SELECT payload_hash
                          FROM sync_mutacao_cliente
                          WHERE dispositivo_id = ?
                            AND client_mutation_id = ?
                            AND schema_version < 13
                """,
                String.class,
                canonical ? currentUserId : dispositivoId,
                mutation.clientMutationId()
        );
        String requestHash = canonical
                ? envelopeHash(mutation)
                : payloadHash(mutation);
        if (canonical) {
            return storedHash != null && storedHash.equals(requestHash);
        }
        return storedHash == null || storedHash.equals(requestHash);
    }

    private SyncPushResponse.ResultadoMutacao idempotencyMismatch(
            SyncPushRequest.MutacaoCliente mutation
    ) {
        if (isCanonical(mutation)) {
            return rejectedResult(
                    mutation,
                    "clientMutationId já foi usado com outro conteúdo ou rastro.",
                    rejectionResult(
                            "IDEMPOTENCY_MISMATCH",
                            "clientMutationId já foi usado com outro conteúdo ou rastro."
                    )
            );
        }
        return new SyncPushResponse.ResultadoMutacao(
                    mutation.clientMutationId(),
                    "ERRO",
                    mutation.entidadeTipo(),
                    mutation.entidadeId(),
                    mutation.operacao(),
                    null,
                    objectMapper.createObjectNode(),
                    objectMapper.createObjectNode(),
                    "clientMutationId já foi usado com outro conteúdo."
            );
    }

    private SyncPushResponse.ResultadoMutacao buscarResultadoMutacaoExistente(
            String dispositivoId,
            String currentUserId,
            String clientMutationId,
            boolean canonical
    ) {
        String sql = """
                SELECT
                    client_mutation_id,
                    status,
                    entidade_tipo,
                    entidade_id,
                    operacao,
                    evento_servidor_commit_seq,
                    resultado_json,
                    conflito_json,
                    erro
                FROM sync_mutacao_cliente
                WHERE %s = ?
                  AND client_mutation_id = ?
                  AND schema_version %s 13
                """.formatted(
                canonical ? "proprietario_id" : "dispositivo_id",
                canonical ? "=" : "<"
        );
        return jdbcTemplate.queryForObject(
                sql,
                (rs, rowNum) -> new SyncPushResponse.ResultadoMutacao(
                        rs.getString("client_mutation_id"),
                        rs.getString("status"),
                        rs.getString("entidade_tipo"),
                        rs.getString("entidade_id"),
                        rs.getString("operacao"),
                        rs.getObject("evento_servidor_commit_seq") == null
                                ? null
                                : rs.getLong("evento_servidor_commit_seq"),
                        parseJson(rs.getString("resultado_json")),
                        parseJson(rs.getString("conflito_json")),
                        rs.getString("erro")
                ),
                canonical ? currentUserId : dispositivoId,
                clientMutationId
        );
    }

    private SyncDeviceResponse buscarDispositivo(String id) {
        return jdbcTemplate.queryForObject(
                """
                SELECT
                    d.id,
                    d.nome,
                    d.tipo,
                    d.usuario_id,
                    d.ativo,
                    COALESCE(e.ultimo_evento_recebido_commit_seq, 0) AS ultimo_evento_recebido_commit_seq
                FROM sync_dispositivo d
                LEFT JOIN sync_estado_dispositivo e
                    ON e.dispositivo_id = d.id
                WHERE d.id = ?
                """,
                (rs, rowNum) -> new SyncDeviceResponse(
                        rs.getString("id"),
                        rs.getString("nome"),
                        rs.getString("tipo"),
                        rs.getString("usuario_id"),
                        rs.getBoolean("ativo"),
                        rs.getLong("ultimo_evento_recebido_commit_seq"),
                        Instant.now()
                ),
                id
        );
    }

    private void validarDispositivoDisponivelOuDoUsuario(
            String dispositivoId,
            String currentUserId
    ) {
        if (dispositivoId == null || dispositivoId.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "dispositivoId é obrigatório."
            );
        }

        String owner = jdbcTemplate.query(
                """
                SELECT usuario_id
                FROM sync_dispositivo
                WHERE id = ?
                LIMIT 1
                """,
                rs -> rs.next() ? rs.getString("usuario_id") : null,
                dispositivoId.trim()
        );

        if (owner != null && !owner.equals(currentUserId)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Dispositivo já registrado para outro usuário."
            );
        }
    }

    private void validarDispositivoDoUsuario(
            String dispositivoId,
            String currentUserId
    ) {
        if (dispositivoId == null || dispositivoId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "dispositivoId é obrigatório.");
        }

        Integer total = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM sync_dispositivo
                WHERE id = ?
                  AND usuario_id = ?
                  AND ativo = TRUE
                """,
                Integer.class,
                dispositivoId.trim(),
                currentUserId
        );

        if (total == null || total == 0) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Dispositivo não pertence ao usuário autenticado ou está inativo."
            );
        }
    }

    private void validarMutacao(SyncPushRequest.MutacaoCliente mutacao) {
        if (mutacao == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mutação nula.");
        }

        if (!identificadorClienteSeguro(mutacao.clientMutationId())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "clientMutationId é inválido."
            );
        }

        if (mutacao.operacao() == null || mutacao.operacao().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "operacao é obrigatória.");
        }

        SyncOperationHandler handler = operationRegistry.require(
                mutacao.operacao()
        );
        if (mutacao.correlacaoId() != null
                && !mutacao.correlacaoId().isBlank()
                && !identificadorClienteSeguro(mutacao.correlacaoId())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "correlacaoId é inválido."
            );
        }
        if (mutacao.entidadeTipo() == null
                || !handler.entityType().equals(mutacao.entidadeTipo())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "entidadeTipo não corresponde à operação informada."
            );
        }
    }

    private void validarRastroCanonicoParaAplicacao(
            String dispositivoId,
            String currentUserId,
            SyncPushRequest.MutacaoCliente mutacao
    ) {
        if (!isCanonical(mutacao)) {
            return;
        }
        if (mutacao.schemaVersion() != CANONICAL_SCHEMA_VERSION) {
            throw rejection("UNSUPPORTED_SCHEMA_VERSION", "schemaVersion deve ser 13.");
        }
        requireCanonicalUuid(mutacao.clientMutationId(), "MALFORMED_CLIENT_MUTATION_ID");
        requireCanonicalUuid(mutacao.deviceId(), "MALFORMED_DEVICE_ID");
        requireCanonicalUuid(mutacao.userId(), "MALFORMED_USER_ID");
        requireCanonicalUuid(mutacao.obraId(), "MALFORMED_WORKSITE_ID");
        requireCanonicalUuid(mutacao.entityId(), "MALFORMED_ENTITY_ID");
        requireCanonicalInstant(mutacao.occurredAt());

        if (!currentUserId.equals(mutacao.userId())) {
            throw rejection("USER_MISMATCH", "userId não corresponde ao usuário autenticado.");
        }
        if (!dispositivoId.equals(mutacao.deviceId())) {
            throw rejection("DEVICE_MISMATCH", "deviceId não corresponde ao dispositivo do push.");
        }
        if (!CANONICAL_ENTITY_TYPES.contains(mutacao.entityType())) {
            throw rejection("UNSUPPORTED_ENTITY_TYPE", "entityType canônico não suportado.");
        }
        if (!mutacao.entityType().equals(mutacao.entidadeTipo())
                || !mutacao.entityId().equals(mutacao.entidadeId())) {
            throw rejection("ENTITY_ALIAS_MISMATCH", "Aliases de entidade divergem do envelope.");
        }

        String expectedOperation = CANONICAL_OPERATION_BY_TRANSPORT.get(mutacao.operacao());
        if (expectedOperation == null || !expectedOperation.equals(mutacao.operation())) {
            throw rejection(
                    "OPERATION_ALIAS_MISMATCH",
                    "operacao não corresponde à operação canônica."
            );
        }
        if (!java.util.Objects.equals(mutacao.baseVersion(), mutacao.baseVersao())) {
            throw rejection("BASE_VERSION_ALIAS_MISMATCH", "Aliases de baseVersion divergem.");
        }
        if ("CREATE".equals(mutacao.operation()) && mutacao.baseVersion() != null) {
            throw rejection("INVALID_BASE_VERSION", "CREATE exige baseVersion nula.");
        }
        if (!"CREATE".equals(mutacao.operation())
                && (mutacao.baseVersion() == null || mutacao.baseVersion() < 0)) {
            throw rejection("INVALID_BASE_VERSION", "A operação exige baseVersion não negativa.");
        }
        if (mutacao.payload() == null || !mutacao.payload().isObject()) {
            throw rejection("MALFORMED_PAYLOAD", "payload canônico deve ser um objeto JSON.");
        }

        validarTrace(mutacao);
        validarChangedFields(mutacao);
        validarEscopoAutorizado(currentUserId, mutacao);
        validarPrincipalNoEscopo(mutacao);
        validarEntidadesRelacionadas(mutacao);
    }

    private void validarTrace(SyncPushRequest.MutacaoCliente mutacao) {
        SyncPushRequest.MutationTrace trace = mutacao.trace();
        if (trace == null) {
            throw rejection("MALFORMED_TRACE", "trace é obrigatório para schemaVersion 13.");
        }
        requireCanonicalUuid(trace.actorId(), "MALFORMED_ACTOR_ID");
        requireCanonicalUuid(trace.deviceId(), "MALFORMED_TRACE_DEVICE_ID");
        requireCanonicalUuid(trace.ontologyEventId(), "MALFORMED_ONTOLOGY_EVENT_ID");
        requireCanonicalUuid(trace.correlationId(), "MALFORMED_CORRELATION_ID");
        if (trace.causationId() != null) {
            requireCanonicalUuid(trace.causationId(), "MALFORMED_CAUSATION_ID");
        }
        if (!mutacao.userId().equals(trace.actorId())) {
            throw rejection("ACTOR_MISMATCH", "actorId diverge de userId.");
        }
        if (!mutacao.deviceId().equals(trace.deviceId())) {
            throw rejection("DEVICE_TRACE_MISMATCH", "trace.deviceId diverge de deviceId.");
        }
        if (!trace.correlationId().equals(mutacao.correlacaoId())) {
            throw rejection("CORRELATION_ALIAS_MISMATCH", "correlacaoId diverge do trace.");
        }
        if (trace.payloadHash() == null
                || !trace.payloadHash().matches("[0-9a-f]{64}")
                || !trace.payloadHash().equals(canonicalPayloadHash(mutacao))) {
            throw rejection("PAYLOAD_HASH_MISMATCH", "payloadHash não corresponde ao payload.");
        }
        if (mutacao.dependsOnMutationIds() == null) {
            throw rejection("MALFORMED_DEPENDENCIES", "dependsOnMutationIds é obrigatório.");
        }
        for (String dependency : mutacao.dependsOnMutationIds()) {
            requireCanonicalUuid(dependency, "MALFORMED_DEPENDENCY_ID");
        }
    }

    private void validarChangedFields(SyncPushRequest.MutacaoCliente mutacao) {
        if (mutacao.changedFields() == null || mutacao.fieldPatch() == null
                || mutacao.fieldPatch().changed() == null
                || !mutacao.fieldPatch().changed().isObject()
                || mutacao.fieldPatch().baseValues() == null
                || !mutacao.fieldPatch().baseValues().isObject()) {
            throw rejection("MALFORMED_CHANGED_FIELDS", "changedFields e fieldPatch são obrigatórios.");
        }
        List<String> sorted = mutacao.changedFields().stream().distinct().sorted().toList();
        if (!sorted.equals(mutacao.changedFields())
                || sorted.stream().anyMatch(field -> field == null || field.isBlank())) {
            throw rejection("MALFORMED_CHANGED_FIELDS", "changedFields deve ser único e ordenado.");
        }
        Set<String> patchFields = new java.util.TreeSet<>();
        mutacao.fieldPatch().changed().fieldNames().forEachRemaining(patchFields::add);
        mutacao.fieldPatch().baseValues().fieldNames().forEachRemaining(patchFields::add);
        if (!new ArrayList<>(patchFields).equals(sorted)) {
            throw rejection("CHANGED_FIELDS_MISMATCH", "fieldPatch diverge de changedFields.");
        }
    }

    private void validarEscopoAutorizado(
            String currentUserId,
            SyncPushRequest.MutacaoCliente mutacao
    ) {
        List<String> scope = mutacao.trace().authorizationScope();
        Optional<Set<String>> allowed = currentUserService.allowedObraIds(currentUserId);
        if (allowed.isEmpty()) {
            if (scope == null || !scope.equals(List.of("ALFA:GLOBAL"))) {
                throw rejection("AUTHORIZATION_SCOPE_MISMATCH", "Escopo ALFA inválido.");
            }
            return;
        }
        if (scope == null || scope.isEmpty() || !scope.contains(mutacao.obraId())) {
            throw rejection("AUTHORIZATION_SCOPE_MISMATCH", "Escopo não contém obraId.");
        }
        for (String obraId : scope) {
            requireCanonicalUuid(obraId, "MALFORMED_AUTHORIZATION_SCOPE");
        }
        if (!allowed.get().containsAll(scope) || !allowed.get().contains(mutacao.obraId())) {
            throw rejection("WORKSITE_SCOPE", "obraId está fora do escopo autenticado.");
        }
    }

    private void validarPrincipalNoEscopo(SyncPushRequest.MutacaoCliente mutacao) {
        if ("CREATE".equals(mutacao.operation())) {
            String payloadWorksite = mutacao.payload().path("obraId").asText(null);
            if (!mutacao.obraId().equals(payloadWorksite)) {
                throw rejection("PRINCIPAL_ENTITY_SCOPE", "payload.obraId diverge do envelope.");
            }
            return;
        }
        String storedWorksite = entityWorksite(mutacao.entityType(), mutacao.entityId());
        if (!mutacao.obraId().equals(storedWorksite)) {
            throw rejection("PRINCIPAL_ENTITY_SCOPE", "Entidade principal pertence a outra obra.");
        }
    }

    private void validarEntidadesRelacionadas(SyncPushRequest.MutacaoCliente mutacao) {
        if (mutacao.relatedEntities() == null) {
            throw rejection("MALFORMED_RELATED_ENTITIES", "relatedEntities é obrigatório.");
        }
        for (SyncPushRequest.RelatedEntity related : mutacao.relatedEntities()) {
            if (related == null || !CANONICAL_ENTITY_TYPES.contains(related.tipo())) {
                throw rejection("UNSUPPORTED_RELATED_ENTITY_TYPE", "Tipo relacionado não suportado.");
            }
            requireCanonicalUuid(related.id(), "MALFORMED_RELATED_ENTITY_ID");
            String relatedWorksite = entityWorksite(related.tipo(), related.id());
            if (!mutacao.obraId().equals(relatedWorksite)) {
                throw rejection("RELATED_ENTITY_SCOPE", "Entidade relacionada pertence a outra obra.");
            }
        }
    }

    private String entityWorksite(String entityType, String entityId) {
        String sql = switch (entityType) {
            case "RDO" -> "SELECT obra_id FROM rdo WHERE id = ?";
            case "CONVERSA" -> "SELECT obra_id FROM conversa WHERE id = ?";
            case "MENSAGEM" -> """
                    SELECT c.obra_id FROM mensagem m
                    JOIN conversa c ON c.id = m.conversa_id WHERE m.id = ?
                    """;
            case "MENSAGEM_ANEXO" -> """
                    SELECT c.obra_id FROM mensagem_anexo a
                    JOIN mensagem m ON m.id = a.mensagem_id
                    JOIN conversa c ON c.id = m.conversa_id WHERE a.id = ?
                    """;
            case "SOLICITACAO_COMPRA" ->
                    "SELECT obra_id FROM finance_solicitacao_compra WHERE id = ?";
            case "COMPRA" -> "SELECT obra_id FROM finance_compra WHERE id = ?";
            default -> throw rejection(
                    "UNSUPPORTED_ENTITY_TYPE",
                    "Tipo de entidade não suportado pelo escopo canônico."
            );
        };
        try {
            String obraId = jdbcTemplate.queryForObject(sql, String.class, entityId);
            if (obraId == null || obraId.isBlank()) {
                throw rejection("ENTITY_NOT_WORKSITE_SCOPED", "Entidade não possui obra.");
            }
            return obraId;
        } catch (EmptyResultDataAccessException exception) {
            throw rejection("ENTITY_NOT_FOUND", "Entidade canônica não encontrada.");
        }
    }

    private void requireAppliedContract(
            SyncOperationHandler handler,
            AppliedSyncMutation applied
    ) {
        if (applied == null
                || !handler.entityType().equals(applied.entityType())
                || applied.entityId() == null
                || applied.entityId().isBlank()) {
            throw new IllegalStateException(
                    "Handler de sync retornou uma aplicação inválida."
            );
        }
    }

    private String exigirEntidadeId(SyncPushRequest.MutacaoCliente mutacao) {
        if (mutacao.entidadeId() == null || mutacao.entidadeId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "entidadeId é obrigatório.");
        }

        return mutacao.entidadeId();
    }

    private int normalizarLimit(Integer requestedLimit) {
        if (requestedLimit == null) {
            return DEFAULT_LIMIT;
        }

        if (requestedLimit <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit precisa ser positivo.");
        }

        return Math.min(requestedLimit, MAX_LIMIT);
    }

    private long maxCommitSeq() {
        Long max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(commit_seq), 0) FROM cortex_evento_operacional",
                Long.class
        );

        return max == null ? 0 : max;
    }

    private JsonNode parseJson(String json) {
        try {
            if (json == null || json.isBlank()) {
                return objectMapper.createObjectNode();
            }

            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("JSON inválido armazenado no sync.", exception);
        }
    }

    private String toJson(JsonNode jsonNode) {
        try {
            if (jsonNode == null || jsonNode.isNull()) {
                return "{}";
            }

            return objectMapper.writeValueAsString(jsonNode);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("JSON inválido.", exception);
        }
    }

    private String primeiroNaoVazio(String valor, String fallback) {
        if (valor != null && !valor.isBlank()) {
            return valor;
        }

        return fallback;
    }

    private String operacaoSeguraParaBanco(String operacao) {
        if (operacao != null
                && operacao.matches("[A-Z][A-Z0-9_]{0,79}")) {
            return operacao;
        }

        return "INVALIDA";
    }

    private String correlationId(SyncPushRequest.MutacaoCliente mutation) {
        return identificadorClienteSeguro(mutation.correlacaoId())
                ? mutation.correlacaoId().strip()
                : mutation.clientMutationId().strip();
    }

    private boolean identificadorClienteSeguro(String value) {
        return value != null
                && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}");
    }

    private boolean isCanonical(SyncPushRequest.MutacaoCliente mutation) {
        return mutation != null && mutation.schemaVersion() != null;
    }

    private boolean canonicalReceiptPersistable(SyncPushRequest.MutacaoCliente mutation) {
        try {
            return mutation.schemaVersion() == CANONICAL_SCHEMA_VERSION
                    && uuidCanonico(mutation.clientMutationId())
                    && uuidCanonico(mutation.deviceId())
                    && uuidCanonico(mutation.userId())
                    && uuidCanonico(mutation.obraId())
                    && uuidCanonico(mutation.entityId())
                    && mutation.trace() != null
                    && uuidCanonico(mutation.trace().ontologyEventId())
                    && mutation.fieldPatch() != null
                    && mutation.relatedEntities() != null
                    && mutation.changedFields() != null
                    && mutation.dependsOnMutationIds() != null
                    && Instant.parse(mutacaoInstant(mutation)) != null;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private String mutacaoInstant(SyncPushRequest.MutacaoCliente mutation) {
        return mutation.occurredAt() == null ? "" : mutation.occurredAt();
    }

    private void requireCanonicalUuid(String value, String category) {
        if (!uuidCanonico(value)) {
            throw rejection(category, "Identificador canônico inválido.");
        }
    }

    private boolean uuidCanonico(String value) {
        if (value == null) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void requireCanonicalInstant(String value) {
        if (value == null
                || !value.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z")) {
            throw rejection("MALFORMED_OCCURRED_AT", "occurredAt deve ser UTC ISO canônico.");
        }
        try {
            Instant.parse(value);
        } catch (RuntimeException exception) {
            throw rejection("MALFORMED_OCCURRED_AT", "occurredAt deve ser UTC ISO canônico.");
        }
    }

    private LocalDateTime canonicalOccurredAtLocal(
            SyncPushRequest.MutacaoCliente mutation
    ) {
        return LocalDateTime.ofInstant(Instant.parse(mutation.occurredAt()), ZoneOffset.UTC);
    }

    private String persistedPayloadHash(SyncPushRequest.MutacaoCliente mutation) {
        return mutation.trace() == null ? null : mutation.trace().payloadHash();
    }

    private String canonicalPayloadHash(SyncPushRequest.MutacaoCliente mutation) {
        String canonical = canonicalJson(mutation.payload());
        return HexFormat.of().formatHex(
                sha256().digest(canonical.getBytes(StandardCharsets.UTF_8))
        );
    }

    private String envelopeHash(SyncPushRequest.MutacaoCliente mutation) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", mutation.schemaVersion());
        material.put("clientMutationId", mutation.clientMutationId());
        material.put("deviceId", mutation.deviceId());
        material.put("userId", mutation.userId());
        material.put("obraId", mutation.obraId());
        material.put("entityType", mutation.entityType());
        material.put("entityId", mutation.entityId());
        material.put("operation", mutation.operation());
        material.put("baseVersion", mutation.baseVersion());
        material.put("changedFields", mutation.changedFields());
        material.put("occurredAt", mutation.occurredAt());
        material.put("payload", mutation.payload());
        material.put("entidadeTipo", mutation.entidadeTipo());
        material.put("entidadeId", mutation.entidadeId());
        material.put("operacao", mutation.operacao());
        material.put("baseVersao", mutation.baseVersao());
        material.put("trace", mutation.trace());
        material.put("fieldPatch", mutation.fieldPatch());
        material.put("relatedEntities", mutation.relatedEntities());
        material.put("dependsOnMutationIds", mutation.dependsOnMutationIds());
        String canonical = canonicalJson(objectMapper.valueToTree(material));
        return HexFormat.of().formatHex(
                sha256().digest(canonical.getBytes(StandardCharsets.UTF_8))
        );
    }

    private String canonicalJson(JsonNode value) {
        if (value == null || value.isNull()) {
            return "null";
        }
        if (value.isArray()) {
            List<String> items = new ArrayList<>();
            value.forEach(item -> items.add(canonicalJson(item)));
            return "[" + String.join(",", items) + "]";
        }
        if (value.isObject()) {
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            List<String> entries = new ArrayList<>();
            names.forEach(name -> entries.add(
                    toJson(objectMapper.getNodeFactory().textNode(name))
                            + ":" + canonicalJson(value.get(name))
            ));
            return "{" + String.join(",", entries) + "}";
        }
        if (value.isNumber()) {
            return canonicalNumber(value.decimalValue());
        }
        return toJson(value);
    }

    private String canonicalNumber(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.signum() == 0) {
            return "0";
        }
        int exponent = normalized.precision() - normalized.scale() - 1;
        if (exponent > -7 && exponent < 21) {
            return normalized.toPlainString();
        }
        String digits = normalized.unscaledValue().abs().toString();
        String fraction = digits.length() == 1
                ? digits
                : digits.charAt(0) + "." + digits.substring(1);
        String sign = normalized.signum() < 0 ? "-" : "";
        String exponentSign = exponent >= 0 ? "+" : "";
        return sign + fraction + "e" + exponentSign + exponent;
    }

    private void vincularEventoCanonico(
            long commitSeq,
            String dispositivoId,
            SyncPushRequest.MutacaoCliente mutation,
            long entityVersion
    ) {
        int updated = jdbcTemplate.update(
                """
                UPDATE cortex_evento_operacional
                SET usuario_id = ?,
                    dispositivo_id = ?,
                    correlacao_id = ?,
                    causacao_id = ?,
                    entidades_relacionadas_json = ?::jsonb,
                    estado_anterior_json = ?::jsonb,
                    estado_novo_json = ?::jsonb,
                    resultado = 'SUCESSO',
                    erro_categoria = NULL,
                    versao_entidade = ?,
                    schema_version = 13,
                    client_mutation_id = ?,
                    evento_cliente_id = ?,
                    ocorrido_em = ?
                WHERE commit_seq = ?
                """,
                mutation.userId(),
                dispositivoId,
                mutation.trace().correlationId(),
                mutation.trace().causationId(),
                toJson(objectMapper.valueToTree(mutation.relatedEntities())),
                toJson(mutation.fieldPatch().baseValues()),
                toJson(mutation.payload()),
                entityVersion,
                mutation.clientMutationId(),
                mutation.trace().ontologyEventId(),
                canonicalOccurredAtLocal(mutation),
                commitSeq
        );
        if (updated != 1) {
            throw new IllegalStateException("Evento do domínio não pôde ser vinculado ao rastro v13.");
        }
    }

    private String payloadHash(SyncPushRequest.MutacaoCliente mutation) {
        String material = primeiroNaoVazio(mutation.entidadeTipo(), "")
                + "\n" + primeiroNaoVazio(mutation.entidadeId(), "")
                + "\n" + primeiroNaoVazio(mutation.operacao(), "")
                + "\n" + (mutation.baseVersao() == null
                        ? ""
                        : mutation.baseVersao())
                + "\n" + toJson(mutation.payload());
        return HexFormat.of().formatHex(
                sha256().digest(material.getBytes(StandardCharsets.UTF_8))
        );
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponível.", exception);
        }
    }

    private record ConflitoVersao(
            String entidadeTipo,
            String entidadeId,
            long baseVersao,
            long versaoAtual
    ) {
    }

    private static class SyncBaseVersionConflictException extends RuntimeException {
        private final String entidadeTipo;
        private final String entidadeId;
        private final long baseVersao;
        private final long versaoAtual;

        private SyncBaseVersionConflictException(
                String entidadeTipo,
                String entidadeId,
                long baseVersao,
                long versaoAtual
        ) {
            super("Conflito de versão.");
            this.entidadeTipo = entidadeTipo;
            this.entidadeId = entidadeId;
            this.baseVersao = baseVersao;
            this.versaoAtual = versaoAtual;
        }
    }

    private SyncTraceRejectionException rejection(String category, String message) {
        return new SyncTraceRejectionException(category, message);
    }

    private static class SyncTraceRejectionException extends RuntimeException {
        private final String category;

        private SyncTraceRejectionException(String category, String message) {
            super(message);
            this.category = category;
        }
    }
}
