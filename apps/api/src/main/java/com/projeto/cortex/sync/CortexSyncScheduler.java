package com.projeto.cortex.sync;

import com.projeto.cortex.assets.AssetImportService;
import com.projeto.cortex.colaboradores.ColaboradorImportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "cortex.sync", name = "enabled", havingValue = "true")
public class CortexSyncScheduler {

    private static final Logger logger = LoggerFactory.getLogger(CortexSyncScheduler.class);

    private final AssetImportService assetImportService;
    private final ColaboradorImportService colaboradorImportService;

    public CortexSyncScheduler(
            AssetImportService assetImportService,
            ColaboradorImportService colaboradorImportService
    ) {
        this.assetImportService = assetImportService;
        this.colaboradorImportService = colaboradorImportService;
    }

    @Scheduled(
            initialDelayString = "${cortex.sync.initial-delay-ms:60000}",
            fixedDelayString = "${cortex.sync.fixed-delay-ms:300000}"
    )
    public void sincronizarFontesExternas() {
        logger.info("Starting automatic Córtex sync.");

        sincronizarAtivos();
        sincronizarColaboradores();

        logger.info("Finished automatic Córtex sync.");
    }

    private void sincronizarAtivos() {
        try {
            logger.info("Starting automatic asset sync from ZLD.");
            assetImportService.importFromZldAtivos();
            logger.info("Finished automatic asset sync from ZLD.");
        } catch (Exception exception) {
            logger.error("Automatic asset sync from ZLD failed.", exception);
        }
    }

    private void sincronizarColaboradores() {
        try {
            logger.info("Starting automatic collaborator sync from Academy.");
            colaboradorImportService.importarUsuariosDaAcademy();
            logger.info("Finished automatic collaborator sync from Academy.");
        } catch (Exception exception) {
            logger.error("Automatic collaborator sync from Academy failed.", exception);
        }
    }
}
