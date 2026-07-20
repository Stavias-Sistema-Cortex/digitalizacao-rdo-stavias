package com.projeto.cortex.assets;

import com.projeto.cortex.auth.CurrentUserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class AssetController {

    private final AssetService assetService;
    private final CurrentUserService currentUserService;

    public AssetController(
            AssetService assetService,
            CurrentUserService currentUserService
    ) {
        this.assetService = assetService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/api/assets")
    public List<AssetResponse> listAssets(
            @RequestParam(required = false) String query
    ) {
        currentUserService.requireAdmin();
        return assetService.listAssets(query);
    }
}
