package com.projeto.cortex.integracoes;

import java.util.List;

public record ZeladoriaAssetSnapshot(
        List<ZeladoriaSourceAdapter.AtivoZeladoriaRecord> assets,
        boolean complete
) {

    public ZeladoriaAssetSnapshot {
        assets = assets == null ? List.of() : List.copyOf(assets);
    }

    public static ZeladoriaAssetSnapshot complete(
            List<ZeladoriaSourceAdapter.AtivoZeladoriaRecord> assets
    ) {
        return new ZeladoriaAssetSnapshot(assets, true);
    }
}
