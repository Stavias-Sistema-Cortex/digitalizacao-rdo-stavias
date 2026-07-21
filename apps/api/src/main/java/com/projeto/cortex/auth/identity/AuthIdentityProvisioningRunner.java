package com.projeto.cortex.auth.identity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-shot, non-web identity provisioner. Identity material is accepted only
 * through a mounted owner-only file; application arguments are ignored.
 */
@Component
@Profile("!postgresql-common")
@ConditionalOnProperty(
        prefix = "cortex.auth.provisioning",
        name = "enabled",
        havingValue = "true"
)
public class AuthIdentityProvisioningRunner implements ApplicationRunner {

    private static final String INVALID_MANIFEST =
            "Manifesto de provisionamento inválido.";
    private static final byte[] RECEIPT_DOMAIN =
            "cortex:auth-provisioning-receipt:v1"
                    .getBytes(StandardCharsets.US_ASCII);

    private final Path provisioningFile;
    private final ObjectMapper objectMapper;
    private final AuthIdentityRepository identityRepository;
    private final ProvisioningReceiptRepository receiptRepository;
    private final boolean nonWeb;
    private final SecureProvisioningManifestReader manifestReader;

    @Autowired
    public AuthIdentityProvisioningRunner(
            @Value("${cortex.auth.provisioning.file:}")
            String provisioningFile,
            @Value("${spring.main.web-application-type:servlet}")
            String webApplicationType,
            ObjectMapper objectMapper,
            AuthIdentityRepository identityRepository,
            ProvisioningReceiptRepository receiptRepository
    ) {
        this(
                requiredPath(provisioningFile),
                objectMapper,
                identityRepository,
                receiptRepository,
                "none".equalsIgnoreCase(normalize(webApplicationType))
        );
    }

    AuthIdentityProvisioningRunner(
            Path provisioningFile,
            ObjectMapper objectMapper,
            AuthIdentityRepository identityRepository,
            ProvisioningReceiptRepository receiptRepository,
            boolean nonWeb
    ) {
        this.provisioningFile = provisioningFile;
        this.objectMapper = objectMapper.copy().enable(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES
        );
        this.identityRepository = identityRepository;
        this.receiptRepository = receiptRepository;
        this.nonWeb = nonWeb;
        this.manifestReader = new SecureProvisioningManifestReader();
    }

    @Override
    @Transactional
    public void run(ApplicationArguments ignored) {
        run();
    }

    @Transactional
    public void run() {
        if (!nonWeb) {
            throw new IllegalStateException(
                    "O provisionamento exige execução em modo não web."
            );
        }

        byte[] bytes = readManifestBytes();
        try {
            ValidatedManifest manifest = parseAndValidate(bytes);
            String receipt = receiptDigest(manifest.nonce());
            if (!receiptRepository.claim(
                    receipt,
                    manifest.identities().size()
            )) {
                return;
            }
            for (ValidatedIdentity identity : manifest.identities()) {
                identityRepository.upsertProvisionedIdentity(
                        identity.cpf(),
                        identity.email(),
                        AuthIdentityRepository.MANUAL_PENDING_SOURCE
                );
            }
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private byte[] readManifestBytes() {
        return manifestReader.read(provisioningFile);
    }

    private ValidatedManifest parseAndValidate(byte[] bytes) {
        ProvisioningManifest manifest;
        try {
            manifest = objectMapper.readValue(
                    bytes,
                    ProvisioningManifest.class
            );
        } catch (JsonProcessingException exception) {
            throw invalidManifest();
        } catch (IOException exception) {
            throw invalidManifest();
        }
        if (manifest.version() != 1
                || manifest.nonce() == null
                || !manifest.nonce().matches("[0-9a-f]{64}")
                || manifest.identities() == null
                || manifest.identities().isEmpty()) {
            throw invalidManifest();
        }

        List<ValidatedIdentity> identities = new ArrayList<>(
                manifest.identities().size()
        );
        Set<String> cpfs = new HashSet<>();
        try {
            for (ProvisioningManifest.Identity identity
                    : manifest.identities()) {
                if (identity == null) {
                    throw invalidManifest();
                }
                String cpf = CpfNormalizer.requireValid(identity.cpf());
                String email = requireEmail(identity.email());
                if (!cpfs.add(cpf)) {
                    throw invalidManifest();
                }
                identities.add(new ValidatedIdentity(cpf, email));
            }
        } catch (IllegalArgumentException exception) {
            throw invalidManifest();
        }
        return new ValidatedManifest(
                manifest.nonce(),
                List.copyOf(identities)
        );
    }

    private String requireEmail(String raw) {
        String email = normalize(raw);
        if (email == null
                || email.length() > 320
                || email.contains("\r")
                || email.contains("\n")) {
            throw invalidManifest();
        }
        try {
            InternetAddress address = new InternetAddress(email, true);
            address.validate();
            if (!email.equals(address.getAddress())) {
                throw invalidManifest();
            }
            return email;
        } catch (AddressException exception) {
            throw invalidManifest();
        }
    }

    private String receiptDigest(String nonce) {
        byte[] nonceBytes = HexFormat.of().parseHex(nonce);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(RECEIPT_DOMAIN);
            digest.update((byte) 0);
            return HexFormat.of().formatHex(digest.digest(nonceBytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 indisponível para provisionamento."
            );
        } finally {
            Arrays.fill(nonceBytes, (byte) 0);
        }
    }

    private static Path requiredPath(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalStateException(
                    "Arquivo secreto de provisionamento não configurado."
            );
        }
        return Path.of(normalized);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    private IllegalStateException invalidManifest() {
        return new IllegalStateException(INVALID_MANIFEST);
    }

    private record ValidatedIdentity(String cpf, String email) {
    }

    private record ValidatedManifest(
            String nonce,
            List<ValidatedIdentity> identities
    ) {
    }
}
