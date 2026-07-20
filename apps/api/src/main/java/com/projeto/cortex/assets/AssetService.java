package com.projeto.cortex.assets;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AssetService {

    private static final int MAX_LOOKUP_RESULTS = 50;

    private final AssetRepository assetRepository;

    public AssetService(AssetRepository assetRepository) {
        this.assetRepository = assetRepository;
    }

    public List<AssetResponse> listAssets(String query) {
        List<Asset> assets;

        if (query == null || query.isBlank()) {
            assets = assetRepository.findByDeletedAtIsNullOrderByExternalCodeAsc();
        } else {
            assets = assetRepository.searchActiveAssets(query.trim());
        }

        return assets.stream()
                .limit(MAX_LOOKUP_RESULTS)
                .map(AssetResponse::from)
                .toList();
    }
}
