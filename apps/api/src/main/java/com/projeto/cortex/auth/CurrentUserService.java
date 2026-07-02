package com.projeto.cortex.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.text.Normalizer;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CurrentUserService {

    public static final String REQUEST_ATTRIBUTE_USER_ID =
            "cortex.authenticatedUserId";

    private final JdbcTemplate jdbcTemplate;

    public CurrentUserService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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

    public void requireAdmin() {
        if (!isAdmin(requireUserId())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "A operação exige perfil administrativo."
            );
        }
    }

    public void requireSelfOrAdmin(String colaboradorId) {
        String currentUserId = requireUserId();
        if (sameId(currentUserId, colaboradorId) || isAdmin(currentUserId)) {
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
        if (isAdmin(currentUserId) || canAccessWorksite(currentUserId, normalizedObraId)) {
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

    public boolean isAdmin(String colaboradorId) {
        if (colaboradorId == null || colaboradorId.isBlank()) {
            return false;
        }

        return jdbcTemplate.query(
                """
                SELECT nome_perfil, nome_grupo
                FROM colaborador
                WHERE id = ?
                  AND ativo = 1
                  AND deletado_em IS NULL
                LIMIT 1
                """,
                rs -> {
                    if (!rs.next()) {
                        return false;
                    }
                    return isAdminText(rs.getString("nome_perfil"))
                            || isAdminText(rs.getString("nome_grupo"));
                },
                colaboradorId.trim()
        );
    }

    private boolean canAccessWorksite(String currentUserId, String obraId) {
        Integer allowed = jdbcTemplate.queryForObject(
                """
                SELECT CASE
                    WHEN EXISTS (
                        SELECT 1
                        FROM alocacao_colaborador
                        WHERE colaborador_id = ?
                          AND obra_id = ?
                          AND status <> 'CANCELADA'
                    )
                    OR EXISTS (
                        SELECT 1
                        FROM rdo_mao_obra mo
                        JOIN rdo r
                          ON r.id = mo.rdo_id
                        WHERE mo.colaborador_id = ?
                          AND r.obra_id = ?
                    )
                    THEN 1
                    ELSE 0
                END
                """,
                Integer.class,
                currentUserId,
                obraId,
                currentUserId,
                obraId
        );

        return allowed != null && allowed == 1;
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

    private boolean isAdminText(String value) {
        String normalized = normalize(value);
        return normalized.contains("admin")
                || normalized.contains("administrador")
                || normalized.contains("administradora");
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String noAccent = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return noAccent.toLowerCase(Locale.ROOT);
    }
}
