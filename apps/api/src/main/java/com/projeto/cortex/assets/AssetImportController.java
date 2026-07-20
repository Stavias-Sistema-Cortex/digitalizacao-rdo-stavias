package com.projeto.cortex.assets;

import com.projeto.cortex.auth.CurrentUserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
public class AssetImportController {

    private final AssetImportService assetImportService;
    private final CurrentUserService currentUserService;

    @Value("${cortex.import.enabled:false}")
    private boolean importEnabled;

    public AssetImportController(
            AssetImportService assetImportService,
            CurrentUserService currentUserService
    ) {
        this.assetImportService = assetImportService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/api/assets/import/zld")
    public AssetImportResult importFromZld() {
        currentUserService.requireAdmin();
        if (!importEnabled) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Asset import is disabled. Set CORTEX_IMPORT_ENABLED=true to enable it."
            );
        }

        return assetImportService.importFromZldAtivos();
    }

    @GetMapping("/api/assets/import/runs")
    public List<SyncRunResponse> listRecentRuns(@RequestParam(defaultValue = "10") int limit) {
        currentUserService.requireAdmin();
        return assetImportService.listRecentRuns(limit);
    }
}
