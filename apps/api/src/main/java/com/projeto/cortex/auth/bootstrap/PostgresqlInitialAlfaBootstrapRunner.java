package com.projeto.cortex.auth.bootstrap;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** One-shot runner deliberately available only in the isolated bootstrap mode. */
@Component
@Profile("postgresql-bootstrap")
public final class PostgresqlInitialAlfaBootstrapRunner implements ApplicationRunner {

    private final PostgresqlInitialAlfaBootstrapService bootstrapService;

    public PostgresqlInitialAlfaBootstrapRunner(
            PostgresqlInitialAlfaBootstrapService bootstrapService
    ) {
        this.bootstrapService = bootstrapService;
    }

    @Override
    public void run(ApplicationArguments args) {
        bootstrapService.bootstrap();
    }
}
