package com.projeto.cortex.auth.offline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration
public class OfflineGrantKeyConfiguration {

    private static final int MINIMUM_RSA_BITS = 2_048;
    private static final byte[] PAIR_CHECK =
            "cortex-offline-grant-key-pair-check-v1"
                    .getBytes(StandardCharsets.US_ASCII);

    @Bean
    OfflineGrantSigningKey offlineGrantSigningKey(
            OfflineGrantProperties properties,
            Environment environment
    ) {
        properties.requireValidTtlSeconds();
        properties.requireValidMaxWorksites();
        // Mounted material is explicit deployment intent, even for local+PostgreSQL.
        if (hasConfiguredKeyMaterial(properties)
                || environment.acceptsProfiles(
                        Profiles.of("prod", "production")
                )) {
            return productionKey(properties);
        }
        if (environment.acceptsProfiles(Profiles.of("local", "test"))) {
            return ephemeralKey();
        }
        return productionKey(properties);
    }

    @Bean
    OfflineGrantPublicKeyFingerprint offlineGrantPublicKeyFingerprint(
            OfflineGrantSigningKey signingKey
    ) {
        String fingerprint = publicKeyFingerprint(signingKey.publicKey());
        return () -> fingerprint;
    }

    private boolean hasConfiguredKeyMaterial(
            OfflineGrantProperties properties
    ) {
        return hasText(properties.getKeyId())
                || hasText(properties.getPrivateKeyFile())
                || hasText(properties.getPublicKeyFile());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private OfflineGrantSigningKey ephemeralKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(MINIMUM_RSA_BITS);
            KeyPair pair = generator.generateKeyPair();
            String fingerprint = publicKeyFingerprint(pair.getPublic());
            return new OfflineGrantSigningKey(
                    "ephemeral-" + fingerprint.substring(0, 16),
                    pair.getPrivate(),
                    pair.getPublic()
            );
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Não foi possível criar a chave efêmera de grant offline.",
                    exception
            );
        }
    }

    private String publicKeyFingerprint(PublicKey publicKey) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256")
                            .digest(publicKey.getEncoded())
            );
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Não foi possível derivar a impressão pública do grant offline.",
                    exception
            );
        }
    }

    private OfflineGrantSigningKey productionKey(
            OfflineGrantProperties properties
    ) {
        String keyId = requireKeyId(properties.getKeyId());
        String privateFile = requireFileProperty(
                properties.getPrivateKeyFile(),
                "private-key-file"
        );
        String publicFile = requireFileProperty(
                properties.getPublicKeyFile(),
                "public-key-file"
        );
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            PrivateKey privateKey = factory.generatePrivate(
                    new PKCS8EncodedKeySpec(readPem(
                            Path.of(privateFile),
                            "PRIVATE KEY"
                    ))
            );
            PublicKey publicKey = factory.generatePublic(
                    new X509EncodedKeySpec(readPem(
                            Path.of(publicFile),
                            "PUBLIC KEY"
                    ))
            );
            requireStrongRsa(privateKey);
            requireStrongRsa(publicKey);
            requireMatchingPair(privateKey, publicKey);
            return new OfflineGrantSigningKey(keyId, privateKey, publicKey);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Configuração da chave de offline grant inválida.",
                    exception
            );
        }
    }

    private String requireKeyId(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalStateException(
                    "Configuração de offline grant exige key-id canônico."
            );
        }
        return normalized;
    }

    private String requireFileProperty(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Configuração de offline grant exige " + property + "."
            );
        }
        return value.trim();
    }

    private byte[] readPem(Path path, String type) throws IOException {
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IllegalStateException(
                    "Arquivo de chave de offline grant indisponível."
            );
        }
        String value = Files.readString(path, StandardCharsets.US_ASCII)
                .replace("\r\n", "\n")
                .trim();
        String begin = "-----BEGIN " + type + "-----";
        String end = "-----END " + type + "-----";
        if (!value.startsWith(begin + "\n")
                || !value.endsWith("\n" + end)
                || value.indexOf(begin, begin.length()) >= 0
                || value.indexOf(end) != value.length() - end.length()) {
            throw new IllegalStateException(
                    "Formato PEM da chave de offline grant inválido."
            );
        }
        String body = value.substring(
                begin.length() + 1,
                value.length() - end.length() - 1
        );
        if (!body.matches("[A-Za-z0-9+/=\\n]+")) {
            throw new IllegalStateException(
                    "Formato PEM da chave de offline grant inválido."
            );
        }
        try {
            return Base64.getMimeDecoder().decode(body);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Conteúdo PEM da chave de offline grant inválido.",
                    exception
            );
        }
    }

    private void requireStrongRsa(java.security.Key key) {
        if (!(key instanceof RSAKey rsaKey)
                || rsaKey.getModulus().bitLength() < MINIMUM_RSA_BITS) {
            throw new IllegalStateException(
                    "A chave de offline grant deve ser RSA de pelo menos 2048 bits."
            );
        }
    }

    private void requireMatchingPair(
            PrivateKey privateKey,
            PublicKey publicKey
    ) throws Exception {
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update(PAIR_CHECK);
        byte[] signature = signer.sign();
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update(PAIR_CHECK);
            if (!verifier.verify(signature)) {
                throw new IllegalStateException(
                        "As chaves de offline grant não correspondem."
                );
            }
        } finally {
            Arrays.fill(signature, (byte) 0);
        }
    }
}
