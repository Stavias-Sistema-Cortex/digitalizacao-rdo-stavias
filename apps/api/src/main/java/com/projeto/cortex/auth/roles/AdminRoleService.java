package com.projeto.cortex.auth.roles;

import com.projeto.cortex.auth.PapelAcesso;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminRoleService {

    private static final String SOURCE = "CORTEX_AUTH";
    private static final String SESSION_REVOCATION_REASON =
            "PAPEL_REBAIXADO_PARA_BETA";

    private final AdminRoleRepository repository;
    private final CortexOperationalMemoryService memory;

    AdminRoleService(
            AdminRoleRepository repository,
            CortexOperationalMemoryService memory
    ) {
        this.repository = repository;
        this.memory = memory;
    }

    @Transactional
    public AdminRoleChangeResponse changeRole(
            String actorId,
            String collaboratorId,
            AdminRoleChangeRequest request
    ) {
        String normalizedActorId = requireUuid(actorId, "administrador");
        String normalizedCollaboratorId = requireUuid(
                collaboratorId,
                "colaborador"
        );
        PapelAcesso newRole = requireRole(request);
        String justification = requireJustification(request);
        RoleChangeExecution execution = executeGovernedChange(
                normalizedActorId,
                normalizedCollaboratorId,
                newRole,
                justification,
                null,
                false
        );
        return new AdminRoleChangeResponse(
                normalizedCollaboratorId,
                execution.before().name(),
                execution.before().role().name(),
                newRole.name(),
                execution.revokedSessions(),
                execution.commitSeq(),
                execution.changedAt()
        );
    }

    @Transactional
    public AdminRoleVersionedChangeResponse changeRoleVersioned(
            String actorId,
            String collaboratorId,
            AdminRoleVersionedChangeRequest request
    ) {
        if (request == null) {
            throw status(
                    HttpStatus.BAD_REQUEST,
                    "Dados da mudança de acesso são obrigatórios."
            );
        }
        String normalizedActorId = requireUuid(actorId, "administrador");
        String normalizedCollaboratorId = requireUuid(
                collaboratorId,
                "colaborador"
        );
        PapelAcesso expectedRole = PapelAcesso
                .fromPersistedExact(request.papelAtual())
                .orElseThrow(() -> status(
                        HttpStatus.BAD_REQUEST,
                        "papelAtual precisa ser ALFA ou BETA."
                ));
        PapelAcesso newRole = PapelAcesso
                .fromPersistedExact(request.papelNovo())
                .orElseThrow(() -> status(
                        HttpStatus.BAD_REQUEST,
                        "papelNovo precisa ser ALFA ou BETA."
                ));
        if (request.baseVersao() == null || request.baseVersao() < 0) {
            throw status(
                    HttpStatus.BAD_REQUEST,
                    "baseVersao é obrigatória e não pode ser negativa."
            );
        }
        String justification = requireJustification(new AdminRoleChangeRequest(
                request.papelNovo(),
                request.justificativa()
        ));
        RoleChangeExecution execution = executeGovernedChange(
                normalizedActorId,
                normalizedCollaboratorId,
                newRole,
                justification,
                new ExpectedState(expectedRole, request.baseVersao()),
                true
        );
        return new AdminRoleVersionedChangeResponse(
                execution.after().id(),
                execution.after().name(),
                execution.after().role().name(),
                execution.after().rowVersion(),
                execution.after().updatedAt()
        );
    }

    private RoleChangeExecution executeGovernedChange(
            String normalizedActorId,
            String normalizedCollaboratorId,
            PapelAcesso newRole,
            String justification,
            ExpectedState expectedState,
            boolean preserveLegacySelfDemotionGuard
    ) {
        repository.lockGovernanceRows();
        RoleAccount actor = repository.findActiveAccount(normalizedActorId)
                .orElseThrow(() -> status(
                        HttpStatus.FORBIDDEN,
                        "Conta administrativa ativa não encontrada."
                ));
        if (actor.role() != PapelAcesso.ALFA
                || !repository.hasActiveCapability(normalizedActorId)) {
            throw status(
                    HttpStatus.FORBIDDEN,
                    "A operação exige a capacidade ADMINISTRAR_PAPEIS."
            );
        }

        RoleAccount target = repository.findActiveAccount(normalizedCollaboratorId)
                .orElseThrow(() -> status(
                        HttpStatus.NOT_FOUND,
                        "Colaborador ativo não encontrado."
                ));
        if (expectedState != null
                && (target.role() != expectedState.role()
                || target.rowVersion() != expectedState.rowVersion())) {
            throw versionConflict();
        }
        if (preserveLegacySelfDemotionGuard
                && actor.id().equals(target.id())
                && target.role() == PapelAcesso.ALFA
                && newRole == PapelAcesso.BETA) {
            throw status(
                    HttpStatus.CONFLICT,
                    "Um usuário Alfa não pode remover o próprio acesso administrativo."
            );
        }
        if (target.role() == newRole) {
            if (expectedState != null) {
                return new RoleChangeExecution(
                        target,
                        target,
                        0,
                        0L,
                        target.updatedAt()
                );
            }
            throw status(
                    HttpStatus.CONFLICT,
                    "O colaborador já possui o papel informado."
            );
        }

        boolean downgrade = target.role() == PapelAcesso.ALFA
                && newRole == PapelAcesso.BETA;
        boolean targetAdmin = downgrade
                && repository.hasActiveCapability(normalizedCollaboratorId);
        if (downgrade && repository.countActiveAlfas() <= 1) {
            throw status(
                    HttpStatus.CONFLICT,
                    "O último Alfa ativo não pode ser rebaixado."
            );
        }
        if (targetAdmin && repository.countActiveRoleAdministrators() <= 1) {
            throw status(
                    HttpStatus.CONFLICT,
                    "O último administrador de papéis não pode ser rebaixado."
            );
        }

        if (targetAdmin) {
            repository.revokeCapability(
                    normalizedCollaboratorId,
                    normalizedActorId,
                    justification
            );
        }
        if (expectedState == null) {
            repository.updateRole(normalizedCollaboratorId, newRole);
        } else if (!repository.updateRoleIfCurrent(
                normalizedCollaboratorId,
                target.role(),
                target.rowVersion(),
                newRole
        )) {
            throw versionConflict();
        }
        repository.insertHistory(
                normalizedCollaboratorId,
                target.role(),
                newRole,
                normalizedActorId,
                justification
        );
        int revokedSessions = downgrade
                ? repository.revokeActiveSessions(
                        normalizedCollaboratorId,
                        SESSION_REVOCATION_REASON
                )
                : 0;

        long commitSeq = publishOntology(
                actor,
                target,
                newRole,
                justification,
                revokedSessions
        );
        RoleAccount after = expectedState == null
                ? new RoleAccount(
                        target.id(),
                        target.name(),
                        newRole,
                        target.rowVersion() + 1L,
                        LocalDateTime.now()
                )
                : repository.findActiveAccount(normalizedCollaboratorId)
                        .filter(account ->
                                account.role() == newRole
                                        && account.rowVersion()
                                        == target.rowVersion() + 1L
                        )
                        .orElseThrow(() -> new IllegalStateException(
                                "Estado persistido do papel divergiu após a alteração."
                        ));
        return new RoleChangeExecution(
                target,
                after,
                revokedSessions,
                commitSeq,
                LocalDateTime.now()
        );
    }

    private ResponseStatusException versionConflict() {
        return status(
                HttpStatus.CONFLICT,
                "Conflito de versão ou papel de acesso do colaborador."
        );
    }

    private long publishOntology(
            RoleAccount actor,
            RoleAccount target,
            PapelAcesso newRole,
            String justification,
            int revokedSessions
    ) {
        memory.registrarObjeto(
                "PESSOA",
                actor.id(),
                actor.id(),
                actor.name(),
                "ATIVO",
                SOURCE
        );
        memory.registrarObjeto(
                "PESSOA",
                target.id(),
                target.id(),
                target.name(),
                "ATIVO",
                SOURCE
        );
        memory.registrarRelacaoAtiva(
                "PESSOA",
                actor.id(),
                "PESSOA",
                target.id(),
                "ALTEROU_PAPEL_DE",
                SOURCE,
                target.role().name() + " -> " + newRole.name()
        );

        LocalDateTime now = LocalDateTime.now();
        return memory.registrarEventoAuditado(
                null,
                "PESSOA",
                target.id(),
                "PAPEL_ACESSO_ALTERADO",
                SOURCE,
                null,
                null,
                target.id(),
                List.of(Map.of(
                        "tipoEntidade", "PESSOA",
                        "entidadeId", actor.id(),
                        "papel", "ADMINISTRADOR"
                )),
                "ONLINE",
                "SYNCED",
                now,
                now,
                1,
                Map.of(
                        "papelAnterior", target.role().name(),
                        "papelNovo", newRole.name(),
                        "justificativa", justification,
                        "sessoesRevogadas", revokedSessions
                ),
                actor.id(),
                null,
                UUID.randomUUID().toString(),
                null,
                Map.of("papelAcesso", target.role().name()),
                Map.of("papelAcesso", newRole.name()),
                "SUCESSO",
                null
        );
    }

    private PapelAcesso requireRole(AdminRoleChangeRequest request) {
        if (request == null) {
            throw status(HttpStatus.BAD_REQUEST, "Alteração de papel obrigatória.");
        }
        return PapelAcesso.fromPersistedExact(request.papelAcesso())
                .orElseThrow(() -> status(
                        HttpStatus.BAD_REQUEST,
                        "Papel deve ser ALFA ou BETA."
                ));
    }

    private String requireJustification(AdminRoleChangeRequest request) {
        if (request.justificativa() == null) {
            throw status(HttpStatus.BAD_REQUEST, "Justificativa obrigatória.");
        }
        String justification = request.justificativa().strip();
        if (justification.length() < 8
                || justification.length() > 500
                || justification.contains("\r")
                || justification.contains("\n")) {
            throw status(
                    HttpStatus.BAD_REQUEST,
                    "Justificativa deve ter entre 8 e 500 caracteres, em uma linha."
            );
        }
        return justification;
    }

    private String requireUuid(String value, String field) {
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) {
                throw new IllegalArgumentException();
            }
            return value;
        } catch (RuntimeException exception) {
            throw status(
                    HttpStatus.BAD_REQUEST,
                    "Identificador de " + field + " inválido."
            );
        }
    }

    private ResponseStatusException status(HttpStatus status, String reason) {
        return new ResponseStatusException(status, reason);
    }

    private record ExpectedState(PapelAcesso role, long rowVersion) {
    }

    private record RoleChangeExecution(
            RoleAccount before,
            RoleAccount after,
            int revokedSessions,
            long commitSeq,
            LocalDateTime changedAt
    ) {
    }
}
