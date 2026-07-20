package com.projeto.cortex.equipes;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.obras.VinculoColaboradorObraService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class EquipeService {

    private static final int DEFAULT_PAGE_SIZE = 25;
    private static final int MAX_PAGE_SIZE = 100;

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserService currentUserService;
    private final EquipeMemoryPublisher memoryPublisher;
    private final VinculoColaboradorObraService vinculoService;

    public EquipeService(
            JdbcTemplate jdbcTemplate,
            CurrentUserService currentUserService,
            EquipeMemoryPublisher memoryPublisher,
            VinculoColaboradorObraService vinculoService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUserService = currentUserService;
        this.memoryPublisher = memoryPublisher;
        this.vinculoService = vinculoService;
    }

    public EquipePageResponse listar(EquipeFilter requestedFilter) {
        EquipeFilter filter = requestedFilter == null
                ? new EquipeFilter(null, null, null, null, null, 0, DEFAULT_PAGE_SIZE)
                : requestedFilter;
        int page = normalizePage(filter.page());
        int size = normalizeSize(filter.size());

        String currentUserId = currentUserService.requireUserId();
        Optional<Set<String>> allowed = currentUserService.allowedObraIds(currentUserId);
        if (allowed.isPresent() && allowed.get().isEmpty()) {
            return EquipePageResponse.empty(page, size);
        }

        String requestedWorksite = trimToNull(filter.obraId());
        if (requestedWorksite != null) {
            currentUserService.requireWorksiteAccess(requestedWorksite);
        }

        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> parameters = new ArrayList<>();

        if (allowed.isPresent()) {
            where.append(" AND EXISTS (SELECT 1 FROM equipe_obra scope_eo ")
                    .append("WHERE scope_eo.equipe_id = e.id AND scope_eo.status = 'ATIVO' ")
                    .append("AND scope_eo.obra_id IN (")
                    .append(placeholders(allowed.get().size()))
                    .append("))");
            parameters.addAll(allowed.get());
        }
        if (requestedWorksite != null) {
            where.append(" AND EXISTS (SELECT 1 FROM equipe_obra filter_eo ")
                    .append("WHERE filter_eo.equipe_id = e.id AND filter_eo.obra_id = ? ")
                    .append("AND filter_eo.status = 'ATIVO')");
            parameters.add(requestedWorksite);
        }

        String text = trimToNull(filter.texto());
        if (text != null) {
            where.append(" AND (LOWER(e.nome) LIKE ? OR LOWER(COALESCE(e.descricao, '')) LIKE ?)");
            String pattern = "%" + text.toLowerCase() + "%";
            parameters.add(pattern);
            parameters.add(pattern);
        }
        String status = trimToNull(filter.status());
        if (status != null) {
            where.append(" AND e.status = ?");
            parameters.add(normalizeTeamStatus(status).name());
        }
        String functionId = trimToNull(filter.funcaoOperacionalId());
        if (functionId != null) {
            where.append(" AND EXISTS (SELECT 1 FROM equipe_membro filter_em ")
                    .append("WHERE filter_em.equipe_id = e.id AND filter_em.status = 'ATIVO' ")
                    .append("AND filter_em.funcao_operacional_id = ?)");
            parameters.add(functionId);
        }
        if (filter.vigenteEm() != null) {
            where.append(" AND e.inicio_validade_em <= ? ")
                    .append("AND (e.fim_validade_em IS NULL OR e.fim_validade_em >= ?)");
            parameters.add(filter.vigenteEm());
            parameters.add(filter.vigenteEm());
        }

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM equipe e" + where,
                Long.class,
                parameters.toArray()
        );
        long totalElements = total == null ? 0 : total;

        List<Object> pageParameters = new ArrayList<>(parameters);
        pageParameters.add(size);
        pageParameters.add(page * size);
        List<EquipeResponse> items = jdbcTemplate.query(
                teamSelect() + where + " ORDER BY e.nome, e.id LIMIT ? OFFSET ?",
                teamMapper(),
                pageParameters.toArray()
        );

        int totalPages = totalElements == 0
                ? 0
                : (int) Math.ceil((double) totalElements / size);
        return new EquipePageResponse(items, totalElements, page, size, totalPages);
    }

    public EquipeResponse buscarPorId(String equipeId) {
        String normalizedId = requireId(equipeId, "equipeId");
        List<EquipeResponse> teams = jdbcTemplate.query(
                teamSelect() + " WHERE e.id = ?",
                teamMapper(),
                normalizedId
        );
        if (teams.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Equipe não encontrada.");
        }

        EquipeResponse team = teams.getFirst();
        requireTeamReadAccess(normalizedId);
        return team.withMembros(listMembers(normalizedId));
    }

    public List<EquipeWorksiteResponse> listarObras(String equipeId) {
        String normalizedId = buscarPorId(equipeId).id();
        String currentUserId = currentUserService.requireUserId();
        Optional<Set<String>> allowed = currentUserService.allowedObraIds(currentUserId);
        if (allowed.isPresent() && allowed.get().isEmpty()) {
            return List.of();
        }

        StringBuilder sql = new StringBuilder(worksiteSelect())
                .append(" WHERE eo.equipe_id = ?");
        List<Object> parameters = new ArrayList<>();
        parameters.add(normalizedId);
        if (allowed.isPresent()) {
            sql.append(" AND eo.obra_id IN (")
                    .append(placeholders(allowed.get().size()))
                    .append(")");
            parameters.addAll(allowed.get());
        }
        sql.append(" ORDER BY (eo.status = 'ATIVO') DESC, eo.inicio_em, o.nome, eo.id");
        return jdbcTemplate.query(sql.toString(), worksiteMapper(), parameters.toArray());
    }

    @Transactional
    public EquipeWorksiteResponse adicionarObra(
            String equipeId,
            EquipeWorksiteRequest request
    ) {
        currentUserService.requireAlfa();
        String actorId = currentUserService.requireUserId();
        if (request == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Dados da associação com a obra são obrigatórios."
            );
        }

        EquipeResponse team = buscarPorId(equipeId);
        if (EquipeStatus.ARQUIVADA.name().equals(team.status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Não é possível associar obra a uma equipe arquivada."
            );
        }
        String worksiteId = requireId(request.obraId(), "obraId");
        currentUserService.requireWorksiteAccess(worksiteId);
        requireExistingWorksite(worksiteId);

        List<EquipeWorksiteResponse> existing = jdbcTemplate.query(
                worksiteSelect()
                        + " WHERE eo.equipe_id = ?"
                        + " AND eo.obra_id = ?"
                        + " AND eo.status = 'ATIVO'",
                worksiteMapper(),
                team.id(),
                worksiteId
        );
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }

        String linkId = request.id() == null || request.id().isBlank()
                ? UUID.randomUUID().toString()
                : request.id().trim();
        LocalDateTime start = request.inicioEm() == null
                ? LocalDateTime.now()
                : request.inicioEm();
        jdbcTemplate.update(
                """
                INSERT INTO equipe_obra (
                    id, equipe_id, obra_id, status, inicio_em, atribuido_por,
                    versao_linha
                ) VALUES (?, ?, ?, 'ATIVO', ?, ?, 1)
                """,
                linkId,
                team.id(),
                worksiteId,
                start,
                actorId
        );

        EquipeWorksiteResponse created = requireWorksiteLink(linkId);
        memoryPublisher.obraAssociada(team, created, actorId);
        return created;
    }

    @Transactional
    public EquipeWorksiteResponse encerrarObra(
            String equipeId,
            String linkId,
            Long baseVersao,
            String motivo,
            LocalDateTime encerradoEm
    ) {
        currentUserService.requireAlfa();
        String actorId = currentUserService.requireUserId();
        EquipeResponse team = buscarPorId(equipeId);
        EquipeWorksiteResponse before = requireWorksiteLink(requireId(linkId, "associacaoId"));
        if (!team.id().equals(before.equipeId())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Associação não pertence à equipe informada."
            );
        }
        if ("ENCERRADO".equals(before.status())) {
            return before;
        }
        if (team.obraPrincipalId().equals(before.obraId())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A obra principal só pode ser encerrada ao arquivar a equipe."
            );
        }

        long baseVersion = requireBaseVersion(baseVersao);
        if (before.versaoEntidade() != baseVersion) {
            throw worksiteLinkVersionConflict();
        }
        String reason = requireText(motivo, "motivo", 500);
        LocalDateTime end = encerradoEm == null ? LocalDateTime.now() : encerradoEm;
        if (end.isBefore(before.inicioEm())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "O encerramento não pode ser anterior ao início da associação."
            );
        }

        int updated = jdbcTemplate.update(
                """
                UPDATE equipe_obra
                SET status = 'ENCERRADO',
                    fim_em = ?,
                    motivo_encerramento = ?,
                    encerrado_por = ?,
                    versao_linha = versao_linha + 1
                WHERE id = ?
                  AND versao_linha = ?
                  AND status = 'ATIVO'
                """,
                end,
                reason,
                actorId,
                before.id(),
                baseVersion
        );
        if (updated != 1) {
            throw worksiteLinkVersionConflict();
        }

        EquipeWorksiteResponse after = requireWorksiteLink(before.id());
        memoryPublisher.obraDesassociada(
                team,
                before,
                after,
                actorId,
                baseVersion,
                reason
        );
        return after;
    }

    @Transactional
    public EquipeResponse atualizar(String equipeId, EquipeUpdateRequest request) {
        currentUserService.requireAlfa();
        String actorId = currentUserService.requireUserId();
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dados da equipe são obrigatórios.");
        }

        String normalizedId = requireId(equipeId, "equipeId");
        TeamIdentity identity = requireIdentity(normalizedId);
        currentUserService.requireWorksiteAccess(identity.worksiteId());
        long baseVersion = requireBaseVersion(request.baseVersao());
        if (identity.version() != baseVersion) {
            throw teamVersionConflict();
        }

        String name = requireText(request.nome(), "nome", 160);
        String description = trimToNull(request.descricao());
        EquipeStatus status = normalizeTeamStatus(request.status());
        LocalDateTime start = request.inicioValidadeEm();
        if (start == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "inicioValidadeEm é obrigatório."
            );
        }
        if (request.fimValidadeEm() != null && request.fimValidadeEm().isBefore(start)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "fimValidadeEm não pode ser anterior ao início."
            );
        }
        if (status == EquipeStatus.ARQUIVADA) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Use a operação de arquivamento para encerrar uma equipe."
            );
        }

        EquipeResponse before = buscarPorId(normalizedId);
        int updated = jdbcTemplate.update(
                """
                UPDATE equipe
                SET nome = ?,
                    descricao = ?,
                    status = ?,
                    inicio_validade_em = ?,
                    fim_validade_em = ?,
                    atualizado_por = ?,
                    versao_linha = versao_linha + 1
                WHERE id = ?
                  AND versao_linha = ?
                """,
                name,
                description,
                status.name(),
                start,
                request.fimValidadeEm(),
                actorId,
                normalizedId,
                baseVersion
        );
        if (updated != 1) {
            throw teamVersionConflict();
        }

        EquipeResponse after = buscarPorId(normalizedId);
        memoryPublisher.equipeAtualizada(
                before,
                after,
                actorId,
                baseVersion,
                trimToNull(request.motivo())
        );
        return after;
    }

    @Transactional
    public EquipeMemberResponse adicionarMembro(
            String equipeId,
            EquipeMemberRequest request
    ) {
        currentUserService.requireAlfa();
        String actorId = currentUserService.requireUserId();
        if (request == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Dados da participação são obrigatórios."
            );
        }

        EquipeResponse team = buscarPorId(equipeId);
        if (EquipeStatus.ARQUIVADA.name().equals(team.status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Não é possível adicionar membro a uma equipe arquivada."
            );
        }

        String participationId = request.id() == null || request.id().isBlank()
                ? UUID.randomUUID().toString()
                : request.id().trim();
        String collaboratorId = requireId(request.colaboradorId(), "colaboradorId");
        String functionId = requireId(request.funcaoOperacionalId(), "funcaoOperacionalId");
        LocalDateTime start = request.inicioEm() == null
                ? LocalDateTime.now()
                : request.inicioEm();
        boolean responsible = Boolean.TRUE.equals(request.responsavel());

        requireActiveFunction(functionId);
        requireActiveCollaborator(collaboratorId);

        EquipeMemberResponse existing = findActiveMember(team.id(), collaboratorId);
        if (existing != null) {
            return existing;
        }

        jdbcTemplate.update(
                """
                INSERT INTO equipe_membro (
                    id, equipe_id, colaborador_id, funcao_operacional_id,
                    responsavel, status, inicio_em, adicionado_por, atribuido_por,
                    atualizado_por,
                    versao_linha
                ) VALUES (?, ?, ?, ?, ?, 'ATIVO', ?, ?, ?, ?, 1)
                """,
                participationId,
                team.id(),
                collaboratorId,
                functionId,
                responsible,
                start,
                actorId,
                actorId,
                actorId
        );

        if (Boolean.TRUE.equals(request.concederAcessoObra())) {
            vinculoService.vincular(
                    team.obraPrincipalId(),
                    collaboratorId,
                    "OPERACIONAL",
                    actorId
            );
        }

        EquipeMemberResponse member = requireMember(participationId);
        memoryPublisher.membroAdicionado(team, member, actorId);
        return member;
    }

    @Transactional
    public EquipeMemberResponse atualizarMembro(
            String equipeId,
            String participationId,
            EquipeMemberRequest request
    ) {
        currentUserService.requireAlfa();
        String actorId = currentUserService.requireUserId();
        if (request == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Dados da participação são obrigatórios."
            );
        }

        EquipeResponse team = buscarPorId(equipeId);
        EquipeMemberResponse before = requireMember(requireId(participationId, "participacaoId"));
        if (!team.id().equals(before.equipeId())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Participação não pertence à equipe informada."
            );
        }
        if (!"ATIVO".equals(before.status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Participação encerrada não pode ser alterada."
            );
        }

        long baseVersion = requireBaseVersion(request.baseVersao());
        if (before.versaoEntidade() != baseVersion) {
            throw memberVersionConflict();
        }
        String collaboratorId = requireId(request.colaboradorId(), "colaboradorId");
        if (!before.colaboradorId().equals(collaboratorId)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "O colaborador de uma participação não pode ser substituído."
            );
        }
        String functionId = requireId(request.funcaoOperacionalId(), "funcaoOperacionalId");
        requireActiveFunction(functionId);
        LocalDateTime start = request.inicioEm() == null ? before.inicioEm() : request.inicioEm();
        boolean responsible = Boolean.TRUE.equals(request.responsavel());

        int updated = jdbcTemplate.update(
                """
                UPDATE equipe_membro
                SET funcao_operacional_id = ?,
                    responsavel = ?,
                    inicio_em = ?,
                    atualizado_por = ?,
                    versao_linha = versao_linha + 1
                WHERE id = ?
                  AND versao_linha = ?
                  AND status = 'ATIVO'
                """,
                functionId,
                responsible,
                start,
                actorId,
                before.id(),
                baseVersion
        );
        if (updated != 1) {
            throw memberVersionConflict();
        }

        EquipeMemberResponse after = requireMember(before.id());
        memoryPublisher.membroAtualizado(
                team,
                before,
                after,
                actorId,
                baseVersion,
                trimToNull(request.motivo())
        );
        return after;
    }

    @Transactional
    public EquipeMemberResponse encerrarMembro(
            String equipeId,
            String participationId,
            Long baseVersao,
            String motivo,
            LocalDateTime encerradoEm
    ) {
        currentUserService.requireAlfa();
        String actorId = currentUserService.requireUserId();
        EquipeResponse team = buscarPorId(equipeId);
        EquipeMemberResponse before = requireMember(requireId(participationId, "participacaoId"));
        if (!team.id().equals(before.equipeId())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Participação não pertence à equipe informada."
            );
        }
        if ("ENCERRADO".equals(before.status())) {
            return before;
        }

        long baseVersion = requireBaseVersion(baseVersao);
        if (before.versaoEntidade() != baseVersion) {
            throw memberVersionConflict();
        }
        String reason = requireText(motivo, "motivo", 500);
        LocalDateTime end = encerradoEm == null ? LocalDateTime.now() : encerradoEm;
        if (end.isBefore(before.inicioEm())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "O encerramento não pode ser anterior ao início da participação."
            );
        }

        int updated = jdbcTemplate.update(
                """
                UPDATE equipe_membro
                SET status = 'ENCERRADO',
                    fim_em = ?,
                    motivo_encerramento = ?,
                    removido_em = ?,
                    removido_por = ?,
                    encerrado_por = ?,
                    atualizado_por = ?,
                    versao_linha = versao_linha + 1
                WHERE id = ?
                  AND versao_linha = ?
                  AND status = 'ATIVO'
                """,
                end,
                reason,
                end,
                actorId,
                actorId,
                actorId,
                before.id(),
                baseVersion
        );
        if (updated != 1) {
            throw memberVersionConflict();
        }

        EquipeMemberResponse after = requireMember(before.id());
        memoryPublisher.membroEncerrado(
                team,
                before,
                after,
                actorId,
                baseVersion,
                reason
        );
        return after;
    }

    @Transactional
    public EquipeResponse arquivar(
            String equipeId,
            Long baseVersao,
            String motivo,
            LocalDateTime arquivadaEm
    ) {
        currentUserService.requireAlfa();
        String actorId = currentUserService.requireUserId();
        EquipeResponse before = buscarPorId(equipeId);
        if (EquipeStatus.ARQUIVADA.name().equals(before.status())) {
            return before;
        }
        List<EquipeWorksiteResponse> activeWorksiteLinks = listActiveWorksiteLinks(before.id());

        long baseVersion = requireBaseVersion(baseVersao);
        if (before.versaoEntidade() != baseVersion) {
            throw teamVersionConflict();
        }
        String reason = requireText(motivo, "motivo", 500);
        LocalDateTime archivedAt = arquivadaEm == null ? LocalDateTime.now() : arquivadaEm;
        if (archivedAt.isBefore(before.inicioValidadeEm())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "O arquivamento não pode ser anterior ao início da equipe."
            );
        }

        int updated = jdbcTemplate.update(
                """
                UPDATE equipe
                SET status = 'ARQUIVADA',
                    fim_validade_em = ?,
                    arquivada_em = ?,
                    atualizado_por = ?,
                    versao_linha = versao_linha + 1
                WHERE id = ?
                  AND versao_linha = ?
                """,
                archivedAt,
                archivedAt,
                actorId,
                before.id(),
                baseVersion
        );
        if (updated != 1) {
            throw teamVersionConflict();
        }

        jdbcTemplate.update(
                """
                UPDATE equipe_obra
                SET status = 'ENCERRADO',
                    fim_em = ?,
                    motivo_encerramento = ?,
                    encerrado_por = ?,
                    versao_linha = versao_linha + 1
                WHERE equipe_id = ?
                  AND status = 'ATIVO'
                """,
                archivedAt,
                reason,
                actorId,
                before.id()
        );
        jdbcTemplate.update(
                """
                UPDATE equipe_membro
                SET status = 'ENCERRADO',
                    fim_em = ?,
                    motivo_encerramento = ?,
                    removido_em = ?,
                    removido_por = ?,
                    encerrado_por = ?,
                    atualizado_por = ?,
                    versao_linha = versao_linha + 1
                WHERE equipe_id = ?
                  AND status = 'ATIVO'
                """,
                archivedAt,
                reason,
                archivedAt,
                actorId,
                actorId,
                actorId,
                before.id()
        );

        EquipeResponse after = buscarPorId(before.id());
        for (EquipeWorksiteResponse link : activeWorksiteLinks) {
            EquipeWorksiteResponse endedLink = requireWorksiteLink(link.id());
            memoryPublisher.obraDesassociada(
                    before,
                    link,
                    endedLink,
                    actorId,
                    link.versaoEntidade(),
                    reason
            );
        }
        for (EquipeMemberResponse member : before.membros()) {
            if (!"ATIVO".equals(member.status())) {
                continue;
            }
            EquipeMemberResponse ended = new EquipeMemberResponse(
                    member.id(),
                    member.equipeId(),
                    member.colaboradorId(),
                    member.colaboradorNome(),
                    member.papelAcesso(),
                    member.funcaoOperacionalId(),
                    member.funcaoCodigo(),
                    member.funcaoNome(),
                    member.responsavel(),
                    "ENCERRADO",
                    member.inicioEm(),
                    archivedAt,
                    reason,
                    member.versaoEntidade() + 1,
                    member.criadoEm(),
                    archivedAt
            );
            memoryPublisher.membroEncerrado(
                    before,
                    member,
                    ended,
                    actorId,
                    member.versaoEntidade(),
                    reason
            );
        }
        memoryPublisher.equipeArquivada(
                before,
                after,
                actorId,
                baseVersion,
                reason
        );
        return after;
    }

    public List<FuncaoOperacionalResponse> listarFuncoes(boolean incluirInativas) {
        currentUserService.requireUserId();
        String condition = incluirInativas ? "" : " WHERE ativo = 1";
        return jdbcTemplate.query(
                functionSelect() + condition + " ORDER BY ordem_exibicao, nome, id",
                functionMapper()
        );
    }

    @Transactional
    public FuncaoOperacionalResponse criarFuncao(FuncaoOperacionalRequest request) {
        currentUserService.requireAlfa();
        String actorId = currentUserService.requireUserId();
        if (request == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Dados da função operacional são obrigatórios."
            );
        }

        String id = request.id() == null || request.id().isBlank()
                ? UUID.randomUUID().toString()
                : request.id().trim();
        String code = normalizeFunctionCode(request.codigo());
        String name = requireText(request.nome(), "nome", 160);
        String description = trimToNull(request.descricao());
        boolean active = request.ativo() == null || request.ativo();
        int order = request.ordemExibicao() == null ? 0 : request.ordemExibicao();
        if (order < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "ordemExibicao não pode ser negativa."
            );
        }

        List<FuncaoOperacionalResponse> existing = jdbcTemplate.query(
                functionSelect() + " WHERE id = ? OR codigo = ?",
                functionMapper(),
                id,
                code
        );
        if (!existing.isEmpty()) {
            FuncaoOperacionalResponse current = existing.getFirst();
            if (current.id().equals(id) && current.codigo().equals(code)) {
                return current;
            }
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Identificador ou código de função operacional já utilizado."
            );
        }

        jdbcTemplate.update(
                """
                INSERT INTO funcao_operacional (
                    id, codigo, nome, descricao, ativo, ordem_exibicao,
                    criado_por, atualizado_por, versao_linha
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)
                """,
                id,
                code,
                name,
                description,
                active,
                order,
                actorId,
                actorId
        );

        FuncaoOperacionalResponse created = requireFunction(id);
        memoryPublisher.funcaoCriada(created, actorId);
        return created;
    }

    @Transactional
    public FuncaoOperacionalResponse atualizarFuncao(
            String functionId,
            FuncaoOperacionalRequest request
    ) {
        currentUserService.requireAlfa();
        String actorId = currentUserService.requireUserId();
        if (request == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Dados da função operacional são obrigatórios."
            );
        }

        FuncaoOperacionalResponse before = requireFunction(requireId(functionId, "funcaoId"));
        long baseVersion = requireBaseVersion(request.baseVersao());
        if (before.versaoEntidade() != baseVersion) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Conflito de versão da função operacional."
            );
        }
        String code = normalizeFunctionCode(request.codigo());
        String name = requireText(request.nome(), "nome", 160);
        int order = request.ordemExibicao() == null ? before.ordemExibicao() : request.ordemExibicao();
        if (order < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "ordemExibicao não pode ser negativa."
            );
        }

        int updated = jdbcTemplate.update(
                """
                UPDATE funcao_operacional
                SET codigo = ?, nome = ?, descricao = ?, ativo = ?,
                    ordem_exibicao = ?, atualizado_por = ?,
                    versao_linha = versao_linha + 1
                WHERE id = ? AND versao_linha = ?
                """,
                code,
                name,
                trimToNull(request.descricao()),
                request.ativo() == null ? before.ativo() : request.ativo(),
                order,
                actorId,
                before.id(),
                baseVersion
        );
        if (updated != 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Conflito de versão da função operacional."
            );
        }

        FuncaoOperacionalResponse after = requireFunction(before.id());
        memoryPublisher.funcaoAtualizada(before, after, actorId, baseVersion);
        return after;
    }

    @Transactional
    public EquipeResponse criar(EquipeCreateRequest request) {
        currentUserService.requireAlfa();
        String actorId = currentUserService.requireUserId();
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dados da equipe são obrigatórios.");
        }

        String id = request.id() == null || request.id().isBlank()
                ? UUID.randomUUID().toString()
                : request.id().trim();
        String worksiteId = requireId(request.obraId(), "obraId");
        String name = requireText(request.nome(), "nome", 160);
        String description = trimToNull(request.descricao());
        LocalDateTime start = request.inicioValidadeEm() == null
                ? LocalDateTime.now()
                : request.inicioValidadeEm();

        currentUserService.requireWorksiteAccess(worksiteId);
        requireExistingWorksite(worksiteId);

        TeamIdentity existing = findIdentity(id);
        if (existing != null) {
            if (!existing.worksiteId().equals(worksiteId) || !existing.name().equals(name)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "O identificador da equipe já existe com outros dados."
                );
            }
            return buscarPorId(id);
        }

        jdbcTemplate.update(
                """
                INSERT INTO equipe (
                    id, obra_id, obra_principal_id, nome, descricao, status,
                    inicio_validade_em, criado_por, atualizado_por, versao_linha
                ) VALUES (?, ?, ?, ?, ?, 'ATIVA', ?, ?, ?, 1)
                """,
                id,
                worksiteId,
                worksiteId,
                name,
                description,
                start,
                actorId,
                actorId
        );
        jdbcTemplate.update(
                """
                INSERT INTO equipe_obra (
                    id, equipe_id, obra_id, status, inicio_em, atribuido_por,
                    versao_linha
                ) VALUES (?, ?, ?, 'ATIVO', ?, ?, 1)
                """,
                UUID.randomUUID().toString(),
                id,
                worksiteId,
                start,
                actorId
        );

        EquipeResponse created = buscarPorId(id);
        memoryPublisher.equipeCriada(created, actorId);
        return created;
    }

    private TeamIdentity findIdentity(String id) {
        List<TeamIdentity> rows = jdbcTemplate.query(
                "SELECT id, obra_principal_id, nome, status, versao_linha FROM equipe WHERE id = ?",
                (rs, rowNum) -> new TeamIdentity(
                        rs.getString("id"),
                        rs.getString("obra_principal_id"),
                        rs.getString("nome"),
                        rs.getString("status"),
                        rs.getLong("versao_linha")
                ),
                id
        );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private List<EquipeMemberResponse> listMembers(String teamId) {
        return jdbcTemplate.query(
                memberSelect()
                        + " WHERE em.equipe_id = ?"
                        + " ORDER BY (em.status = 'ATIVO') DESC, em.inicio_em, c.nome, em.id",
                memberMapper(),
                teamId
        );
    }

    private EquipeMemberResponse findActiveMember(String teamId, String collaboratorId) {
        List<EquipeMemberResponse> rows = jdbcTemplate.query(
                memberSelect()
                        + " WHERE em.equipe_id = ?"
                        + " AND em.colaborador_id = ?"
                        + " AND em.status = 'ATIVO'",
                memberMapper(),
                teamId,
                collaboratorId
        );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private EquipeMemberResponse requireMember(String participationId) {
        List<EquipeMemberResponse> rows = jdbcTemplate.query(
                memberSelect() + " WHERE em.id = ?",
                memberMapper(),
                participationId
        );
        if (rows.isEmpty()) {
            throw new IllegalStateException("Participação criada não pôde ser recarregada.");
        }
        return rows.getFirst();
    }

    private EquipeWorksiteResponse requireWorksiteLink(String linkId) {
        List<EquipeWorksiteResponse> rows = jdbcTemplate.query(
                worksiteSelect() + " WHERE eo.id = ?",
                worksiteMapper(),
                linkId
        );
        if (rows.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Associação entre equipe e obra não encontrada."
            );
        }
        return rows.getFirst();
    }

    private List<EquipeWorksiteResponse> listActiveWorksiteLinks(String teamId) {
        return jdbcTemplate.query(
                worksiteSelect()
                        + " WHERE eo.equipe_id = ?"
                        + " AND eo.status = 'ATIVO'"
                        + " ORDER BY eo.inicio_em, eo.id",
                worksiteMapper(),
                teamId
        );
    }

    private String worksiteSelect() {
        return """
                SELECT
                    eo.id, eo.equipe_id, eo.obra_id, o.nome AS obra_nome,
                    eo.status, eo.inicio_em, eo.fim_em, eo.motivo_encerramento,
                    eo.versao_linha, eo.criado_em, eo.atualizado_em
                FROM equipe_obra eo
                JOIN obra o ON o.id = eo.obra_id
                """;
    }

    private RowMapper<EquipeWorksiteResponse> worksiteMapper() {
        return (rs, rowNum) -> new EquipeWorksiteResponse(
                rs.getString("id"),
                rs.getString("equipe_id"),
                rs.getString("obra_id"),
                rs.getString("obra_nome"),
                rs.getString("status"),
                toLocalDateTime(rs.getTimestamp("inicio_em")),
                toLocalDateTime(rs.getTimestamp("fim_em")),
                rs.getString("motivo_encerramento"),
                rs.getLong("versao_linha"),
                toLocalDateTime(rs.getTimestamp("criado_em")),
                toLocalDateTime(rs.getTimestamp("atualizado_em"))
        );
    }

    private String memberSelect() {
        return """
                SELECT
                    em.id, em.equipe_id, em.colaborador_id,
                    c.nome AS colaborador_nome,
                    c.papel_acesso AS colaborador_papel_acesso,
                    em.funcao_operacional_id,
                    f.codigo AS funcao_codigo,
                    f.nome AS funcao_nome,
                    em.responsavel, em.status, em.inicio_em, em.fim_em,
                    em.motivo_encerramento, em.versao_linha,
                    em.criado_em, em.atualizado_em
                FROM equipe_membro em
                JOIN colaborador c ON c.id = em.colaborador_id
                JOIN funcao_operacional f ON f.id = em.funcao_operacional_id
                """;
    }

    private RowMapper<EquipeMemberResponse> memberMapper() {
        return (rs, rowNum) -> new EquipeMemberResponse(
                rs.getString("id"),
                rs.getString("equipe_id"),
                rs.getString("colaborador_id"),
                rs.getString("colaborador_nome"),
                rs.getString("colaborador_papel_acesso"),
                rs.getString("funcao_operacional_id"),
                rs.getString("funcao_codigo"),
                rs.getString("funcao_nome"),
                rs.getBoolean("responsavel"),
                rs.getString("status"),
                toLocalDateTime(rs.getTimestamp("inicio_em")),
                toLocalDateTime(rs.getTimestamp("fim_em")),
                rs.getString("motivo_encerramento"),
                rs.getLong("versao_linha"),
                toLocalDateTime(rs.getTimestamp("criado_em")),
                toLocalDateTime(rs.getTimestamp("atualizado_em"))
        );
    }

    private String functionSelect() {
        return """
                SELECT id, codigo, nome, descricao, ativo, ordem_exibicao,
                       versao_linha, criado_em, atualizado_em
                FROM funcao_operacional
                """;
    }

    private RowMapper<FuncaoOperacionalResponse> functionMapper() {
        return (rs, rowNum) -> new FuncaoOperacionalResponse(
                rs.getString("id"),
                rs.getString("codigo"),
                rs.getString("nome"),
                rs.getString("descricao"),
                rs.getBoolean("ativo"),
                rs.getInt("ordem_exibicao"),
                rs.getLong("versao_linha"),
                toLocalDateTime(rs.getTimestamp("criado_em")),
                toLocalDateTime(rs.getTimestamp("atualizado_em"))
        );
    }

    private FuncaoOperacionalResponse requireFunction(String functionId) {
        List<FuncaoOperacionalResponse> rows = jdbcTemplate.query(
                functionSelect() + " WHERE id = ?",
                functionMapper(),
                functionId
        );
        if (rows.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Função operacional não encontrada."
            );
        }
        return rows.getFirst();
    }

    private String teamSelect() {
        return """
                SELECT
                    e.id, e.obra_principal_id, o.nome AS obra_nome,
                    e.nome, e.descricao, e.status,
                    e.inicio_validade_em, e.fim_validade_em,
                    e.versao_linha, e.criado_em, e.atualizado_em
                FROM equipe e
                JOIN obra o ON o.id = e.obra_principal_id
                """;
    }

    private RowMapper<EquipeResponse> teamMapper() {
        return (rs, rowNum) -> new EquipeResponse(
                rs.getString("id"),
                rs.getString("obra_principal_id"),
                rs.getString("obra_nome"),
                rs.getString("nome"),
                rs.getString("descricao"),
                rs.getString("status"),
                toLocalDateTime(rs.getTimestamp("inicio_validade_em")),
                toLocalDateTime(rs.getTimestamp("fim_validade_em")),
                rs.getLong("versao_linha"),
                toLocalDateTime(rs.getTimestamp("criado_em")),
                toLocalDateTime(rs.getTimestamp("atualizado_em")),
                List.of()
        );
    }

    private void requireExistingWorksite(String worksiteId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM obra WHERE id = ? AND arquivado_em IS NULL",
                Integer.class,
                worksiteId
        );
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Obra não encontrada ou arquivada.");
        }
    }

    private void requireTeamReadAccess(String teamId) {
        String currentUserId = currentUserService.requireUserId();
        Optional<Set<String>> allowed = currentUserService.allowedObraIds(currentUserId);
        if (allowed.isEmpty()) {
            return;
        }
        if (allowed.get().isEmpty()) {
            throw teamAccessDenied();
        }

        List<Object> parameters = new ArrayList<>();
        parameters.add(teamId);
        parameters.addAll(allowed.get());
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM equipe_obra
                WHERE equipe_id = ?
                  AND status = 'ATIVO'
                  AND obra_id IN (%s)
                """.formatted(placeholders(allowed.get().size())),
                Integer.class,
                parameters.toArray()
        );
        if (count == null || count == 0) {
            throw teamAccessDenied();
        }
    }

    private ResponseStatusException teamAccessDenied() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso negado à equipe.");
    }

    private TeamIdentity requireIdentity(String teamId) {
        TeamIdentity identity = findIdentity(teamId);
        if (identity == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Equipe não encontrada.");
        }
        return identity;
    }

    private void requireActiveFunction(String functionId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM funcao_operacional WHERE id = ? AND ativo = 1",
                Integer.class,
                functionId
        );
        if (count == null || count == 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Função operacional não encontrada ou inativa."
            );
        }
    }

    private void requireActiveCollaborator(String collaboratorId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM colaborador
                WHERE id = ? AND ativo = 1 AND deletado_em IS NULL
                """,
                Integer.class,
                collaboratorId
        );
        if (count == null || count == 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Colaborador não encontrado ou inativo."
            );
        }
    }

    private long requireBaseVersion(Long value) {
        if (value == null || value < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "baseVersao é obrigatória e não pode ser negativa."
            );
        }
        return value;
    }

    private ResponseStatusException teamVersionConflict() {
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Conflito de versão da equipe."
        );
    }

    private ResponseStatusException memberVersionConflict() {
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Conflito de versão da participação na equipe."
        );
    }

    private ResponseStatusException worksiteLinkVersionConflict() {
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Conflito de versão da associação entre equipe e obra."
        );
    }

    private int normalizePage(Integer page) {
        if (page == null) {
            return 0;
        }
        if (page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page não pode ser negativo.");
        }
        return page;
    }

    private int normalizeSize(Integer size) {
        if (size == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (size <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "size precisa ser positivo.");
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private EquipeStatus normalizeTeamStatus(String value) {
        try {
            return EquipeStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status de equipe inválido.");
        }
    }

    private String requireId(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " é obrigatório.");
        }
        return value.trim();
    }

    private String requireText(String value, String field, int maxLength) {
        String normalized = requireId(value, field);
        if (normalized.length() > maxLength) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    field + " excede o limite de " + maxLength + " caracteres."
            );
        }
        return normalized;
    }

    private String normalizeFunctionCode(String value) {
        String normalized = requireText(value, "codigo", 80)
                .toUpperCase()
                .replace('-', '_')
                .replace(' ', '_');
        if (!normalized.matches("[A-Z0-9_]+")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "codigo deve conter apenas letras, números e sublinhado."
            );
        }
        return normalized;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    record TeamIdentity(String id, String worksiteId, String name, String status, long version) {
    }
}
