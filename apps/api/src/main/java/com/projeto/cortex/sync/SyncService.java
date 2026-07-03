package com.projeto.cortex.sync;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import com.projeto.cortex.rdos.RdoCreateRequest;
import com.projeto.cortex.rdos.RdoDraftUpdateService;
import com.projeto.cortex.rdos.RdoQueryService;
import com.projeto.cortex.rdos.RdoResponse;
import com.projeto.cortex.rdos.RdoService;
import com.projeto.cortex.rdos.RdoWorkflowService;

@Service
public class SyncService {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;
    private static final int MAX_MUTACOES_POR_PUSH = 100;

    private static final Set<String> OPERACOES_SUPORTADAS = Set.of(
            "CRIAR_RDO",
            "ATUALIZAR_RDO_RASCUNHO",
            "ENVIAR_RDO"
    );

    private static final Set<String> OPERACOES_VALIDAS_NO_BANCO = Set.of(
            "CRIAR_RDO",
            "ATUALIZAR_RDO_RASCUNHO",
            "ENVIAR_RDO",
            "CANCELAR_RDO",
            "CRIAR_OBRA",
            "ATUALIZAR_OBRA",
            "OUTRA"
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final RdoService rdoService;
    private final RdoDraftUpdateService rdoDraftUpdateService;
    private final RdoWorkflowService rdoWorkflowService;
    private final RdoQueryService rdoQueryService;
    private final CurrentUserService currentUserService;

    public SyncService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate,
            RdoService rdoService,
            RdoDraftUpdateService rdoDraftUpdateService,
            RdoWorkflowService rdoWorkflowService,
            RdoQueryService rdoQueryService,
            CurrentUserService currentUserService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.rdoService = rdoService;
        this.rdoDraftUpdateService = rdoDraftUpdateService;
        this.rdoWorkflowService = rdoWorkflowService;
        this.rdoQueryService = rdoQueryService;
        this.currentUserService = currentUserService;
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

        List<SyncPullResponse.EventoSync> eventosComExtra = jdbcTemplate.query(
                """
                SELECT
                    commit_seq,
                    id,
                    tipo_entidade,
                    entidade_id,
                    tipo_evento,
                    fonte,
                    payload_json,
                    ocorrido_em,
                    criado_em,
                    versao_entidade
                FROM cortex_evento_operacional
                WHERE commit_seq > ?
                ORDER BY commit_seq
                LIMIT ?
                """,
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
                afterCommitSeq,
                queryLimit
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

            return registrarErroEmNovaTransacao(dispositivoId, mutacao, erro);
        } catch (RuntimeException exception) {
            String erro = exception.getClass().getSimpleName() + ": " + primeiroNaoVazio(
                    exception.getMessage(),
                    "Erro inesperado ao processar mutação."
            );

            return registrarErroEmNovaTransacao(dispositivoId, mutacao, erro);
        }
    }

