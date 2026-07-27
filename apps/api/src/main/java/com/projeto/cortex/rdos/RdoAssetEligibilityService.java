package com.projeto.cortex.rdos;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RdoAssetEligibilityService {

    private final JdbcTemplate jdbcTemplate;

    public RdoAssetEligibilityService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void requireEligible(
            String obraId,
            List<RdoCreateRequest.EquipamentoItem> equipamentos
    ) {
        Set<String> checked = new HashSet<>();
        for (RdoCreateRequest.EquipamentoItem item : safe(equipamentos)) {
            String assetId = normalized(item.assetId());
            if (assetId == null || !checked.add(assetId)) {
                continue;
            }
            Integer eligible = jdbcTemplate.queryForObject(
                    """
                    SELECT CASE WHEN EXISTS (
                        SELECT 1
                        FROM asset_obra_eligibilidade eligibility
                        JOIN asset ON asset.id = eligibility.asset_id
                        WHERE eligibility.asset_id = ?
                          AND eligibility.obra_id = ?
                          AND eligibility.status = 'ATIVO'
                          AND asset.active = TRUE
                          AND asset.deleted_at IS NULL
                    ) THEN 1 ELSE 0 END
                    """,
                    Integer.class,
                    assetId,
                    obraId
            );
            if (eligible == null || eligible != 1) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "O equipamento informado não está ativo e elegível para a obra do RDO."
                );
            }
        }
    }

    private List<RdoCreateRequest.EquipamentoItem> safe(
            List<RdoCreateRequest.EquipamentoItem> items
    ) {
        return items == null ? List.of() : items;
    }

    private String normalized(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
