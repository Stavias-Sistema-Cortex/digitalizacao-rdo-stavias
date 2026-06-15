package com.projeto.cortex.assets;

import java.time.LocalDateTime;

public record AssetResponse(
        String id,
        String externalCode,
        String name,
        String category,
        Boolean active,
        LocalDateTime updatedAt
) {
    public static AssetResponse from(Asset asset) {
        return new AssetResponse(
                asset.getId(),
                asset.getExternalCode(),
                asset.getName(),
                asset.getCategory(),
                asset.getActive(),
                asset.getUpdatedAt()
        );
    }
}
