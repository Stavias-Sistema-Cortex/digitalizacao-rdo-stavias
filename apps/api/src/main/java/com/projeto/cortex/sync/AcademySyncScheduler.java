package com.projeto.cortex.sync;

import com.projeto.cortex.colaboradores.ColaboradorImportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(
        prefix = "cortex.sync.academy",
        name = "enabled",
        havingValue = "true"
)
public class AcademySyncScheduler {

    private static final Logger logger =
            LoggerFactory.getLogger(AcademySyncScheduler.class);

    private final ColaboradorImportService colaboradorImportService;

    public AcademySyncScheduler(
            ColaboradorImportService colaboradorImportService
    ) {
        this.colaboradorImportService = colaboradorImportService;
    }

    @Scheduled(
            initialDelayString =
                    "${cortex.sync.academy.initial-delay-ms:60000}",
            fixedDelayString =
                    "${cortex.sync.academy.fixed-delay-ms:300000}"
    )
    public void sincronizarAcademy() {
        try {
            logger.info(
                    "Starting automatic collaborator sync from Academy."
            );
            colaboradorImportService.importarUsuariosDaAcademy();
            logger.info(
                    "Finished automatic collaborator sync from Academy."
            );
        } catch (Exception ignored) {
            logger.error(
                    "Automatic collaborator sync from Academy failed."
            );
        }
    }
}
