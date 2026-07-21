package com.projeto.cortex.auth;

import com.projeto.cortex.auth.otp.AuthenticatedIdentity;
import com.projeto.cortex.auth.session.AuthSessionProfileResolver;
import com.projeto.cortex.auth.session.ResolvedAuthSession;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

/**
 * Ponto central de autorização do Córtex. Resolve o papel de acesso
 * ({@link PapelAcesso}) do usuário autenticado e o vínculo explícito com cada
 * obra, servindo controllers, serviços e a política de acesso da Stav.IA.
 *
 * <p>Regras de acesso:
 * <ul>
 *   <li><b>ALFA</b>: escopo global — enxerga e administra todas as obras.</li>
 *   <li><b>BETA</b>: escopo restrito — acessa apenas as obras com
 *       {@code vinculo_colaborador_obra} ativo.</li>
 * </ul>
 *
 * <p>O acesso à obra é derivado exclusivamente do vínculo explícito. Não há mais
 * concessão por inferência (alocação operacional ou presença em RDO anterior).
 */
@Service
@Profile("!postgresql-common")
public class CurrentUserService implements AuthSessionProfileResolver {

    public static final String REQUEST_ATTRIBUTE_USER_ID =
            "cortex.authenticatedUserId";

    private final JdbcTemplate jdbcTemplate;
    private final Environment environment;
    private final boolean devAdminEnabled;

    public CurrentUserService(
            JdbcTemplate jdbcTemplate,
            Environment environment,
            @Value("${cortex.auth.dev-admin.enabled:false}")
            boolean devAdminEnabled
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.environment = environment;
        this.devAdminEnabled = devAdminEnabled;
    }

