package com.projeto.cortex.colaboradores;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.auth.PapelAcesso;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class PapelAcessoService {

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserService currentUserService;
    private final PapelAcessoMemoryPublisher memoryPublisher;

    public PapelAcessoService(
            JdbcTemplate jdbcTemplate,
            CurrentUserService currentUserService,
            PapelAcessoMemoryPublisher memoryPublisher
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUserService = currentUserService;
        this.memoryPublisher = memoryPublisher;
    }

    @Transactional
    public PapelAcessoUpdateResponse alterar(
            String colaboradorId,
            PapelAcessoUpdateRequest request
    ) {
        currentUserService.requireAlfa();
        if (request == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Dados da mudança de acesso são obrigatórios."
            );
        }
        if (!Boolean.TRUE.equals(request.confirmacaoExplicita())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A mudança de acesso exige confirmação explícita."
            );
        }

        String actorId = currentUserService.requireUserId();
        String targetId = requireText(colaboradorId, "colaboradorId", 36);
        PapelAcesso requestedCurrent = requireRole(request.papelAtual(), "papelAtual");
        PapelAcesso requestedNew = requireRole(request.papelNovo(), "papelNovo");
        long baseVersion = requireVersion(request.baseVersao());
        String reason = requireText(request.motivo(), "motivo", 500);

        PapelAcessoUpdateResponse before = requireCollaborator(targetId);
        PapelAcesso persistedCurrent = requireRole(before.papelAcesso(), "papelAcesso persistido");
        if (persistedCurrent != requestedCurrent || before.versaoLinha() != baseVersion) {
            throw versionConflict();
        }
        if (actorId.equals(targetId)
                && persistedCurrent == PapelAcesso.ALFA
                && requestedNew == PapelAcesso.BETA) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Um usuário Alfa não pode remover o próprio acesso administrativo."
            );
        }
        if (persistedCurrent == requestedNew) {
            return before;
        }

        int updated = jdbcTemplate.update(
                """
                UPDATE colaborador
                SET papel_acesso = ?,
                    versao_linha = versao_linha + 1
                WHERE id = ?
                  AND papel_acesso = ?
                  AND versao_linha = ?
                  AND ativo = 1
                  AND deletado_em IS NULL
                """,
                requestedNew.name(),
                targetId,
                requestedCurrent.name(),
                baseVersion
        );
        if (updated != 1) {
            throw versionConflict();
        }

        PapelAcessoUpdateResponse after = requireCollaborator(targetId);
        memoryPublisher.papelAlterado(
                before,
                after,
                actorId,
                baseVersion,
                reason
        );
        return after;
    }

    private PapelAcessoUpdateResponse requireCollaborator(String collaboratorId) {
        List<PapelAcessoUpdateResponse> rows = jdbcTemplate.query(
                """
                SELECT id, nome, papel_acesso, versao_linha, atualizado_em
                FROM colaborador
                WHERE id = ?
                  AND ativo = 1
                  AND deletado_em IS NULL
                """,
                roleMapper(),
                collaboratorId
        );
        if (rows.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Colaborador não encontrado ou inativo."
            );
        }
        return rows.getFirst();
    }

    private RowMapper<PapelAcessoUpdateResponse> roleMapper() {
        return (rs, rowNum) -> new PapelAcessoUpdateResponse(
                rs.getString("id"),
                rs.getString("nome"),
                rs.getString("papel_acesso"),
                rs.getLong("versao_linha"),
                toLocalDateTime(rs.getTimestamp("atualizado_em"))
        );
    }

    private PapelAcesso requireRole(String value, String field) {
        PapelAcesso role = PapelAcesso.fromNullable(value);
        if (role == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    field + " precisa ser ALFA ou BETA."
            );
        }
        return role;
    }

    private long requireVersion(Long value) {
        if (value == null || value < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "baseVersao é obrigatória e não pode ser negativa."
            );
        }
        return value;
    }

    private String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    field + " é obrigatório."
            );
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    field + " excede o limite de " + maxLength + " caracteres."
            );
        }
        return normalized;
    }

    private ResponseStatusException versionConflict() {
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Conflito de versão ou papel de acesso do colaborador."
        );
    }

    private LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
