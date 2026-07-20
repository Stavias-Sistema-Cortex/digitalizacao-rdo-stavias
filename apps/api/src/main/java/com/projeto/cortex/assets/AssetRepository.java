package com.projeto.cortex.assets;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AssetRepository extends JpaRepository<Asset, String> {

    List<Asset> findByDeletedAtIsNullOrderByExternalCodeAsc();

    @Query("""
            SELECT a
            FROM Asset a
            WHERE a.deletedAt IS NULL
              AND (
                   LOWER(a.externalCode) LIKE LOWER(CONCAT('%', :query, '%'))
                OR LOWER(a.name) LIKE LOWER(CONCAT('%', :query, '%'))
                OR LOWER(a.category) LIKE LOWER(CONCAT('%', :query, '%'))
              )
            ORDER BY a.externalCode ASC
            """)
    List<Asset> searchActiveAssets(@Param("query") String query);
}
