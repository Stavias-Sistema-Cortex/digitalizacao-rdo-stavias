package com.projeto.cortex.assets;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AssetImportController {

    private final AssetImportService assetImportService;

    public AssetImportController(AssetImportService assetImportService) {
        this.assetImportService = assetImportService;
    }

    @PostMapping("/api/assets/import/zld")
    public AssetImportResult importFromZld() {
        return assetImportService.importFromZldAtivos();
    }
}
