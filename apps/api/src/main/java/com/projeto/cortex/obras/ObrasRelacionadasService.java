package com.projeto.cortex.obras;

import com.projeto.cortex.auth.AutorizacaoDeObra;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ObrasRelacionadasService {

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserService currentUserService;
    private final FinancialAccessService financialAccessService;

    public ObrasRelacionadasService(
            JdbcTemplate jdbcTemplate,
            CurrentUserService currentUserService,
            FinancialAccessService financialAccessService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUserService = currentUserService;
        this.financialAccessService = financialAccessService;
    }

    private static final String PROJECAO = """
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
                o.versao_linha,
                NULL AS valor_contratual
            FROM obra o
            WHERE o.arquivado_em IS NULL
            """;

    private static final String ORDENACAO = """
            ORDER BY o.atualizado_em DESC, o.id DESC
            LIMIT 200
            """;

    private static final RowMapper<ObraRelacionadaResponse> PROJETAR_OBRA =
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
                            : rs.getTimestamp("atualizado_em").toLocalDateTime(),
                    rs.getLong("versao_linha")
            );

    /**
     * As obras não arquivadas que o colaborador pode abrir: todas, para Alfa;
     * as de vínculo {@code ATIVO}, para Beta.
     *
     * <p>O {@code EXISTS} compara {@code colaborador_id} com {@code LOWER} dos
     * dois lados, e isso não é preciosismo. Quando esta consulta filtrava sem
     * normalizar caixa e {@code CurrentUserService} filtrava com, uma diferença
     * de caixa entre as tabelas bastava para a pessoa passar na autorização da
     * obra e mesmo assim nunca vê-la na lista — permitida e invisível ao mesmo
     * tempo, que é o defeito mais caro de diagnosticar. As duas pontas comparam
     * do mesmo jeito de propósito.
     *
     * <p>O valor contratual continua saindo só para quem tem permissão
     * financeira: é filtrado depois da consulta, e não por ela. Vínculo com a
     * obra não é permissão financeira.
     */
    public List<ObraRelacionadaResponse> listarParaColaborador() {
        String userId = currentUserService.requireUserId();
        boolean global = currentUserService.isAlfa(userId);

        String sql = global
                ? PROJECAO + ORDENACAO
                : PROJECAO
                        + " AND "
                        + AutorizacaoDeObra.existeCaminhoParaObra("o.id")
                        + ORDENACAO;

        List<ObraRelacionadaResponse> obras = global
                ? jdbcTemplate.query(sql, PROJETAR_OBRA)
                : jdbcTemplate.query(sql, PROJETAR_OBRA, userId, userId);

        Set<String> financeWorksites = financialAccessService.allowedObraIds(
                userId,
                FinancialPermission.FINANCEIRO_VISUALIZAR
        );
        List<String> visibleIds = obras.stream()
                .map(ObraRelacionadaResponse::id)
                .filter(financeWorksites::contains)
                .toList();
        if (visibleIds.isEmpty()) {
            return obras;
        }

        Map<String, BigDecimal> contractualByWorksite = new LinkedHashMap<>();
        String placeholders = String.join(
                ",",
                Collections.nCopies(visibleIds.size(), "?")
        );
        jdbcTemplate.query(
                "SELECT obra_id, COALESCE(SUM(valor_total), 0) AS total "
                        + "FROM item_contratual "
                        + "WHERE status = 'ATIVO' AND obra_id IN ("
                        + placeholders
                        + ") GROUP BY obra_id",
                rs -> {
                    contractualByWorksite.put(
                            rs.getString("obra_id"),
                            rs.getBigDecimal("total")
                    );
                },
                visibleIds.toArray()
        );

        List<ObraRelacionadaResponse> scoped = new ArrayList<>(obras.size());
        for (ObraRelacionadaResponse obra : obras) {
            scoped.add(new ObraRelacionadaResponse(
                    obra.id(),
                    obra.codigoContrato(),
                    obra.nome(),
                    obra.cliente(),
                    obra.cidade(),
                    obra.uf(),
                    obra.rodovia(),
                    obra.status(),
                    obra.observacoes(),
                    obra.latitude(),
                    obra.longitude(),
                    contractualByWorksite.get(obra.id()),
                    obra.atualizadoEm(),
                    obra.versaoLinha()
            ));
        }
        return List.copyOf(scoped);
    }
}
