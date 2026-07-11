package com.projeto.cortex.obras;

import com.projeto.cortex.auth.CurrentUserService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ObrasRelacionadasService {

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserService currentUserService;

    public ObrasRelacionadasService(
            JdbcTemplate jdbcTemplate,
            CurrentUserService currentUserService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUserService = currentUserService;
    }

    public List<ObraRelacionadaResponse> listarParaColaborador() {
        String userId = currentUserService.requireUserId();
        int isAdmin = currentUserService.isAdmin(userId) ? 1 : 0;

        return jdbcTemplate.query(
                """
                SELECT
                    o.id,
                    o.codigo_contrato,
                    o.nome,
                    o.cliente,
                    o.cidade,
                    o.uf,
                    o.rodovia,
                    o.status,
                    o.observacoes,
                    o.latitude,
                    o.longitude,
                    o.atualizado_em,
                    (
                        SELECT COALESCE(SUM(ic.valor_total), 0)
                        FROM item_contratual ic
                        WHERE ic.obra_id = o.id
                          AND ic.status = 'ATIVO'
                    ) AS valor_contratual
                FROM obra o
                WHERE o.arquivado_em IS NULL
                  AND (
                        ? = 1
                     OR EXISTS (
                            SELECT 1
                            FROM vinculo_colaborador_obra v
                            WHERE v.colaborador_id = ?
                              AND v.obra_id = o.id
                              AND v.status = 'ATIVO'
                        )
                  )
                ORDER BY o.atualizado_em DESC, o.id DESC
                LIMIT 200
                """,
                (rs, rowNum) -> new ObraRelacionadaResponse(
                        rs.getString("id"),
                        rs.getString("codigo_contrato"),
                        rs.getString("nome"),
                        rs.getString("cliente"),
                        rs.getString("cidade"),
                        rs.getString("uf"),
                        rs.getString("rodovia"),
                        rs.getString("status"),
                        rs.getString("observacoes"),
                        rs.getBigDecimal("latitude"),
                        rs.getBigDecimal("longitude"),
                        rs.getBigDecimal("valor_contratual"),
                        rs.getTimestamp("atualizado_em") == null
                                ? null
                                : rs.getTimestamp("atualizado_em").toLocalDateTime()
                ),
                isAdmin, userId
        );
    }
}
