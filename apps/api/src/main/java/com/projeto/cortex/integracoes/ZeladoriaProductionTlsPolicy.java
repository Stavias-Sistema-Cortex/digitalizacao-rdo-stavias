package com.projeto.cortex.integracoes;

import java.nio.file.Path;

final class ZeladoriaProductionTlsPolicy {

    private static final String PINNED_TRUST_STORE_URL =
            "file:/etc/secrets/cortex-zeladoria-truststore.p12";
    private static final Path PINNED_TRUST_STORE_PATH = Path.of(
            "/etc/secrets/cortex-zeladoria-truststore.p12"
    );
    private static final String REDACTED_FAILURE =
            "Configuracao TLS de producao da Zeladoria invalida.";

    private final PinnedMysqlSourceTlsPolicy delegate;

    ZeladoriaProductionTlsPolicy() {
        this(
                Path.of("").toAbsolutePath().normalize(),
                PINNED_TRUST_STORE_PATH
        );
    }

    ZeladoriaProductionTlsPolicy(
            Path repositoryRoot,
            Path trustStorePath
    ) {
        delegate = new PinnedMysqlSourceTlsPolicy(
                repositoryRoot,
                trustStorePath,
                PINNED_TRUST_STORE_URL,
                REDACTED_FAILURE
        );
    }

    void validate(String jdbcUrl) {
        delegate.validate(jdbcUrl);
    }
}
