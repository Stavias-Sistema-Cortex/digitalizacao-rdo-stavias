package com.projeto.cortex.auth;

import com.projeto.cortex.auth.otp.AuthenticatedIdentity;
import com.projeto.cortex.auth.session.AuthSessionProfileResolver;
import com.projeto.cortex.auth.session.ResolvedAuthSession;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
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
public class CurrentUserService implements AuthSessionProfileResolver {

    public static final String REQUEST_ATTRIBUTE_USER_ID =
            "cortex.authenticatedUserId";

    /**
     * Memória de autorização com validade de uma requisição.
     *
     * <p>Papel e vínculo eram relidos do banco a cada pergunta, e as perguntas
     * se repetem: uma única requisição pergunta o papel ao filtro de sessão, de
     * novo ao autorizar a obra, de novo ao montar o perfil — e serviços que
     * percorrem participantes perguntam uma vez por pessoa. Cada repetição era
     * uma ida ao banco para receber a resposta que já se tinha.
     *
     * <p>O alcance é o da requisição, e não maior, de propósito. Dentro dela o
     * papel é constante porque nenhuma escrita de papel ou de vínculo passa por
     * aqui; fora dela nada é lembrado, então revogar um papel continua valendo
     * já na requisição seguinte. Guardar por mais tempo trocaria idas ao banco
     * por acesso revogado que sobrevive — e esse não é um câmbio aceitável.
     */
    private static final String REQUEST_ATTRIBUTE_AUTHORIZATION_MEMO =
            "cortex.authorizationMemo";

    private record ChavePapel(String colaboradorId) {
    }

    private record ChaveVinculo(String colaboradorId, String obraId) {
    }

    private record ChaveObras(String colaboradorId) {
    }

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
        String obraId = findRdoWorksite(normalizedRdoId);

        requireWorksiteAccess(obraId);
    }

    /**
     * Authorizes an RDO write against the server-owned worksite reference.
     * Access to both worksites is not enough: a client cannot move an existing
     * RDO by supplying another worksite identifier in the payload.
     */
    public void requireRdoWorksiteAccess(String rdoId, String requestedObraId) {
        String normalizedRdoId = requireId(rdoId, "rdoId");
        String normalizedRequestedObraId = requireId(requestedObraId, "obraId");
        String persistedObraId = findRdoWorksite(normalizedRdoId);

        if (!persistedObraId.equals(normalizedRequestedObraId)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A obra informada não pertence ao RDO."
            );
        }

        requireWorksiteAccess(persistedObraId);
    }

    private String findRdoWorksite(String normalizedRdoId) {
        String obraId = jdbcTemplate.query(
                """
                SELECT obra_id
                FROM rdo
                WHERE LOWER(id) = LOWER(?)
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
        return obraId.strip();
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

        String normalizado = colaboradorId.trim();
        return lembrarNaRequisicao(
                new ChavePapel(normalizado),
                () -> jdbcTemplate.query(
                        """
                        SELECT papel_acesso
                        FROM colaborador
                        WHERE id = ?
                          AND ativo = TRUE
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
                        normalizado
                )
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

        String normalizado = colaboradorId.trim();
        return lembrarNaRequisicao(
                new ChaveObras(normalizado),
                () -> Optional.of(Set.copyOf(jdbcTemplate.queryForList(
                        """
                        SELECT obra_id
                        FROM vinculo_colaborador_obra
                        WHERE LOWER(colaborador_id) = LOWER(?)
                          AND status = 'ATIVO'
                        """,
                        String.class,
                        normalizado
                )))
        );
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
        return lembrarNaRequisicao(
                new ChaveVinculo(colaboradorId, obraId),
                () -> {
                    Integer allowed = jdbcTemplate.queryForObject(
                            """
                            SELECT CASE WHEN EXISTS (
                                SELECT 1
                                FROM vinculo_colaborador_obra
                                WHERE LOWER(colaborador_id) = LOWER(?)
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
        );
    }

    /**
     * Responde uma vez por requisição e reaproveita dentro dela.
     *
     * <p>Fora de uma requisição — tarefas agendadas, sincronizações — não há o
     * que reaproveitar nem onde guardar, e a consulta é feita direto. O mapa é
     * confinado à thread que atende a requisição, porque o contexto não é
     * herdado por threads filhas: quem sair dela cai neste mesmo caminho
     * direto, em vez de compartilhar estado.
     */
    @SuppressWarnings("unchecked")
    private <T> T lembrarNaRequisicao(Object chave, Supplier<T> resolver) {
        RequestAttributes atributos =
                RequestContextHolder.getRequestAttributes();
        if (atributos == null) {
            return resolver.get();
        }

        Map<Object, Object> memoria = (Map<Object, Object>)
                atributos.getAttribute(
                        REQUEST_ATTRIBUTE_AUTHORIZATION_MEMO,
                        RequestAttributes.SCOPE_REQUEST
                );
        if (memoria == null) {
            memoria = new HashMap<>();
            atributos.setAttribute(
                    REQUEST_ATTRIBUTE_AUTHORIZATION_MEMO,
                    memoria,
                    RequestAttributes.SCOPE_REQUEST
            );
        }

        // `null` é resposta legítima: significa colaborador inexistente,
        // inativo ou removido, e o chamador nega o acesso a partir dela. Guardar
        // por presença de chave, e não por valor não-nulo, é o que impede que
        // justamente a negativa seja reconsultada a cada pergunta.
        if (memoria.containsKey(chave)) {
            return (T) memoria.get(chave);
        }
        T valor = resolver.get();
        memoria.put(chave, valor);
        return valor;
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
                && left.trim().equalsIgnoreCase(right.trim());
    }
}
