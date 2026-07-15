package com.projeto.cortex.auth.offline;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.auth.PapelAcesso;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OfflineGrantService {

    private final CurrentUserService currentUsers;
    private final OfflineGrantSubjectRepository subjects;
    private final OfflineGrantSigner signer;
    private final long ttlSeconds;
    private final int maxWorksites;

    public OfflineGrantService(
            CurrentUserService currentUsers,
            OfflineGrantSubjectRepository subjects,
            OfflineGrantSigningKey signingKey,
            OfflineGrantProperties properties
    ) {
        this.currentUsers = currentUsers;
        this.subjects = subjects;
        this.signer = new OfflineGrantSigner(signingKey);
        this.ttlSeconds = properties.requireValidTtlSeconds();
        this.maxWorksites = properties.requireValidMaxWorksites();
    }

    @Transactional(readOnly = true)
    public OfflineGrant issue(UUID colaboradorId) {
        if (colaboradorId == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Identidade autenticada inválida."
            );
        }
        String id = colaboradorId.toString();
        PapelAcesso role = currentUsers.papelAcesso(id);
        if (role == null) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Perfil sem autorização para uso offline."
            );
        }
        Optional<Set<String>> allowed = currentUsers.allowedObraIds(id);
        Scope scope = scope(role, allowed);
        OfflineGrantSubject subject = subjects.findActive(colaboradorId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Perfil sem autorização para uso offline."
                ));
        String name = requireName(subject.nome());
        Instant issuedAt = requireDatabaseTime(subject.databaseNow());
        try {
            return signer.sign(new OfflineGrantClaims(
                    1,
                    colaboradorId,
                    name,
                    role,
                    scope.global(),
                    scope.worksiteIds(),
                    issuedAt,
                    issuedAt.plusSeconds(ttlSeconds)
            ));
        } catch (OfflineGrantScopeTooLargeException exception) {
            throw scopeTooLarge();
        }
    }

    private Scope scope(
            PapelAcesso role,
            Optional<Set<String>> allowed
    ) {
        if (role == PapelAcesso.ALFA) {
            if (allowed.isPresent()) {
                throw new IllegalStateException(
                        "Escopo global inconsistente para perfil ALFA."
                );
            }
            return new Scope(true, List.of());
        }
        if (role != PapelAcesso.BETA || allowed.isEmpty()) {
            throw new IllegalStateException(
                    "Escopo restrito inconsistente para perfil BETA."
            );
        }
        Set<String> worksiteIds = allowed.orElseThrow();
        if (worksiteIds.size() > maxWorksites) {
            throw scopeTooLarge();
        }
        List<String> sorted = worksiteIds.stream()
                .map(this::requireCanonicalUuid)
                .sorted(Comparator.naturalOrder())
                .toList();
        if (sorted.size() != Set.copyOf(sorted).size()) {
            throw new IllegalStateException(
                    "Escopo offline contém obras duplicadas."
            );
        }
        return new Scope(false, sorted);
    }

    private String requireCanonicalUuid(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            String canonical = parsed.toString();
            if (!canonical.equals(value)) {
                throw new IllegalArgumentException();
            }
            return canonical;
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Escopo offline contém identificador de obra inválido."
            );
        }
    }

    private String requireName(String value) {
        if (value == null || value.isBlank() || value.length() > 255) {
            throw new IllegalStateException(
                    "Nome do perfil offline inválido."
            );
        }
        return value;
    }

    private Instant requireDatabaseTime(Instant value) {
        if (value == null) {
            throw new IllegalStateException(
                    "Relógio UTC do banco indisponível."
            );
        }
        return value;
    }

    private ResponseStatusException scopeTooLarge() {
        return new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Escopo offline grande demais para este dispositivo; "
                        + "reduza as obras vinculadas ou reconecte-se para "
                        + "atualizar o acesso."
        );
    }

    private record Scope(boolean global, List<String> worksiteIds) {
    }
}
