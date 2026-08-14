package com.projeto.cortex.pdor;

import com.projeto.cortex.financeiro.catalog.ServiceCatalogProjectionInvalidator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Um serviço do catálogo é corporativo e pode alimentar várias obras.
 * Excluir ou restaurar esse serviço invalida toda projeção atual derivada de
 * uma de suas versões de preço.
 */
@Component
public class PdorServiceCatalogProjectionInvalidator
        implements ServiceCatalogProjectionInvalidator {

    private final JdbcTemplate jdbcTemplate;

    public PdorServiceCatalogProjectionInvalidator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void invalidateService(String serviceId) {
        /*
         * Serializa a transição do catálogo com cada cálculo que possa
         * publicar para uma obra afetada. O cálculo mantém a linha com
         * FOR UPDATE desde antes do loader até a publicação; o FOR SHARE daqui
         * é compatível com outras mutações do catálogo e incompatível com o
         * cálculo. Assim, ele ou
         * publica primeiro e é invalidado logo depois, ou espera esta
         * transição e lê o catálogo novo em READ_COMMITTED.
         *
         * A versão operacional da obra não muda: excluir um serviço global
         * não é uma edição do cadastro da obra e não deve provocar conflito
         * otimista nas telas de gestão.
         */
        jdbcTemplate.queryForList(
                """
                SELECT obra.id
                FROM obra
                WHERE EXISTS (
                    SELECT 1
                    FROM service_price_version price
                    WHERE price.obra_id = obra.id
                      AND price.service_id = ?
                )
                ORDER BY obra.id
                FOR SHARE
                """,
                String.class,
                serviceId
        );
        jdbcTemplate.update(
                """
                UPDATE pdor_snapshot snapshot
                SET is_current = false,
                    is_stale = true
                WHERE snapshot.is_current = true
                  AND snapshot.obra_id IN (
                      SELECT DISTINCT price.obra_id
                      FROM service_price_version price
                      WHERE price.service_id = ?
                  )
                """,
                serviceId
        );
    }

    @Override
    @Transactional
    public void invalidateWorksite(String obraId) {
        jdbcTemplate.queryForObject(
                "SELECT id FROM obra WHERE id = ? FOR SHARE",
                String.class,
                obraId
        );
        jdbcTemplate.update(
                """
                UPDATE pdor_snapshot
                SET is_current = false,
                    is_stale = true
                WHERE obra_id = ?
                  AND is_current = true
                """,
                obraId
        );
    }
}
