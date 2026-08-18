package com.projeto.cortex.integracoes;

import java.nio.file.Path;
import java.security.KeyStore;

/** Fail-closed TLS policy for the production-only Academy source. */
final class AcademyProductionTlsPolicy {

    private static final String PINNED_TRUST_STORE_URL =
            "file:/etc/secrets/cortex-academy-truststore.p12";
    private static final Path PINNED_TRUST_STORE_PATH = Path.of(
            "/etc/secrets/cortex-academy-truststore.p12"
    );
    private static final String REDACTED_FAILURE =
            "Configuracao TLS de producao da Academy invalida.";

    private final PinnedMysqlSourceTlsPolicy delegate;

    AcademyProductionTlsPolicy() {
        this(
                Path.of("").toAbsolutePath().normalize(),
                PINNED_TRUST_STORE_PATH,
                null
        );
    }

    AcademyProductionTlsPolicy(
            Path repositoryRoot,
            Path trustStorePath
    ) {
        this(repositoryRoot, trustStorePath, null);
    }

    AcademyProductionTlsPolicy(
            Path repositoryRoot,
            Path trustStorePath,
            KeyStoreLoader keyStoreLoader
    ) {
        PinnedMysqlSourceTlsPolicy.KeyStoreLoader loader =
                keyStoreLoader == null
                        ? null
                        : keyStoreLoader::load;
        delegate = loader == null
                ? new PinnedMysqlSourceTlsPolicy(
                        repositoryRoot,
                        trustStorePath,
                        PINNED_TRUST_STORE_URL,
                        REDACTED_FAILURE
                )
                : new PinnedMysqlSourceTlsPolicy(
                        repositoryRoot,
                        trustStorePath,
                        PINNED_TRUST_STORE_URL,
                        REDACTED_FAILURE,
                        loader
                );
    }

    void validate(String jdbcUrl) {
        delegate.validate(jdbcUrl);
    }

    @FunctionalInterface
    interface KeyStoreLoader {

        KeyStore load(Path path, char[] password) throws Exception;
    }
}
