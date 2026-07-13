package com.projeto.cortex.auth.identity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
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
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-shot, non-web identity provisioner. Identity material is accepted only
 * through a mounted owner-only file; application arguments are ignored.
 */
@Component
@ConditionalOnProperty(
        prefix = "cortex.auth.provisioning",
        name = "enabled",
        havingValue = "true"
)
public class AuthIdentityProvisioningRunner implements ApplicationRunner {

    private static final String INVALID_MANIFEST =
            "Manifesto de provisionamento inválido.";
    private static final long MAX_MANIFEST_BYTES = 1_048_576;
    private static final Set<PosixFilePermission> ALLOWED_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
    );

    private final Path provisioningFile;
    private final ObjectMapper objectMapper;
    private final AuthIdentityRepository identityRepository;
    private final ProvisioningReceiptRepository receiptRepository;
    private final boolean nonWeb;

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
            String receipt = sha256(bytes);
            List<ValidatedIdentity> identities = parseAndValidate(bytes);
            if (!receiptRepository.claim(receipt, identities.size())) {
                return;
            }
            for (ValidatedIdentity identity : identities) {
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
        validateMountedFile();
        try {
            long size = Files.size(provisioningFile);
            if (size < 1 || size > MAX_MANIFEST_BYTES) {
                throw invalidManifest();
            }
            return Files.readAllBytes(provisioningFile);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Não foi possível ler o arquivo secreto de provisionamento."
            );
        }
    }

    private void validateMountedFile() {
        if (provisioningFile == null
                || Files.isSymbolicLink(provisioningFile)
                || !Files.isRegularFile(
                        provisioningFile,
                        LinkOption.NOFOLLOW_LINKS
                )) {
            throw new IllegalStateException(
                    "Arquivo secreto de provisionamento inválido."
            );
        }
        try {
            Set<PosixFilePermission> permissions =
                    Files.getPosixFilePermissions(
                            provisioningFile,
                            LinkOption.NOFOLLOW_LINKS
                    );
            if (!permissions.contains(PosixFilePermission.OWNER_READ)
                    || !ALLOWED_PERMISSIONS.containsAll(permissions)) {
                throw new IllegalStateException(
                        "O arquivo secreto de provisionamento deve usar 0600 ou permissões mais restritas."
                );
            }
        } catch (UnsupportedOperationException exception) {
            throw new IllegalStateException(
                    "O arquivo secreto de provisionamento exige permissões POSIX."
            );
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Não foi possível validar o arquivo secreto de provisionamento."
            );
        }
    }

    private List<ValidatedIdentity> parseAndValidate(byte[] bytes) {
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
        return List.copyOf(identities);
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

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 indisponível para provisionamento."
            );
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
}
