package com.projeto.cortex.integracoes;

import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Fail-closed TLS policy for the production-only Academy JDBC source.
 *
 * <p>The package-private test seam changes only the file inspected on disk.
 * The JDBC URL must always name the fixed production secret path.</p>
 */
final class AcademyProductionTlsPolicy {

    private static final String MYSQL_JDBC_PREFIX = "jdbc:mysql:";
    private static final String VERIFY_IDENTITY = "VERIFY_IDENTITY";
    private static final String VERIFY_CA = "VERIFY_CA";
    private static final String PKCS12 = "PKCS12";
    private static final String FALSE = "false";
    private static final String SSL_MODE = "sslmode";
    private static final String TRUST_STORE_URL =
            "trustcertificatekeystoreurl";
    private static final String TRUST_STORE_TYPE =
            "trustcertificatekeystoretype";
    private static final String TRUST_STORE_PASSWORD =
            "trustcertificatekeystorepassword";
    private static final String SYSTEM_TRUST_STORE_FALLBACK =
            "fallbacktosystemtruststore";
    private static final String PINNED_TRUST_STORE_URL =
            "file:/etc/secrets/cortex-academy-truststore.p12";
    private static final Path PINNED_TRUST_STORE_PATH = Path.of(
            "/etc/secrets/cortex-academy-truststore.p12"
    );
    private static final String REDACTED_FAILURE =
            "Configuracao TLS de producao da Academy invalida.";

    private final Path repositoryRoot;
    private final Path trustStorePath;
    private final KeyStoreLoader keyStoreLoader;

    AcademyProductionTlsPolicy() {
        this(
                Path.of("").toAbsolutePath().normalize(),
                PINNED_TRUST_STORE_PATH,
                AcademyProductionTlsPolicy::loadPkcs12
        );
    }

    AcademyProductionTlsPolicy(
            Path repositoryRoot,
            Path trustStorePath
    ) {
        this(
                repositoryRoot,
                trustStorePath,
                AcademyProductionTlsPolicy::loadPkcs12
        );
    }

    AcademyProductionTlsPolicy(
            Path repositoryRoot,
            Path trustStorePath,
            KeyStoreLoader keyStoreLoader
    ) {
        this.repositoryRoot = repositoryRoot
                .toAbsolutePath()
                .normalize();
        this.trustStorePath = trustStorePath
                .toAbsolutePath()
                .normalize();
        this.keyStoreLoader = keyStoreLoader;
    }

    void validate(String jdbcUrl) {
        try {
            validateUnredacted(jdbcUrl);
        } catch (Exception ignored) {
            throw failure();
        }
    }

    private void validateUnredacted(String jdbcUrl) throws Exception {
        Map<String, String> properties = decodedUniqueQuery(jdbcUrl);
        String sslMode = properties.get(SSL_MODE);

        if (VERIFY_IDENTITY.equalsIgnoreCase(sslMode)
                && !hasCustomTrustStoreProperty(properties)) {
            return;
        }
        if (!VERIFY_CA.equalsIgnoreCase(sslMode)) {
            throw failure();
        }
        if (!PINNED_TRUST_STORE_URL.equals(
                properties.get(TRUST_STORE_URL)
        )) {
            throw failure();
        }
        if (!PKCS12.equalsIgnoreCase(
                properties.get(TRUST_STORE_TYPE)
        )) {
            throw failure();
        }
        if (!FALSE.equalsIgnoreCase(
                properties.get(SYSTEM_TRUST_STORE_FALLBACK)
        )) {
            throw failure();
        }

        String decodedPassword = properties.remove(TRUST_STORE_PASSWORD);
        char[] password = decodedPassword == null
                ? null
                : decodedPassword.toCharArray();
        decodedPassword = null;
        try {
            validatePinnedTrustStore(password);
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }

    private boolean hasCustomTrustStoreProperty(
            Map<String, String> properties
    ) {
        return properties.containsKey(TRUST_STORE_URL)
                || properties.containsKey(TRUST_STORE_TYPE)
                || properties.containsKey(TRUST_STORE_PASSWORD)
                || properties.containsKey(SYSTEM_TRUST_STORE_FALLBACK);
    }

    private Map<String, String> decodedUniqueQuery(String jdbcUrl) {
        if (jdbcUrl == null
                || jdbcUrl.isBlank()
                || !jdbcUrl.regionMatches(
                        true,
                        0,
                        MYSQL_JDBC_PREFIX,
                        0,
                        MYSQL_JDBC_PREFIX.length()
                )) {
            throw failure();
        }

        int queryStart = jdbcUrl.indexOf('?');
        if (queryStart < 0 || queryStart == jdbcUrl.length() - 1) {
            throw failure();
        }

        Map<String, String> properties = new LinkedHashMap<>();
        String query = jdbcUrl.substring(queryStart + 1);
        for (String parameter : query.split("&", -1)) {
            int separator = parameter.indexOf('=');
            if (separator <= 0) {
                throw failure();
            }
            String rawName = parameter
                    .substring(0, separator)
                    .strip();
            String rawValue = parameter
                    .substring(separator + 1)
                    .strip();
            String decodedName = URLDecoder.decode(
                    rawName,
                    StandardCharsets.UTF_8
            );
            String decodedValue = URLDecoder.decode(
                    rawValue,
                    StandardCharsets.UTF_8
            );
            if (decodedName.isEmpty()) {
                throw failure();
            }

            String normalizedName = decodedName.toLowerCase(Locale.ROOT);
            if (properties.putIfAbsent(
                    normalizedName,
                    decodedValue
            ) != null) {
                throw failure();
            }
        }
        return properties;
    }

    private void validatePinnedTrustStore(char[] password) throws Exception {
        if (trustStorePath.startsWith(repositoryRoot)
                || Files.isSymbolicLink(trustStorePath)
                || !Files.isRegularFile(
                        trustStorePath,
                        LinkOption.NOFOLLOW_LINKS
                )
                || !Files.isReadable(trustStorePath)) {
            throw failure();
        }

        KeyStore trustStore = keyStoreLoader.load(
                trustStorePath,
                password
        );
        if (trustStore.size() != 1) {
            throw failure();
        }

        var aliases = trustStore.aliases();
        if (!aliases.hasMoreElements()) {
            throw failure();
        }
        String alias = aliases.nextElement();
        if (aliases.hasMoreElements()
                || !trustStore.isCertificateEntry(alias)) {
            throw failure();
        }

        Certificate certificate = trustStore.getCertificate(alias);
        if (!(certificate instanceof X509Certificate x509Certificate)
                || x509Certificate.getBasicConstraints() != -1) {
            throw failure();
        }
    }

    private static KeyStore loadPkcs12(
            Path path,
            char[] password
    ) throws Exception {
        KeyStore trustStore = KeyStore.getInstance(PKCS12);
        Set<OpenOption> options = Set.of(
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS
        );
        try (
                SeekableByteChannel channel = Files.newByteChannel(
                        path,
                        options
                );
                InputStream input = Channels.newInputStream(channel)
        ) {
            trustStore.load(input, password);
        }
        return trustStore;
    }

    private static IllegalStateException failure() {
        return new IllegalStateException(REDACTED_FAILURE);
    }

    @FunctionalInterface
    interface KeyStoreLoader {

        KeyStore load(Path path, char[] password) throws Exception;
    }
}
