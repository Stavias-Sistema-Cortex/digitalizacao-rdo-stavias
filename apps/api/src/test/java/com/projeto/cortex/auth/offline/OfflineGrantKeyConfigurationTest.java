package com.projeto.cortex.auth.offline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

class OfflineGrantKeyConfigurationTest {

    @TempDir
    Path tempDir;

    @Test
    void keylessLocalAndTestProfilesUseEphemeralRsaKeysWithoutSecretFiles()
            throws Exception {
        OfflineGrantProperties properties = new OfflineGrantProperties();
        OfflineGrantKeyConfiguration configuration =
                new OfflineGrantKeyConfiguration();

        assertEphemeral(configuration.offlineGrantSigningKey(
                properties,
                profiles("local", "postgresql")
        ));
        assertEphemeral(configuration.offlineGrantSigningKey(
                properties,
                profiles("test")
        ));
    }

    @Test
    void localPostgresqlLoadsConfiguredMountedKeyPairAndFingerprint()
            throws Exception {
        KeyPair pair = rsaKeyPair();
        OfflineGrantProperties properties = properties(
                "local-postgresql-key-v1",
                writePem("local-private.pem", "PRIVATE KEY",
                        pair.getPrivate().getEncoded()),
                writePem("local-public.pem", "PUBLIC KEY",
                        pair.getPublic().getEncoded())
        );

        OfflineGrantSigningKey loaded = new OfflineGrantKeyConfiguration()
                .offlineGrantSigningKey(
                        properties,
                        profiles("local", "postgresql")
                );

        assertThat(loaded.keyId()).isEqualTo("local-postgresql-key-v1");
        assertThat(loaded.publicKey().getEncoded())
                .isEqualTo(pair.getPublic().getEncoded());
        assertThat(fingerprint(loaded.publicKey()))
                .isEqualTo(fingerprint(pair.getPublic()))
                .hasSize(43);
    }

