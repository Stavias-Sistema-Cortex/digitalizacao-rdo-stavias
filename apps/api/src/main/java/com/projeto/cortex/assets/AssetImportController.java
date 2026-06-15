package com.projeto.cortex.assets;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    @GetMapping("/api/assets/import/runs")
    public List<SyncRunResponse> listRecentRuns(@RequestParam(defaultValue = "10") int limit) {
        return assetImportService.listRecentRuns(limit);
    }
}
