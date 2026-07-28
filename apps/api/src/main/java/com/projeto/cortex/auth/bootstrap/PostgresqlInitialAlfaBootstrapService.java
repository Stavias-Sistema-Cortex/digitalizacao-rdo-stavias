package com.projeto.cortex.auth.bootstrap;

import com.projeto.cortex.auth.identity.CpfLookupDigest;
import com.projeto.cortex.auth.identity.CpfLookupDigestService;
import com.projeto.cortex.colaboradores.AcademyCollaboratorIdentity;
import com.projeto.cortex.integracoes.AcademyBootstrapUser;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/** Coordinates the file-only Academy lookup and the sole PostgreSQL transaction. */
@Component
@Profile("postgresql-bootstrap")
public final class PostgresqlInitialAlfaBootstrapService {

    private static final byte[] RECEIPT_DOMAIN =
            "cortex:postgresql:initial-alfa-bootstrap:receipt:v1"
                    .getBytes(StandardCharsets.US_ASCII);

    private final BootstrapAdminProperties properties;
    private final BootstrapCpfSecretReader secretReader;
    private final CpfLookupDigestService cpfLookupDigestService;
    private final AcademyBootstrapLookup academyLookup;
    private final PostgresqlInitialAlfaBootstrapRepository repository;
    private final TransactionOperations transactions;

    public PostgresqlInitialAlfaBootstrapService(
            BootstrapAdminProperties properties,
            BootstrapCpfSecretReader secretReader,
            CpfLookupDigestService cpfLookupDigestService,
            AcademyBootstrapLookup academyLookup,
            PostgresqlInitialAlfaBootstrapRepository repository,
            PlatformTransactionManager transactionManager
    ) {
        this(
                properties,
                secretReader,
                cpfLookupDigestService,
                academyLookup,
                repository,
                new TransactionTemplate(transactionManager)
        );
    }

    PostgresqlInitialAlfaBootstrapService(
            BootstrapAdminProperties properties,
            BootstrapCpfSecretReader secretReader,
            CpfLookupDigestService cpfLookupDigestService,
            AcademyBootstrapLookup academyLookup,
            PostgresqlInitialAlfaBootstrapRepository repository,
            TransactionOperations transactions
    ) {
        this.properties = properties;
        this.secretReader = secretReader;
        this.cpfLookupDigestService = cpfLookupDigestService;
        this.academyLookup = academyLookup;
        this.repository = repository;
        this.transactions = transactions;
    }

    public BootstrapResult bootstrap() {
        try {
            String canonicalCpf = secretReader.read(requiredSecretPath());
            CpfLookupDigest protectedCpf = cpfLookupDigestService.current(canonicalCpf);
            AcademyBootstrapUser academyUser = academyLookup.lookupSingleActive(canonicalCpf);
            BootstrapProjection projection = validateProjection(academyUser);
            String receiptDigest = receiptDigest(protectedCpf);
            BootstrapResult result = transactions.execute(status -> repository.bootstrap(
                    projection,
                    protectedCpf,
                    receiptDigest
            ));
            if (result == null) {
                throw new BootstrapFailure();
            }
            return result;
        } catch (PostgresqlInitialAlfaBootstrapRepository.BootstrapConflictException exception) {
            throw exception;
        } catch (BootstrapFailure exception) {
            throw exception;
        } catch (Exception ignored) {
            throw new BootstrapFailure();
        }
    }

    private Path requiredSecretPath() {
        String configured = properties.getAdminCpfFile();
        if (configured == null || configured.isBlank()) {
            throw new BootstrapFailure();
        }
        try {
            return Path.of(configured);
        } catch (Exception ignored) {
            throw new BootstrapFailure();
        }
    }

    private BootstrapProjection validateProjection(AcademyBootstrapUser user) {
        try {
            if (user == null || user.sourceUserId() <= 0 || !user.ativo()) {
                throw new IllegalArgumentException();
            }
            return new BootstrapProjection(
                    AcademyCollaboratorIdentity.fromAcademyUserId(user.sourceUserId()),
                    Long.toString(user.sourceUserId()),
                    requiredText(user.nome(), 255),
                    normalizedEmail(user.email()),
                    optionalText(user.idGrupo(), 128),
                    optionalText(user.nomeGrupo(), 120),
                    optionalText(user.idPerfil(), 128),
                    optionalText(user.nomePerfil(), 120),
                    user.criadoEm()
            );
        } catch (Exception ignored) {
            throw new BootstrapFailure();
        }
    }

    private static String requiredText(String raw, int maximumLength) {
        String normalized = optionalText(raw, maximumLength);
        if (normalized == null) {
            throw new IllegalArgumentException();
        }
        return normalized;
    }

    private static String optionalText(String raw, int maximumLength) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.strip();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > maximumLength
                || normalized.indexOf('\r') >= 0
                || normalized.indexOf('\n') >= 0) {
            throw new IllegalArgumentException();
        }
        return normalized;
    }

    private static String normalizedEmail(String raw) throws AddressException {
        String email = requiredText(raw, 320).toLowerCase(Locale.ROOT);
        InternetAddress address = new InternetAddress(email, true);
        address.validate();
        if (!email.equals(address.getAddress())) {
            throw new IllegalArgumentException();
        }
        return email;
    }

    private static String receiptDigest(CpfLookupDigest protectedCpf) {
        byte[] keyId = protectedCpf.keyId().getBytes(StandardCharsets.US_ASCII);
        byte[] hmac = protectedCpf.value().getBytes(StandardCharsets.US_ASCII);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(RECEIPT_DOMAIN);
            digest.update((byte) 0);
            digest.update(keyId);
            digest.update((byte) 0);
            return HexFormat.of().formatHex(digest.digest(hmac));
        } catch (NoSuchAlgorithmException exception) {
            throw new BootstrapFailure();
        }
    }

    public enum BootstrapResult {
        CREATED,
        ALREADY_APPLIED
    }

    static final class BootstrapFailure extends IllegalStateException {

        private BootstrapFailure() {
            super("Bootstrap inicial do ALFA não pôde ser concluído.");
        }
    }
}