    public String requireUserId() {
        Object value = currentRequest().getAttribute(REQUEST_ATTRIBUTE_USER_ID);
        if (value instanceof String userId && !userId.isBlank()) {
            return userId.trim();
        }

        throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Usuário autenticado não encontrado no contexto da requisição."
        );
    }

    /** Exige papel ALFA (acesso administrativo global). */
    public void requireAdmin() {
        if (!isAlfa(requireUserId())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "A operação exige perfil administrativo (Alfa)."
            );
        }
    }

    /** Alias explícito de {@link #requireAdmin()} usando o vocabulário Alfa/Beta. */
    public void requireAlfa() {
        requireAdmin();
    }

    public void requireSelfOrAdmin(String colaboradorId) {
        String currentUserId = requireUserId();
        if (sameId(currentUserId, colaboradorId) || isAlfa(currentUserId)) {
            return;
        }

        throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Você não pode acessar dados de outro colaborador."
        );
    }

    public void requireWorksiteAccess(String obraId) {
        String normalizedObraId = requireId(obraId, "obraId");
        String currentUserId = requireUserId();
        if (podeAcessarObra(currentUserId, normalizedObraId)) {
            return;
        }

        throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Você não possui permissão para acessar esta obra."
        );
    }

    public void requireRdoAccess(String rdoId) {
        String normalizedRdoId = requireId(rdoId, "rdoId");
        String obraId = jdbcTemplate.query(
                """
                SELECT obra_id
                FROM rdo
                WHERE id = ?
                LIMIT 1
                """,
                rs -> rs.next() ? rs.getString("obra_id") : null,
                normalizedRdoId
        );

        if (obraId == null || obraId.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "RDO não encontrado."
            );
        }

        requireWorksiteAccess(obraId);
    }

    /**
     * Papel de acesso do colaborador. Retorna {@code null} para usuário em
     * branco, inexistente, inativo ou removido — nesses casos o chamador deve
     * negar o acesso. No perfil {@code local} com dev-admin habilitado, todo
     * usuário identificado é tratado como ALFA.
     */
    public PapelAcesso papelAcesso(String colaboradorId) {
        if (colaboradorId == null || colaboradorId.isBlank()) {
            return null;
        }

        if (isLocalDevAdminEnabled()) {
            return PapelAcesso.ALFA;
        }

        return jdbcTemplate.query(
                """
                SELECT papel_acesso
                FROM colaborador
                WHERE id = ?
                  AND ativo = 1
                  AND deletado_em IS NULL
                LIMIT 1
                """,
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    return PapelAcesso.fromPersistedExact(
                            rs.getString("papel_acesso")
                    ).orElse(null);
                },
                colaboradorId.trim()
        );
    }

    public boolean isAlfa(String colaboradorId) {
        return papelAcesso(colaboradorId) == PapelAcesso.ALFA;
    }

    /**
     * Compatibilidade histórica: "admin" equivale a ALFA. Mantido para não
     * quebrar chamadas existentes; novo código deve preferir {@link #isAlfa}.
     */
    public boolean isAdmin(String colaboradorId) {
        return isAlfa(colaboradorId);
    }

    /**
     * Decisão booleana e não lançadora de acesso à obra. ALFA acessa qualquer
     * obra; BETA acessa apenas obras com vínculo ativo. Base de authz para a
     * Stav.IA, o PDOR e demais consultas por obra.
     */
    public boolean podeAcessarObra(String colaboradorId, String obraId) {
        if (colaboradorId == null || colaboradorId.isBlank()
                || obraId == null || obraId.isBlank()) {
            return false;
        }

        PapelAcesso papel = papelAcesso(colaboradorId);
        if (papel == null) {
            return false;
        }
        if (papel == PapelAcesso.ALFA) {
            return true;
        }

        return temVinculoAtivo(colaboradorId.trim(), obraId.trim());
    }

    /**
     * Obras às quais o usuário tem acesso.
     * <ul>
     *   <li>{@code Optional.empty()} — escopo global (ALFA), sem restrição.</li>
     *   <li>{@code Optional.of(conjunto)} — BETA, restrito às obras vinculadas
     *       (conjunto possivelmente vazio).</li>
     * </ul>
     * Usuário inválido recebe {@code Optional.of(Set.of())} (nega tudo).
     */
    public Optional<Set<String>> allowedObraIds(String colaboradorId) {
        PapelAcesso papel = papelAcesso(colaboradorId);
        if (papel == null) {
            return Optional.of(Set.of());
        }
        if (papel == PapelAcesso.ALFA) {
            return Optional.empty();
        }

        List<String> ids = jdbcTemplate.queryForList(
                """
                SELECT obra_id
                FROM vinculo_colaborador_obra
                WHERE colaborador_id = ?
                  AND status = 'ATIVO'
                """,
                String.class,
                colaboradorId.trim()
        );

        return Optional.of(Set.copyOf(ids));
    }

    @Override
    public void requireEligibleForSessionIssue(AuthenticatedIdentity identity) {
        if (identity == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Identidade autenticada inválida."
            );
        }
    }

    @Override
    public AuthSessionResponse profileForIssuedSession(
            AuthenticatedIdentity identity,
            Instant expiresAt
    ) {
        requireEligibleForSessionIssue(identity);
        return AuthSessionResponse.from(
                identity,
                expiresAt,
                allowedObraIds(identity.colaboradorId())
        );
    }

    @Override
    public AuthSessionResponse profileForResolvedSession(
            ResolvedAuthSession session
    ) {
        if (session == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Sessão inválida ou expirada."
            );
        }
        return AuthSessionResponse.from(
                session,
                allowedObraIds(session.collaboratorId())
        );
    }

    private boolean temVinculoAtivo(String colaboradorId, String obraId) {
        Integer allowed = jdbcTemplate.queryForObject(
                """
                SELECT CASE WHEN EXISTS (
                    SELECT 1
                    FROM vinculo_colaborador_obra
                    WHERE colaborador_id = ?
                      AND obra_id = ?
                      AND status = 'ATIVO'
                ) THEN 1 ELSE 0 END
                """,
                Integer.class,
                colaboradorId,
                obraId
        );

        return allowed != null && allowed == 1;
    }

    private boolean isLocalDevAdminEnabled() {
        return devAdminEnabled
                && Arrays.stream(environment.getActiveProfiles())
                        .anyMatch("local"::equals);
    }

    private HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest();
        }

        throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Contexto da requisição indisponível."
        );
    }

    private String requireId(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    fieldName + " é obrigatório."
            );
        }
        return value.trim();
    }

    private boolean sameId(String left, String right) {
        return left != null
                && right != null
                && left.trim().equals(right.trim());
    }
}
