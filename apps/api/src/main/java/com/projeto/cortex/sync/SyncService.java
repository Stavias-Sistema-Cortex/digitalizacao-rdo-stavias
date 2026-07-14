package com.projeto.cortex.sync;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
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
            "PEDIDO_COMPRA",
            "REGRA_APROVACAO",
            "DECISAO_APROVACAO",
            "NOTA_FISCAL",
            "LANCAMENTO_FINANCEIRO",
            "PAGAMENTO",
            "COBRANCA_EMAIL"
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
                null
        );
    }

    FiltroPull filtroPorEscopo(
            Optional<Set<String>> obrasAutorizadas,
            Set<String> obrasFinanceirasAutorizadas,
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
                ) VALUES (?, ?, ?, ?, 1)
                ON DUPLICATE KEY UPDATE
                    nome = VALUES(nome),
                    tipo = VALUES(tipo),
                    usuario_id = VALUES(usuario_id),
                    ativo = 1,
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
                ON DUPLICATE KEY UPDATE
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
                ON DUPLICATE KEY UPDATE
                    ultimo_evento_recebido_commit_seq = GREATEST(
                        ultimo_evento_recebido_commit_seq,
                        VALUES(ultimo_evento_recebido_commit_seq)
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
            resultados.add(processarMutacaoComSeguranca(request.dispositivoId(), mutacao));
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
            SyncPushRequest.MutacaoCliente mutacao
    ) {
        try {
            validarMutacao(mutacao);

            SyncPushResponse.ResultadoMutacao existente = buscarResultadoMutacaoExistenteOuNull(
                    dispositivoId,
                    mutacao.clientMutationId()
            );

            if (existente != null) {
                if (!payloadHashMatchesExisting(dispositivoId, mutacao)) {
                    return idempotencyMismatch(mutacao);
                }
                if ("ERRO".equals(existente.status())) {
                    return transactionTemplate.execute(
                            status -> reprocessarMutacaoComErro(dispositivoId, mutacao)
                    );
                }
                return existente;
            }

            return transactionTemplate.execute(status -> processarMutacaoAplicavel(dispositivoId, mutacao));
        } catch (DuplicateKeyException exception) {
            return buscarResultadoMutacaoExistente(dispositivoId, mutacao.clientMutationId());
        } catch (SyncBaseVersionConflictException exception) {
            return registrarConflitoEmNovaTransacao(dispositivoId, mutacao, exception);
        } catch (ResponseStatusException exception) {
            String erro = exception.getReason() == null
                    ? "Mutação rejeitada pelo backend."
                    : exception.getReason();

            return registrarErroEmNovaTransacao(
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
                    resultado_json = ?,
                    erro = NULL,
                    erro_categoria = NULL,
                    conflito_json = NULL,
                    aplicada_em = CURRENT_TIMESTAMP(6)
                WHERE dispositivo_id = ?
                  AND client_mutation_id = ?
                """,
                applied.entityType(),
                applied.entityId(),
                commitSeq,
                toJson(resultado),
                dispositivoId,
                mutacao.clientMutationId()
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
                    payload_json = ?,
                    payload_hash = ?,
                    correlacao_id = ?,
                    status = 'PENDENTE',
                    erro = NULL,
                    erro_categoria = NULL,
                    resultado_json = NULL,
                    conflito_json = NULL,
                    evento_servidor_commit_seq = NULL,
                    recebida_em = CURRENT_TIMESTAMP(6),
                    aplicada_em = NULL
                WHERE dispositivo_id = ?
                  AND client_mutation_id = ?
                  AND status = 'ERRO'
                """,
                primeiroNaoVazio(mutacao.entidadeTipo(), "RDO"),
                mutacao.entidadeId(),
                operacaoSeguraParaBanco(mutacao.operacao()),
                mutacao.baseVersao(),
                toJson(mutacao.payload()),
                payloadHash(mutacao),
                correlationId(mutacao),
                dispositivoId,
                mutacao.clientMutationId()
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
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    CURRENT_TIMESTAMP(6)
                )
                ON DUPLICATE KEY UPDATE
                    status = VALUES(status),
                    erro = VALUES(erro),
                    erro_categoria = VALUES(erro_categoria),
                    resultado_json = VALUES(resultado_json),
                    conflito_json = VALUES(conflito_json),
                    evento_servidor_commit_seq = VALUES(evento_servidor_commit_seq),
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
                    ?, ?, ?, ?, ?, ?, ?, ?, 'PENDENTE', ?
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
            String clientMutationId
    ) {
        try {
            return buscarResultadoMutacaoExistente(dispositivoId, clientMutationId);
        } catch (EmptyResultDataAccessException exception) {
            return null;
        }
    }

    private boolean payloadHashMatchesExisting(
            String dispositivoId,
            SyncPushRequest.MutacaoCliente mutation
    ) {
        String storedHash = jdbcTemplate.queryForObject(
                """
                SELECT payload_hash
                FROM sync_mutacao_cliente
                WHERE dispositivo_id = ?
                  AND client_mutation_id = ?
                """,
                String.class,
                dispositivoId,
                mutation.clientMutationId()
        );
        return storedHash == null || storedHash.equals(payloadHash(mutation));
    }

    private SyncPushResponse.ResultadoMutacao idempotencyMismatch(
            SyncPushRequest.MutacaoCliente mutation
    ) {
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
            String clientMutationId
    ) {
        return jdbcTemplate.queryForObject(
                """
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
                WHERE dispositivo_id = ?
                  AND client_mutation_id = ?
                """,
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
                dispositivoId,
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
                  AND ativo = 1
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
}
