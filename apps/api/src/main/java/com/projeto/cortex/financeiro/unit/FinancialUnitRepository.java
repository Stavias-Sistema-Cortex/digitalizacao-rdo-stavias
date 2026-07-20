package com.projeto.cortex.financeiro.unit;

import com.projeto.cortex.financeiro.unit.FinancialUnitDtos.FinancialUnitFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FinancialUnitRepository {

    private final JdbcTemplate jdbcTemplate;

    public FinancialUnitRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<FinancialUnitRecord> list(FinancialUnitFilter filter) {
        StringBuilder sql = new StringBuilder(selectSql())
                .append(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (filter != null && filter.tipo() != null) {
            sql.append(" AND u.tipo = ?");
            args.add(filter.tipo().name());
        }
        if (filter != null && hasText(filter.status())) {
            sql.append(" AND u.status = ?");
            args.add(filter.status().trim().toUpperCase());
        }
        if (filter != null && hasText(filter.busca())) {
            sql.append(" AND (LOWER(u.nome) LIKE ? OR LOWER(u.codigo) LIKE ?)");
            String pattern = "%" + filter.busca().trim().toLowerCase() + "%";
            args.add(pattern);
            args.add(pattern);
        }
        sql.append(" ORDER BY u.tipo, u.nome, u.id LIMIT 500");
        return jdbcTemplate.query(
                sql.toString(),
                (rs, rowNum) -> map(rs),
                args.toArray()
        );
    }

    public Optional<FinancialUnitRecord> findById(String id) {
        return find(selectSql() + " WHERE u.id = ? LIMIT 1", id);
    }

    public Optional<FinancialUnitRecord> findByCode(String code) {
        return find(selectSql() + " WHERE u.codigo = ? LIMIT 1", code);
    }

    public Optional<FinancialUnitRecord> findByWorksite(String worksiteId) {
        return find(
                selectSql() + " WHERE u.obra_id = ? LIMIT 1",
                worksiteId
        );
    }

    public Optional<FinancialUnitRecord> findByAsset(String assetId) {
        return find(
                selectSql() + " WHERE u.ativo_id = ? LIMIT 1",
                assetId
        );
    }

    public Optional<FinancialUnitScope> findScopeById(String id) {
        return findById(id).map(FinancialUnitScope::from);
    }

    public Optional<FinancialUnitScope> findScopeByWorksite(String worksiteId) {
        return findByWorksite(worksiteId).map(FinancialUnitScope::from);
    }

    public Optional<AssetRecord> findActiveAsset(String assetId) {
        return jdbcTemplate.query(
                """
                SELECT id, name, category
                FROM asset
                WHERE id = ?
                  AND active = 1
                  AND deleted_at IS NULL
                LIMIT 1
                """,
                rs -> rs.next()
                        ? Optional.of(new AssetRecord(
                                rs.getString("id"),
                                rs.getString("name"),
                                rs.getString("category")
                        ))
                        : Optional.empty(),
                assetId
        );
    }

    public Optional<WorksiteRecord> findActiveWorksite(String worksiteId) {
        return jdbcTemplate.query(
                """
                SELECT id, nome
                FROM obra
                WHERE id = ?
                  AND arquivado_em IS NULL
                LIMIT 1
                """,
                rs -> rs.next()
                        ? Optional.of(new WorksiteRecord(
                                rs.getString("id"),
                                rs.getString("nome")
                        ))
                        : Optional.empty(),
                worksiteId
        );
    }

    public void insertAdministrative(
            String id,
            String code,
            String name,
            String actorId
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO finance_unidade_controle (
                    id, tipo, codigo, nome, status, origem,
                    criado_por, atualizado_por
                ) VALUES (?, 'ADMINISTRATIVO', ?, ?, 'ATIVA', 'MANUAL', ?, ?)
                """,
                id,
                code,
                name,
                actorId,
                actorId
        );
    }

    public void insertAsset(
            String id,
            String assetId,
            String code,
            String name,
            String actorId
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO finance_unidade_controle (
                    id, tipo, ativo_id, codigo, nome, status, origem,
                    criado_por, atualizado_por
                ) VALUES (?, 'ATIVO', ?, ?, ?, 'ATIVA', 'COMPRA_ATIVO', ?, ?)
                """,
                id,
                assetId,
                code,
                name,
                actorId,
                actorId
        );
    }

    public void insertWorksite(
            String id,
            String worksiteId,
            String code,
            String name,
            String actorId
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO finance_unidade_controle (
                    id, tipo, obra_id, codigo, nome, status, origem,
                    criado_por, atualizado_por
                ) VALUES (?, 'OBRA', ?, ?, ?, 'ATIVA', 'MANUAL', ?, ?)
                """,
                id,
                worksiteId,
                code,
                name,
                actorId,
                actorId
        );
    }

    private Optional<FinancialUnitRecord> find(String sql, Object... args) {
        List<FinancialUnitRecord> rows = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> map(rs),
                args
        );
        return rows.stream().findFirst();
    }

    private FinancialUnitRecord map(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        return new FinancialUnitRecord(
                rs.getString("id"),
                FinancialUnitType.valueOf(rs.getString("tipo")),
                rs.getString("obra_id"),
                rs.getString("ativo_id"),
                rs.getString("codigo"),
                rs.getString("nome"),
                rs.getString("status"),
                rs.getLong("versao_linha")
        );
    }

    private String selectSql() {
        return """
                SELECT u.id, u.tipo, u.obra_id, u.ativo_id, u.codigo,
                       u.nome, u.status, u.versao_linha
                FROM finance_unidade_controle u
                """;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record FinancialUnitScope(
            String id,
            FinancialUnitType type,
            String worksiteId,
            String assetId,
            String status
    ) {
        private static FinancialUnitScope from(FinancialUnitRecord record) {
            return new FinancialUnitScope(
                    record.id(),
                    record.type(),
                    record.worksiteId(),
                    record.assetId(),
                    record.status()
            );
        }
    }

    public record AssetRecord(String id, String name, String category) {
    }

    public record WorksiteRecord(String id, String name) {
    }
}

record FinancialUnitRecord(
        String id,
        FinancialUnitType type,
        String worksiteId,
        String assetId,
        String code,
        String name,
        String status,
        long version
) {
}
