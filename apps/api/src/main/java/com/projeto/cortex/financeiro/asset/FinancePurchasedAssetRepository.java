package com.projeto.cortex.financeiro.asset;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FinancePurchasedAssetRepository {

    private final JdbcTemplate jdbc;

    public FinancePurchasedAssetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PurchasedAssetMutation> findMutation(
            String actorId,
            String clientMutationId
    ) {
        return findMutation(actorId, clientMutationId, false);
    }

    public Optional<PurchasedAssetMutation> findMutationCurrent(
            String actorId,
            String clientMutationId
    ) {
        return findMutation(actorId, clientMutationId, true);
    }

    private Optional<PurchasedAssetMutation> findMutation(
            String actorId,
            String clientMutationId,
            boolean currentRead
    ) {
        List<PurchasedAssetMutation> rows = jdbc.query(
                """
                SELECT compra_item_id, (concluido_em IS NOT NULL) AS concluida
                FROM finance_compra_item_ativo_mutacao
                WHERE ator_id = ? AND client_mutation_id = ?
                LIMIT 1%s
                """.formatted(currentRead ? " FOR UPDATE" : ""),
                (rs, rowNum) -> new PurchasedAssetMutation(
                        rs.getString("compra_item_id"),
                        rs.getBoolean("concluida")
                ),
                actorId,
                clientMutationId
        );
        return rows.stream().findFirst();
    }

    public void claimMutation(
            String actorId,
            String clientMutationId,
            String itemId
    ) {
        jdbc.update(
                """
                INSERT INTO finance_compra_item_ativo_mutacao (
                    ator_id, client_mutation_id, compra_item_id
                ) VALUES (?, ?, ?)
                """,
                actorId,
                clientMutationId,
                itemId
        );
    }

    public void completeMutation(
            String actorId,
            String clientMutationId,
            String itemId
    ) {
        if (jdbc.update(
                """
                UPDATE finance_compra_item_ativo_mutacao
                SET concluido_em = CURRENT_TIMESTAMP(6)
                WHERE ator_id = ? AND client_mutation_id = ?
                  AND compra_item_id = ? AND concluido_em IS NULL
                """,
                actorId,
                clientMutationId,
                itemId
        ) != 1) {
            throw new IllegalStateException(
                    "A reserva idempotente dos ativos não pôde ser concluída."
            );
        }
    }

    public Optional<PurchasedItem> lockItem(
            String purchaseId,
            String itemId
    ) {
        return item(purchaseId, itemId, true);
    }

    public Optional<PurchasedItem> findItem(
            String purchaseId,
            String itemId
    ) {
        return item(purchaseId, itemId, false);
    }

    private Optional<PurchasedItem> item(
            String purchaseId,
            String itemId,
            boolean currentRead
    ) {
        List<PurchasedItem> rows = jdbc.query(
                """
                SELECT c.id AS compra_id, i.id AS item_id, c.obra_id,
                       i.descricao, i.quantidade, i.natureza,
                       (i.arquivado_em IS NOT NULL OR c.arquivado_em IS NOT NULL)
                           AS arquivado,
                       i.versao_linha
                FROM finance_compra_item i
                JOIN finance_compra c ON c.id = i.compra_id
                WHERE c.id = ? AND i.id = ?
                LIMIT 1%s
                """.formatted(currentRead ? " FOR UPDATE" : ""),
                (rs, rowNum) -> new PurchasedItem(
                        rs.getString("compra_id"),
                        rs.getString("item_id"),
                        rs.getString("obra_id"),
                        rs.getString("descricao"),
                        rs.getBigDecimal("quantidade"),
                        rs.getString("natureza"),
                        rs.getBoolean("arquivado"),
                        rs.getLong("versao_linha")
                ),
                purchaseId,
                itemId
        );
        return rows.stream().findFirst();
    }

    public Optional<AssetRecord> findActiveAsset(String assetId) {
        return findAsset(
                """
                WHERE id = ? AND active = TRUE AND deleted_at IS NULL
                LIMIT 1
                """,
                assetId
        );
    }

    public Optional<AssetRecord> findActiveAssetByExternalCode(String code) {
        return findAsset(
                """
                WHERE external_code = ? AND active = TRUE AND deleted_at IS NULL
                ORDER BY id LIMIT 1
                """,
                code
        );
    }

    private Optional<AssetRecord> findAsset(String where, String argument) {
        List<AssetRecord> rows = jdbc.query(
                """
                SELECT id, external_code, name, category
                FROM asset
                """ + where,
                (rs, rowNum) -> new AssetRecord(
                        rs.getString("id"),
                        rs.getString("external_code"),
                        rs.getString("name"),
                        rs.getString("category")
                ),
                argument
        );
        return rows.stream().findFirst();
    }

    public void insertAsset(NewAssetWrite write) {
        jdbc.update(
                """
                INSERT INTO asset (
                    id, source_database, source_table, source_pk,
                    external_code, name, category, active
                ) VALUES (?, 'CORTEX_FINANCEIRO', 'finance_compra_item',
                          ?, ?, ?, ?, 1)
                """,
                write.id(),
                write.sourcePk(),
                write.externalCode(),
                write.name(),
                write.category()
        );
    }

    public void insertLink(PurchasedLinkWrite write) {
        jdbc.update(
                """
                INSERT INTO finance_compra_item_ativo (
                    id, compra_item_id, ativo_id, unidade_controle_id,
                    sequencia, criado_por
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                write.id(),
                write.itemId(),
                write.assetId(),
                write.unitId(),
                write.sequence(),
                write.actorId()
        );
    }

    public void insertHistory(PurchasedLinkHistoryWrite write) {
        jdbc.update(
                """
                INSERT INTO finance_compra_item_ativo_historico (
                    id, vinculo_id, client_mutation_id, operacao,
                    estado_anterior_json, estado_novo_json, alterado_por,
                    correlacao_id, dispositivo_id
                ) VALUES (?, ?, ?, 'VINCULAR', CAST(? AS JSON),
                          CAST(? AS JSON), ?, ?, ?)
                """,
                write.id(),
                write.linkId(),
                write.clientMutationId(),
                write.previousStateJson(),
                write.newStateJson(),
                write.actorId(),
                write.correlationId(),
                write.deviceId()
        );
    }

    public boolean incrementItemVersion(
            String itemId,
            long expectedVersion,
            String actorId
    ) {
        return jdbc.update(
                """
                UPDATE finance_compra_item
                SET versao_linha = versao_linha + 1,
                    atualizado_por = ?,
                    atualizado_em = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND versao_linha = ?
                  AND arquivado_em IS NULL
                """,
                actorId,
                itemId,
                expectedVersion
        ) == 1;
    }

    public List<PurchasedLinkRecord> findLinks(String itemId) {
        return links(itemId, false);
    }

    public List<PurchasedLinkRecord> findLinksCurrent(String itemId) {
        return links(itemId, true);
    }

    private List<PurchasedLinkRecord> links(
            String itemId,
            boolean currentRead
    ) {
        return jdbc.query(
                """
                SELECT l.id, l.ativo_id, l.unidade_controle_id, l.sequencia,
                       a.external_code, a.name, a.category,
                       l.criado_por, l.criado_em
                FROM finance_compra_item_ativo l
                JOIN asset a ON a.id = l.ativo_id
                WHERE l.compra_item_id = ?
                ORDER BY l.sequencia%s
                """.formatted(currentRead ? " FOR UPDATE" : ""),
                (rs, rowNum) -> new PurchasedLinkRecord(
                        rs.getString("id"),
                        rs.getString("ativo_id"),
                        rs.getString("unidade_controle_id"),
                        rs.getInt("sequencia"),
                        rs.getString("external_code"),
                        rs.getString("name"),
                        rs.getString("category"),
                        rs.getString("criado_por"),
                        rs.getTimestamp("criado_em").toLocalDateTime()
                ),
                itemId
        );
    }

    public List<PurchasedLinkHistoryRecord> history(String itemId) {
        return jdbc.query(
                """
                SELECT h.id, h.vinculo_id, h.client_mutation_id,
                       h.operacao, h.estado_anterior_json,
                       h.estado_novo_json, h.alterado_por, h.alterado_em,
                       h.correlacao_id, h.dispositivo_id
                FROM finance_compra_item_ativo_historico h
                JOIN finance_compra_item_ativo l ON l.id = h.vinculo_id
                WHERE l.compra_item_id = ?
                ORDER BY h.alterado_em, h.id
                """,
                (rs, rowNum) -> new PurchasedLinkHistoryRecord(
                        rs.getString("id"),
                        rs.getString("vinculo_id"),
                        rs.getString("client_mutation_id"),
                        rs.getString("operacao"),
                        rs.getString("estado_anterior_json"),
                        rs.getString("estado_novo_json"),
                        rs.getString("alterado_por"),
                        rs.getTimestamp("alterado_em").toLocalDateTime(),
                        rs.getString("correlacao_id"),
                        rs.getString("dispositivo_id")
                ),
                itemId
        );
    }

    public record PurchasedAssetMutation(String itemId, boolean completed) {
    }

    public record PurchasedItem(
            String purchaseId,
            String itemId,
            String worksiteId,
            String description,
            BigDecimal quantity,
            String nature,
            boolean archived,
            long version
    ) {
    }

    public record AssetRecord(
            String id,
            String externalCode,
            String name,
            String category
    ) {
    }

    public record NewAssetWrite(
            String id,
            String sourcePk,
            String externalCode,
            String name,
            String category
    ) {
    }

    public record PurchasedLinkWrite(
            String id,
            String itemId,
            String assetId,
            String unitId,
            int sequence,
            String actorId
    ) {
    }

    public record PurchasedLinkRecord(
            String id,
            String assetId,
            String unitId,
            int sequence,
            String externalCode,
            String name,
            String category,
            String createdBy,
            LocalDateTime createdAt
    ) {
    }

    public record PurchasedLinkHistoryWrite(
            String id,
            String linkId,
            String clientMutationId,
            String previousStateJson,
            String newStateJson,
            String actorId,
            String correlationId,
            String deviceId
    ) {
    }

    public record PurchasedLinkHistoryRecord(
            String id,
            String linkId,
            String clientMutationId,
            String operation,
            String previousStateJson,
            String newStateJson,
            String actorId,
            LocalDateTime at,
            String correlationId,
            String deviceId
    ) {
    }
}
