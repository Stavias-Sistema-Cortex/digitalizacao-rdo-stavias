package com.projeto.cortex.auth;

import com.projeto.cortex.common.SecurityRuntimeMode;
import com.projeto.cortex.common.RuntimeReadiness;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Refuses an authenticated production cutover without a recoverable ALFA. */
@Component
@Profile("!postgresql-common")
public final class AuthReadinessIndicator implements InitializingBean, RuntimeReadiness {

    private static final String VERIFIED_ACTIVE_ALFA_COUNT = """
            SELECT COUNT(*)
            FROM colaborador c
            JOIN auth_identity ai ON ai.colaborador_id = c.id
            WHERE c.ativo = TRUE
              AND c.deletado_em IS NULL
              AND c.papel_acesso = 'ALFA'
              AND ai.status = 'ATIVA'
              AND ai.email_verificado_em IS NOT NULL
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Environment environment;

    public AuthReadinessIndicator(
            JdbcTemplate jdbcTemplate,
            Environment environment
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        verifyProductionReadiness();
    }

    void verifyProductionReadiness() {
        if (SecurityRuntimeMode.isLocalOrTestOnly(environment)) {
            return;
        }
        Integer activeAlfas = jdbcTemplate.queryForObject(
                VERIFIED_ACTIVE_ALFA_COUNT,
                Integer.class
        );
        if (activeAlfas == null || activeAlfas < 1) {
            throw new IllegalStateException(
                    "Produção exige ao menos um ALFA ativo, autenticável e "
                            + "com e-mail verificado."
            );
        }
    }

    @Override
    public void verifyRuntimeReadiness() {
        Integer databaseReady = jdbcTemplate.queryForObject(
                "SELECT 1",
                Integer.class
        );
        if (databaseReady == null || databaseReady != 1) {
            throw new IllegalStateException(
                    "Banco de dados indisponível para readiness."
            );
        }
        verifyProductionReadiness();
    }
}
