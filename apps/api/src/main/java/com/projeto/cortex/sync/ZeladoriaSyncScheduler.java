package com.projeto.cortex.sync;

import com.projeto.cortex.assets.AssetImportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(
        prefix = "cortex.sync.zeladoria",
        name = "enabled",
        havingValue = "true"
)
public class ZeladoriaSyncScheduler {

    private static final Logger logger =
            LoggerFactory.getLogger(ZeladoriaSyncScheduler.class);

    private final AssetImportService assetImportService;

    public ZeladoriaSyncScheduler(AssetImportService assetImportService) {
        this.assetImportService = assetImportService;
    }

    @Scheduled(
            initialDelayString =
                    "${cortex.sync.zeladoria.initial-delay-ms:60000}",
            fixedDelayString =
                    "${cortex.sync.zeladoria.fixed-delay-ms:300000}"
    )
    public void sincronizarZeladoria() {
        try {
            logger.info("Starting automatic asset sync from ZLD.");
            assetImportService.importFromZldAtivos();
            logger.info("Finished automatic asset sync from ZLD.");
        } catch (Exception ignored) {
            logger.error("Automatic asset sync from ZLD failed.");
        }
    }
}
