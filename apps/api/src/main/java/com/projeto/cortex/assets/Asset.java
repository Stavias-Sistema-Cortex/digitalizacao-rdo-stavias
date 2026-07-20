package com.projeto.cortex.assets;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "asset")
public class Asset {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "source_database", nullable = false, length = 64)
    private String sourceDatabase;

    @Column(name = "source_table", nullable = false, length = 64)
    private String sourceTable;

    @Column(name = "source_pk", nullable = false, length = 128)
    private String sourcePk;

    @Column(name = "external_code", length = 120)
    private String externalCode;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "category", length = 120)
    private String category;

    @Column(name = "active", nullable = false)
    private Boolean active;

    @Column(name = "source_updated_at")
    private LocalDateTime sourceUpdatedAt;

    @Column(name = "source_hash", length = 64)
    private String sourceHash;

    @Column(name = "first_imported_at")
    private LocalDateTime firstImportedAt;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "row_version")
    private Long rowVersion;

    protected Asset() {
    }

    public String getId() {
        return id;
    }

    public String getExternalCode() {
        return externalCode;
    }

    public String getName() {
        return name;
    }

    public String getCategory() {
        return category;
    }

    public Boolean getActive() {
        return active;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