    private SyncPushResponse.ResultadoMutacao processarMutacaoAplicavel(
            String dispositivoId,
            SyncPushRequest.MutacaoCliente mutacao
    ) {
        inserirMutacaoPendente(dispositivoId, mutacao);
        validarBaseVersao(mutacao);

        RdoResponse response = aplicarOperacao(mutacao);
        long commitSeq = commitSeqEntidade("RDO", response.id());
        long versaoEntidade = versaoAtualEntidade(
                "RDO",
                response.id()
        );

        ObjectNode resultado = objectMapper.valueToTree(response);
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
                    conflito_json = NULL,
                    aplicada_em = CURRENT_TIMESTAMP(6)
                WHERE dispositivo_id = ?
                  AND client_mutation_id = ?
                """,
                "RDO",
                response.id(),
                commitSeq,
                toJson(resultado),
                dispositivoId,
                mutacao.clientMutationId()
        );

        return new SyncPushResponse.ResultadoMutacao(
                mutacao.clientMutationId(),
                "APLICADA",
                "RDO",
                response.id(),
                mutacao.operacao(),
                commitSeq,
                resultado,
                objectMapper.createObjectNode(),
                null
        );
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
                    null
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
            String erro
    ) {
        if (mutacao == null || mutacao.clientMutationId() == null || mutacao.clientMutationId().isBlank()) {
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
                    null
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
            Long eventoServidorCommitSeq
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO sync_mutacao_cliente (
                    id,
                    dispositivo_id,
                    client_mutation_id,
                    entidade_tipo,
                    entidade_id,
                    operacao,
                    base_versao,
                    payload_json,
                    status,
                    erro,
                    resultado_json,
                    conflito_json,
                    evento_servidor_commit_seq,
                    criada_no_cliente_em,
                    aplicada_em
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE
                    status = VALUES(status),
                    erro = VALUES(erro),
                    resultado_json = VALUES(resultado_json),
                    conflito_json = VALUES(conflito_json),
                    evento_servidor_commit_seq = VALUES(evento_servidor_commit_seq),
                    aplicada_em = CURRENT_TIMESTAMP(6)
                """,
                UUID.randomUUID().toString(),
                dispositivoId,
                mutacao.clientMutationId(),
                primeiroNaoVazio(mutacao.entidadeTipo(), "RDO"),
                mutacao.entidadeId(),
                operacaoSeguraParaBanco(mutacao.operacao()),
                mutacao.baseVersao(),
                toJson(mutacao.payload()),
                status,
                erro,
                toJson(resultado),
                toJson(conflito),
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
                    client_mutation_id,
                    entidade_tipo,
                    entidade_id,
                    operacao,
                    base_versao,
                    payload_json,
                    status,
                    criada_no_cliente_em
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDENTE', ?)
                """,
                UUID.randomUUID().toString(),
                dispositivoId,
                mutacao.clientMutationId(),
                primeiroNaoVazio(mutacao.entidadeTipo(), "RDO"),
                mutacao.entidadeId(),
                operacaoSeguraParaBanco(mutacao.operacao()),
                mutacao.baseVersao(),
                toJson(mutacao.payload()),
                mutacao.criadaNoClienteEm()
        );
    }

    private RdoResponse aplicarOperacao(SyncPushRequest.MutacaoCliente mutacao) {
        return switch (mutacao.operacao()) {
            case "CRIAR_RDO" -> {
                RdoCreateRequest request = toValue(mutacao.payload(), RdoCreateRequest.class);
                currentUserService.requireWorksiteAccess(request.obraId());
                if (request.id() != null && !request.id().isBlank() && rdoExiste(request.id())) {
                    currentUserService.requireRdoAccess(request.id());
                    yield rdoQueryService.buscarPorId(request.id());
                }
                yield rdoService.criarRascunho(request);
            }
            case "ATUALIZAR_RDO_RASCUNHO" -> {
                String entidadeId = exigirEntidadeId(mutacao);
                currentUserService.requireRdoAccess(entidadeId);
                RdoCreateRequest request = toValue(mutacao.payload(), RdoCreateRequest.class);
                currentUserService.requireWorksiteAccess(request.obraId());
                yield rdoDraftUpdateService.atualizarRascunho(entidadeId, request);
            }
            case "ENVIAR_RDO" -> {
                String entidadeId = exigirEntidadeId(mutacao);
                currentUserService.requireRdoAccess(entidadeId);
                yield rdoWorkflowService.enviar(entidadeId);
            }
            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Operação não suportada: " + mutacao.operacao()
            );
        };
    }

    private void validarBaseVersao(SyncPushRequest.MutacaoCliente mutacao) {
        if ("CRIAR_RDO".equals(mutacao.operacao())) {
            return;
        }

        String entidadeId = exigirEntidadeId(mutacao);

        if (mutacao.baseVersao() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "baseVersao é obrigatória para operação em entidade existente."
            );
        }

        long versaoAtual = versaoAtualEntidade(mutacao.entidadeTipo(), entidadeId);

        if (versaoAtual != mutacao.baseVersao()) {
            throw new SyncBaseVersionConflictException(
                    mutacao.entidadeTipo(),
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

    private boolean rdoExiste(String rdoId) {
        Integer total = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM rdo
                WHERE id = ?
                """,
                Integer.class,
                rdoId
        );

        return total != null && total > 0;
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

        if (mutacao.clientMutationId() == null || mutacao.clientMutationId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "clientMutationId é obrigatório.");
        }

        if (mutacao.operacao() == null || mutacao.operacao().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "operacao é obrigatória.");
        }

        if (!OPERACOES_SUPORTADAS.contains(mutacao.operacao())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Operação não suportada pelo sync push: " + mutacao.operacao()
            );
        }

        if (!"CRIAR_RDO".equals(mutacao.operacao())) {
            if (mutacao.entidadeTipo() == null || mutacao.entidadeTipo().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "entidadeTipo é obrigatório.");
            }

            if (!"RDO".equals(mutacao.entidadeTipo())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Apenas entidadeTipo RDO é suportado agora.");
            }
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

    private <T> T toValue(JsonNode jsonNode, Class<T> type) {
        try {
            if (jsonNode == null || jsonNode.isNull()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "payload é obrigatório.");
            }

            return objectMapper.treeToValue(jsonNode, type);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "payload inválido para " + type.getSimpleName());
        }
    }

    private String primeiroNaoVazio(String valor, String fallback) {
        if (valor != null && !valor.isBlank()) {
            return valor;
        }

        return fallback;
    }

    private String operacaoSeguraParaBanco(String operacao) {
        if (operacao != null && OPERACOES_VALIDAS_NO_BANCO.contains(operacao)) {
            return operacao;
        }

        return "OUTRA";
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
