package com.projeto.cortex.postgresql.bootstrap;

import com.projeto.cortex.auth.bootstrap.AcademyBootstrapLookup;
import com.projeto.cortex.auth.bootstrap.BootstrapAdminProperties;
import com.projeto.cortex.auth.bootstrap.BootstrapCpfSecretReader;
import com.projeto.cortex.auth.bootstrap.PostgresqlBootstrapMemoryAuditRepository;
import com.projeto.cortex.auth.bootstrap.PostgresqlBootstrapProfileGuard;
import com.projeto.cortex.auth.bootstrap.PostgresqlInitialAlfaBootstrapRepository;
import com.projeto.cortex.auth.bootstrap.PostgresqlInitialAlfaBootstrapRunner;
import com.projeto.cortex.auth.bootstrap.PostgresqlInitialAlfaBootstrapService;
import com.projeto.cortex.auth.identity.CpfLookupDigestConfiguration;
import com.projeto.cortex.config.PostgresqlModeConfigurationGuard;
import com.projeto.cortex.config.PostgresqlSchemaReadinessGuard;
import com.projeto.cortex.integracoes.AcademySourceAdapter;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Import;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Isolated non-web launcher for the PostgreSQL initial-ALFA bootstrap.
 */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration(exclude = {
        HibernateJpaAutoConfiguration.class,
        JpaRepositoriesAutoConfiguration.class
})
@EnableConfigurationProperties(BootstrapAdminProperties.class)
@Import({
        PostgresqlModeConfigurationGuard.class,
        PostgresqlSchemaReadinessGuard.class,
        PostgresqlBootstrapProfileGuard.class,
        CpfLookupDigestConfiguration.class,
        AcademySourceAdapter.class,
        BootstrapCpfSecretReader.class,
        AcademyBootstrapLookup.class,
        PostgresqlBootstrapMemoryAuditRepository.class,
        PostgresqlInitialAlfaBootstrapRepository.class,
        PostgresqlInitialAlfaBootstrapService.class,
        PostgresqlInitialAlfaBootstrapRunner.class
})
public class PostgresqlBootstrapApplication {

    private PostgresqlBootstrapApplication() {
    }

    public static void main(String[] args) {
        new SpringApplicationBuilder(PostgresqlBootstrapApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }
}