    @Test
    void localPostgresqlFailsClosedForPartialConfiguredKeyMaterial() {
        OfflineGrantProperties properties = new OfflineGrantProperties();
        properties.setKeyId("local-postgresql-key-v1");

        assertThatThrownBy(() -> new OfflineGrantKeyConfiguration()
                .offlineGrantSigningKey(
                        properties,
                        profiles("local", "postgresql")
                ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("private-key-file");
    }

    @Test
    void localPostgresqlRuntimeBindsMountedConfiguredKeys()
            throws Exception {
        KeyPair pair = rsaKeyPair();
        Path privatePem = writePem(
                "runtime-private.pem",
                "PRIVATE KEY",
                pair.getPrivate().getEncoded()
        );
        Path publicPem = writePem(
                "runtime-public.pem",
                "PUBLIC KEY",
                pair.getPublic().getEncoded()
        );

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ConfigurationPropertiesAutoConfiguration.class
                ))
                .withUserConfiguration(
                        OfflineGrantKeyConfiguration.class,
                        OfflineGrantProperties.class
                )
                .withPropertyValues(
                        "spring.profiles.active=local,postgresql",
                        "cortex.auth.offline-grant.key-id="
                                + "local-runtime-key-v1",
                        "cortex.auth.offline-grant.private-key-file="
                                + privatePem,
                        "cortex.auth.offline-grant.public-key-file="
                                + publicPem
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    OfflineGrantSigningKey loaded = context.getBean(
                            OfflineGrantSigningKey.class
                    );

                    assertThat(loaded.keyId()).isEqualTo(
                            "local-runtime-key-v1"
                    );
                    assertThat(loaded.publicKey().getEncoded())
                            .isEqualTo(pair.getPublic().getEncoded());
                    assertThat(fingerprint(loaded.publicKey()))
                            .isEqualTo(fingerprint(pair.getPublic()));
                    assertThat(context.getBean(
                            OfflineGrantPublicKeyFingerprint.class
                    ).value()).isEqualTo(fingerprint(pair.getPublic()));
                });
    }

    @Test
    void productionLoadsMountedPkcs8AndSpkiPemAndValidatesThePair()
            throws Exception {
        KeyPair pair = rsaKeyPair();
        Path privatePem = writePem(
                "private.pem",
                "PRIVATE KEY",
                pair.getPrivate().getEncoded()
        );
        Path publicPem = writePem(
                "public.pem",
                "PUBLIC KEY",
                pair.getPublic().getEncoded()
        );
        OfflineGrantProperties properties = properties(
                "grant-key-2030-01",
                privatePem,
                publicPem
        );

        OfflineGrantSigningKey loaded = new OfflineGrantKeyConfiguration()
                .offlineGrantSigningKey(
                        properties,
                        new MockEnvironment().withProperty(
                                "spring.profiles.active",
                                "prod"
                        )
                );

        assertThat(loaded.keyId()).isEqualTo("grant-key-2030-01");
        assertThat(loaded.publicKey().getEncoded())
                .isEqualTo(pair.getPublic().getEncoded());
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(loaded.publicKey());
        verifier.update("pair-check".getBytes(StandardCharsets.US_ASCII));
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(loaded.privateKey());
        signer.update("pair-check".getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(signer.sign())).isTrue();
    }

    @Test
    void productionFailsClosedWhenFilesAreMissingOrKeysDoNotMatch()
            throws Exception {
        OfflineGrantKeyConfiguration configuration =
                new OfflineGrantKeyConfiguration();

        assertThatThrownBy(() -> configuration.offlineGrantSigningKey(
                new OfflineGrantProperties(),
                new MockEnvironment().withProperty(
                        "spring.profiles.active",
                        "prod"
                )
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("offline grant");

        KeyPair first = rsaKeyPair();
        KeyPair second = rsaKeyPair();
        OfflineGrantProperties mismatch = properties(
                "grant-key-2030-01",
                writePem("private-mismatch.pem", "PRIVATE KEY",
                        first.getPrivate().getEncoded()),
                writePem("public-mismatch.pem", "PUBLIC KEY",
                        second.getPublic().getEncoded())
        );

        assertThatThrownBy(() -> configuration.offlineGrantSigningKey(
                mismatch,
                new MockEnvironment().withProperty(
                        "spring.profiles.active",
                        "prod"
                )
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("não correspondem");
    }

    @Test
    void productionProfileCannotBeDowngradedByAlsoEnablingTest() {
        MockEnvironment mixed = new MockEnvironment();
        mixed.setActiveProfiles("production", "test");

        assertThatThrownBy(() -> new OfflineGrantKeyConfiguration()
                .offlineGrantSigningKey(
                        new OfflineGrantProperties(),
                        mixed
                )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("offline grant");
    }

    @Test
    void productionRejectsWeakRsaAndNonCanonicalKeyIds() throws Exception {
        KeyPairGenerator weakGenerator = KeyPairGenerator.getInstance("RSA");
        weakGenerator.initialize(1024);
        KeyPair weak = weakGenerator.generateKeyPair();
        OfflineGrantProperties weakProperties = properties(
                "grant key with spaces",
                writePem("weak-private.pem", "PRIVATE KEY",
                        weak.getPrivate().getEncoded()),
                writePem("weak-public.pem", "PUBLIC KEY",
                        weak.getPublic().getEncoded())
        );

        assertThatThrownBy(() -> new OfflineGrantKeyConfiguration()
                .offlineGrantSigningKey(
                        weakProperties,
                        new MockEnvironment().withProperty(
                                "spring.profiles.active",
                                "prod"
                        )
                )).isInstanceOf(IllegalStateException.class);
    }

    private OfflineGrantProperties properties(
            String keyId,
            Path privateKey,
            Path publicKey
    ) {
        OfflineGrantProperties properties = new OfflineGrantProperties();
        properties.setKeyId(keyId);
        properties.setPrivateKeyFile(privateKey.toString());
        properties.setPublicKeyFile(publicKey.toString());
        return properties;
    }

    private KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private MockEnvironment profiles(String... activeProfiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(activeProfiles);
        return environment;
    }

    private void assertEphemeral(OfflineGrantSigningKey key) {
        assertThat(key.keyId()).startsWith("ephemeral-");
        assertThat(key.privateKey().getAlgorithm()).isEqualTo("RSA");
        assertThat(key.publicKey().getEncoded()).hasSizeGreaterThan(250);
    }

    private String fingerprint(PublicKey key) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(key.getEncoded())
        );
    }

    private Path writePem(String name, String type, byte[] der) throws Exception {
        String encoded = Base64.getMimeEncoder(64, new byte[]{'\n'})
                .encodeToString(der);
        Path path = tempDir.resolve(name);
        Files.writeString(
                path,
                "-----BEGIN " + type + "-----\n"
                        + encoded + "\n-----END " + type + "-----\n",
                StandardCharsets.US_ASCII
        );
        return path;
    }
}
