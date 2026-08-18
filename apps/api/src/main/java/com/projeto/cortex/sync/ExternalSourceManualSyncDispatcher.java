package com.projeto.cortex.sync;

import com.projeto.cortex.integracoes.IntegracaoActionResponse;
import com.projeto.cortex.integracoes.IntegracaoAdminService;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Removes external MySQL I/O from the canonical outbox transaction.
 *
 * <p>The automatic schedules remain the durability mechanism. A manual
 * request only accelerates the next source-specific run and is deliberately
 * executed on the same single-thread bulkhead as that source. Academy cannot
 * occupy Zeladoria's worker and neither source can hold an operational
 * {@code /sync/push} request open.
 */
@Component
public class ExternalSourceManualSyncDispatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            ExternalSourceManualSyncDispatcher.class
    );
    private static final Set<String> ACTIONS =
            Set.of("TESTAR", "SINCRONIZAR");

    private final IntegracaoAdminService service;
    private final TaskExecutor academyExecutor;
    private final TaskExecutor zeladoriaExecutor;
    private final AtomicBoolean academyBusy = new AtomicBoolean();
    private final AtomicBoolean zeladoriaBusy = new AtomicBoolean();

    public ExternalSourceManualSyncDispatcher(
            IntegracaoAdminService service,
            @Qualifier(
                    ExternalSourceSchedulingConfiguration.ACADEMY_SCHEDULER
            ) TaskExecutor academyExecutor,
            @Qualifier(
                    ExternalSourceSchedulingConfiguration.ZELADORIA_SCHEDULER
            ) TaskExecutor zeladoriaExecutor
    ) {
        this.service = service;
        this.academyExecutor = academyExecutor;
        this.zeladoriaExecutor = zeladoriaExecutor;
    }

    public IntegracaoActionResponse submit(
            String integrationId,
            String action
    ) {
        if (!Set.of("academy", "zeladoria").contains(integrationId)) {
            throw new IllegalArgumentException(
                    "Integracao desconhecida: " + integrationId
            );
        }
        if (!ACTIONS.contains(action)) {
            throw new IllegalArgumentException(
                    "Acao de integracao desconhecida: " + action
            );
        }

        AtomicBoolean busy = busy(integrationId);
        if (!busy.compareAndSet(false, true)) {
            return new IntegracaoActionResponse(
                    integrationId,
                    "SCHEDULED",
                    "A fonte já está com uma solicitação em andamento."
            );
        }
        try {
            executor(integrationId).execute(() -> {
                try {
                    executeSafely(integrationId, action);
                } finally {
                    busy.set(false);
                }
            });
        } catch (RuntimeException exception) {
            busy.set(false);
            throw exception;
        }
        return new IntegracaoActionResponse(
                integrationId,
                "SCHEDULED",
                "Solicitacao aceita na fila independente da fonte."
        );
    }

    private TaskExecutor executor(String integrationId) {
        return "academy".equals(integrationId)
                ? academyExecutor
                : zeladoriaExecutor;
    }

    private AtomicBoolean busy(String integrationId) {
        return "academy".equals(integrationId)
                ? academyBusy
                : zeladoriaBusy;
    }

    private void executeSafely(String integrationId, String action) {
        try {
            if ("TESTAR".equals(action)) {
                service.testConnection(integrationId);
            } else {
                service.startSync(integrationId);
            }
        } catch (Exception ignored) {
            LOGGER.error(
                    "Independent external-source request failed for {}.",
                    integrationId
            );
        }
    }
}
