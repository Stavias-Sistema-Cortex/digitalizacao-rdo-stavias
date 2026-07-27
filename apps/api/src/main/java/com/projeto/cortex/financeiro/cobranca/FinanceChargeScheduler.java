package com.projeto.cortex.financeiro.cobranca;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("legacy-finance")
@ConditionalOnProperty(
        prefix = "cortex.finance.email.scheduler",
        name = "enabled",
        havingValue = "true"
)
public final class FinanceChargeScheduler {

    private final FinanceChargeDeliveryService delivery;
    private final FinanceAutomaticChargeService automaticCharges;
    private final int batchSize;

    public FinanceChargeScheduler(
            FinanceChargeDeliveryService delivery,
            FinanceAutomaticChargeService automaticCharges,
            @Value("${cortex.finance.email.scheduler.batch-size:25}")
            int batchSize
    ) {
        this.delivery = delivery;
        this.automaticCharges = automaticCharges;
        this.batchSize = Math.max(1, Math.min(batchSize, 100));
    }

    @Scheduled(
            initialDelayString =
                    "${cortex.finance.email.scheduler.initial-delay-ms:30000}",
            fixedDelayString =
                    "${cortex.finance.email.scheduler.fixed-delay-ms:60000}"
    )
    public void dispatchDueCharges() {
        automaticCharges.generate(batchSize);
        delivery.dispatchDue(batchSize);
    }
}
