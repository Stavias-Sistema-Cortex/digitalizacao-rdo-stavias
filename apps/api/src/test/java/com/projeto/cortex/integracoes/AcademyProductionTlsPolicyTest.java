package com.projeto.cortex.integracoes;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AcademyProductionTlsPolicyTest {

    private static final String JDBC_PREFIX =
            "jdbc:mysql://academy.test.invalid:3306/academy?";
    private static final String PINNED_URL =
            "file:/etc/secrets/cortex-academy-truststore.p12";
    private static final String REDACTED_FAILURE =
            "Configuracao TLS de producao da Academy invalida.";
    private static final char[] STORE_PASSWORD =
            "pin+ pass&=%".toCharArray();
    private static final String ENCODED_STORE_PASSWORD =
            "pin%2B+pass%26%3D%25";

    @TempDir
    Path tempDir;

    private Path repositoryRoot;
    private Path trustStorePath;
    private AcademyProductionTlsPolicy policy;

    @BeforeEach
    void setUp() throws Exception {
        repositoryRoot = Files.createDirectories(
                tempDir.resolve("repository")
        );
        Path secretRoot = Files.createDirectories(
                tempDir.resolve("external-secrets")
        );
        trustStorePath = secretRoot.resolve(
                "cortex-academy-truststore.p12"
        );
        policy = new AcademyProductionTlsPolicy(
                repositoryRoot,
                trustStorePath
        );
    }

    @Test
    void verifyIdentityAcceptsDecodedCaseInsensitiveUniqueSslMode() {
        assertThatCode(() -> policy.validate(
                JDBC_PREFIX
                        + "connectTimeout=10000"
                        + "&sSl%4Dode=verify_identity"
        )).doesNotThrowAnyException();
    }

    @Test
    void verifyIdentityRejectsDecodedCaseInsensitiveDuplicateKeys() {
        assertRedactedFailure(
                JDBC_PREFIX
                        + "sslMode=VERIFY_IDENTITY"
                        + "&sSl%4Dode=VERIFY_IDENTITY"
        );
    }

    @Test
    void verifyIdentityRejectsDuplicateUnrelatedQueryKeys() {
        assertRedactedFailure(
                JDBC_PREFIX
                        + "connectTimeout=10000"
                        + "&CONNECTTIMEOUT=20000"
                        + "&sslMode=VERIFY_IDENTITY"
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "trustCertificateKeyStoreUrl=file:/tmp/alternate.p12",
            "trustCertificateKeyStoreType=PKCS12",
            "trustCertificateKeyStorePassword=sensitive",
            "fallbackToSystemTrustStore=false"
    })
    void verifyIdentityRejectsEveryCustomTrustStoreProperty(
            String customTrustProperty
    ) {
        assertRedactedFailure(
                JDBC_PREFIX
                        + "sslMode=VERIFY_IDENTITY"
                        + "&" + customTrustProperty
        );
    }

    @Test
    void rejectsNonMysqlAndNonVerifyingTlsModes() {
        assertRedactedFailure(
                "jdbc:mariadb://academy.test.invalid/academy"
                        + "?sslMode=VERIFY_IDENTITY"
        );
        assertRedactedFailure(JDBC_PREFIX + "sslMode=REQUIRED");
        assertRedactedFailure(JDBC_PREFIX + "sslMode=PREFERRED");
        assertRedactedFailure(JDBC_PREFIX + "sslMode=DISABLED");
    }

    @Test
    void verifyCaAcceptsExactlyOneTrustedLeafWithOptionalEncodedPassword()
            throws Exception {
        writeOpaqueRegularFile(trustStorePath);
        policy = policyWithStore(
                validLeafStore(),
                STORE_PASSWORD
        );

        assertThatCode(() -> policy.validate(
                verifyCaUrl(
                        "&trustCertificateKeyStorePassword="
                                + ENCODED_STORE_PASSWORD
                )
        )).doesNotThrowAnyException();
    }

    @Test
    void verifyCaAcceptsAnUnprotectedSingleLeafStore() throws Exception {
        writeOpaqueRegularFile(trustStorePath);
        policy = policyWithStore(validLeafStore(), null);

        assertThatCode(() -> policy.validate(
                verifyCaUrl("")
        )).doesNotThrowAnyException();
    }

    @Test
    void verifyCaRequiresTheExactFixedFileUrl() throws Exception {
        writeOpaqueRegularFile(trustStorePath);
        policy = policyWithStore(validLeafStore(), STORE_PASSWORD);

        assertRedactedFailure(
                JDBC_PREFIX
                        + "sslMode=VERIFY_CA"
                        + "&trustCertificateKeyStoreUrl=file:/tmp/"
                        + "cortex-academy-truststore.p12"
                        + "&trustCertificateKeyStoreType=PKCS12"
                        + "&fallbackToSystemTrustStore=false"
                        + "&trustCertificateKeyStorePassword="
                        + ENCODED_STORE_PASSWORD
        );
    }

    @Test
    void verifyCaRequiresPkcs12AndDisablesSystemFallback() throws Exception {
        writeOpaqueRegularFile(trustStorePath);
        policy = policyWithStore(validLeafStore(), null);

        assertRedactedFailure(
                verifyCaUrl("")
                        .replace(
                                "trustCertificateKeyStoreType=PKCS12",
                                "trustCertificateKeyStoreType=JKS"
                        )
        );
        assertRedactedFailure(
                verifyCaUrl("")
                        .replace(
                                "fallbackToSystemTrustStore=false",
                                "fallbackToSystemTrustStore=true"
                        )
        );
        assertRedactedFailure(
                JDBC_PREFIX
                        + "sslMode=VERIFY_CA"
                        + "&trustCertificateKeyStoreUrl=" + PINNED_URL
                        + "&trustCertificateKeyStoreType=PKCS12"
        );
    }

    @Test
    void verifyCaRejectsDuplicateSecurityPropertiesAfterDecoding()
            throws Exception {
        writeOpaqueRegularFile(trustStorePath);
        policy = policyWithStore(validLeafStore(), STORE_PASSWORD);

        assertRedactedFailure(
                verifyCaUrl(
                        "&trustCertificateKeyStorePassword="
                                + ENCODED_STORE_PASSWORD
                                + "&trustCertificateKeyStorePass%77ord="
                                + ENCODED_STORE_PASSWORD
                )
        );
    }

    @Test
    void verifyCaRejectsMissingUnreadableAndSymbolicLinkFiles()
            throws Exception {
        assertRedactedFailure(verifyCaUrl(""));

        Path realStore = tempDir.resolve("real-store.p12");
        writeOpaqueRegularFile(realStore);
        Files.createSymbolicLink(trustStorePath, realStore);
        assertRedactedFailure(verifyCaUrl(""));
    }

    @Test
    void verifyCaRejectsUnreadableRegularFileWhenPosixPermissionsApply()
            throws Exception {
        writeOpaqueRegularFile(trustStorePath);
        if (!Files.getFileStore(trustStorePath)
                .supportsFileAttributeView("posix")) {
            return;
        }
        Files.setPosixFilePermissions(
                trustStorePath,
                Set.of()
        );
        if (Files.isReadable(trustStorePath)) {
            return;
        }

        assertRedactedFailure(verifyCaUrl(""));
    }

    @Test
    void verifyCaRejectsAStoreInsideTheRepositoryRoot() throws Exception {
        Path repositoryStore = repositoryRoot.resolve(
                "cortex-academy-truststore.p12"
        );
        writeOpaqueRegularFile(repositoryStore);
        AcademyProductionTlsPolicy repositoryPolicy =
                new AcademyProductionTlsPolicy(
                        repositoryRoot,
                        repositoryStore,
                        (path, password) -> validLeafStore()
                );

        assertRedactedFailure(repositoryPolicy, verifyCaUrl(""));
    }

    @Test
    void verifyCaRejectsEmptyAndMultipleEntryStores() throws Exception {
        writeOpaqueRegularFile(trustStorePath);
        policy = policyWithStore(storeWithSize(0), null);
        assertRedactedFailure(verifyCaUrl(""));

        policy = policyWithStore(storeWithSize(2), null);
        assertRedactedFailure(verifyCaUrl(""));
    }

    @Test
    void verifyCaRejectsKeyEntriesAndCaCertificates() throws Exception {
        writeOpaqueRegularFile(trustStorePath);
        KeyStore keyEntryStore = singleEntryStore(false, null);
        policy = policyWithStore(keyEntryStore, null);
        assertRedactedFailure(verifyCaUrl(""));

        X509Certificate ca = mock(X509Certificate.class);
        when(ca.getBasicConstraints()).thenReturn(0);
        policy = policyWithStore(singleEntryStore(true, ca), null);
        assertRedactedFailure(verifyCaUrl(""));
    }

    @Test
    void verifyCaRedactsMalformedStoreAndPasswordFromFailures()
            throws Exception {
        writeOpaqueRegularFile(trustStorePath);
        policy = new AcademyProductionTlsPolicy(
                repositoryRoot,
                trustStorePath,
                (path, password) -> {
                    throw new IllegalStateException(
                            "driver detail: pin+ pass&=% " + path
                    );
                }
        );
        String sensitiveUrl = verifyCaUrl(
                "&trustCertificateKeyStorePassword="
                        + ENCODED_STORE_PASSWORD
        );

        assertThatThrownBy(() -> policy.validate(sensitiveUrl))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(REDACTED_FAILURE)
                .hasMessageNotContaining("pin+ pass&=%")
                .hasMessageNotContaining(ENCODED_STORE_PASSWORD)
                .hasMessageNotContaining(trustStorePath.toString())
                .hasNoCause();
    }

    @Test
    void verifyCaRejectsANonX509TrustedCertificateEntry()
            throws Exception {
        writeOpaqueRegularFile(trustStorePath);
        java.security.cert.Certificate nonX509 =
                mock(java.security.cert.Certificate.class);
        policy = policyWithStore(
                singleEntryStore(true, nonX509),
                null
        );

        assertRedactedFailure(verifyCaUrl(""));
    }

    private String verifyCaUrl(String suffix) {
        return JDBC_PREFIX
                + "sslMode=VERIFY_CA"
                + "&trustCertificateKeyStoreUrl=" + PINNED_URL
                + "&trustCertificateKeyStoreType=PKCS12"
                + "&fallbackToSystemTrustStore=false"
                + suffix;
    }

    private void assertRedactedFailure(String jdbcUrl) {
        assertRedactedFailure(policy, jdbcUrl);
    }

    private void assertRedactedFailure(
            AcademyProductionTlsPolicy targetPolicy,
            String jdbcUrl
    ) {
        assertThatThrownBy(() -> targetPolicy.validate(jdbcUrl))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(REDACTED_FAILURE)
                .hasMessageNotContaining(jdbcUrl)
                .hasMessageNotContaining(PINNED_URL)
                .hasNoCause();
    }

    private AcademyProductionTlsPolicy policyWithStore(
            KeyStore store,
            char[] expectedPassword
    ) {
        return new AcademyProductionTlsPolicy(
                repositoryRoot,
                trustStorePath,
                (path, password) -> {
                    if (!Arrays.equals(expectedPassword, password)) {
                        throw new IllegalStateException(
                                "Unexpected test password."
                        );
                    }
                    return store;
                }
        );
    }

    private KeyStore validLeafStore() throws Exception {
        X509Certificate leaf = mock(X509Certificate.class);
        when(leaf.getBasicConstraints()).thenReturn(-1);
        return singleEntryStore(true, leaf);
    }

    private KeyStore storeWithSize(int size) throws Exception {
        KeyStore store = mock(KeyStore.class);
        when(store.size()).thenReturn(size);
        return store;
    }

    private KeyStore singleEntryStore(
            boolean certificateEntry,
            java.security.cert.Certificate certificate
    ) throws Exception {
        KeyStore store = mock(KeyStore.class);
        when(store.size()).thenReturn(1);
        when(store.aliases()).thenReturn(
                Collections.enumeration(
                        java.util.List.of("academy-leaf")
                )
        );
        when(store.isCertificateEntry("academy-leaf"))
                .thenReturn(certificateEntry);
        when(store.getCertificate("academy-leaf"))
                .thenReturn(certificate);
        return store;
    }

    private void writeOpaqueRegularFile(Path path) throws Exception {
        Files.writeString(path, "opaque test store");
    }
}
