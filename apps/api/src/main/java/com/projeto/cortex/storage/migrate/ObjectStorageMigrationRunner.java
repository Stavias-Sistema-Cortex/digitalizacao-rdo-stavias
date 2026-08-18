package com.projeto.cortex.storage.migrate;

import com.projeto.cortex.storage.ObjectStorage;
import com.projeto.cortex.storage.ObjectStorageContent;
import com.projeto.cortex.storage.ObjectStorageException;
import com.projeto.cortex.storage.ObjectStorageNotFoundException;
import java.io.IOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public final class ObjectStorageMigrationRunner {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern BACKEND = Pattern.compile("LOCAL|S3");
    private static final int MAX_PAGE_SIZE = 1_000;

    private final Catalog catalog;
    private final ObjectSource source;
    private final ObjectStorage target;
    private final int pageSize;

    public ObjectStorageMigrationRunner(
            Catalog catalog,
            ObjectSource source,
            ObjectStorage target,
            int pageSize
    ) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.source = Objects.requireNonNull(source, "source");
        this.target = Objects.requireNonNull(target, "target");
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "O tamanho da página deve ficar entre 1 e "
                            + MAX_PAGE_SIZE + "."
            );
        }
        if (!"LOCAL".equals(target.backend())) {
            throw new IllegalArgumentException(
                    "A migração exige destino LOCAL."
            );
        }
        this.pageSize = pageSize;
    }

    public ObjectStorageMigrationResult migrate() {
        Totals totals = new Totals();
        MessageDigest manifest = sha256Digest();
        String cursor = null;

        while (true) {
            List<MigrationObject> page = List.copyOf(
                    catalog.findAvailableAfter(cursor, pageSize)
            );
            if (page.isEmpty()) {
                break;
            }
            if (page.size() > pageSize) {
                throw new IllegalStateException(
                        "O catálogo excedeu o limite da página."
                );
            }

            for (MigrationObject object : page) {
                if (cursor != null && object.id().compareTo(cursor) <= 0) {
                    throw new IllegalStateException(
                            "O catálogo de objetos não está em ordem crescente."
                    );
                }
                Outcome outcome = migrateOne(object);
                totals.record(object, outcome);
                updateManifest(manifest, object, outcome);
                cursor = object.id();
            }
        }

        return totals.result(HexFormat.of().formatHex(manifest.digest()));
    }

    private Outcome migrateOne(MigrationObject object) {
        try {
            Verification existing = verifyTarget(object);
            if (existing == Verification.VERIFIED) {
                return Outcome.ALREADY_VERIFIED;
            }
            if (existing == Verification.MISMATCHED) {
                return Outcome.MISMATCHED;
            }
            if ("LOCAL".equals(object.storageBackend())) {
                return Outcome.MISSING;
            }
            return copyFromSource(object);
        } catch (ObjectStorageNotFoundException exception) {
            return Outcome.MISSING;
        } catch (ObjectStorageException | IllegalArgumentException exception) {
            return Outcome.FAILED;
        }
    }

    private Outcome copyFromSource(MigrationObject object) {
        try (ObjectStorageContent content = source.get(object.storageKey())) {
            if (content.length() >= 0 && content.length() != object.size()) {
                return Outcome.MISMATCHED;
            }
            if (!object.mediaType().equals(content.mediaType())) {
                return Outcome.MISMATCHED;
            }

            MessageDigest sourceDigest = sha256Digest();
            try (DigestInputStream input = new DigestInputStream(
                    content.inputStream(),
                    sourceDigest
            )) {
                target.put(
                        object.storageKey(),
                        input,
                        object.size(),
                        object.mediaType()
                );
            } catch (IOException exception) {
                safeDeleteTarget(object.storageKey());
                throw new ObjectStorageException(
                        "Não foi possível fechar o conteúdo migrado.",
                        exception
                );
            }

            String copiedSha = HexFormat.of().formatHex(sourceDigest.digest());
            if (!object.sha256().equals(copiedSha)) {
                safeDeleteTarget(object.storageKey());
                return Outcome.MISMATCHED;
            }
            if (verifyTarget(object) != Verification.VERIFIED) {
                safeDeleteTarget(object.storageKey());
                return Outcome.MISMATCHED;
            }
            return Outcome.COPIED;
        }
    }

    private Verification verifyTarget(MigrationObject object) {
        try (ObjectStorageContent content = target.get(object.storageKey())) {
            if (content.length() != object.size()
                    || !object.mediaType().equals(content.mediaType())) {
                return Verification.MISMATCHED;
            }
            MessageDigest digest = sha256Digest();
            long count = 0;
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = content.inputStream().read(buffer)) != -1) {
                digest.update(buffer, 0, read);
                count += read;
            }
            if (count != object.size()
                    || !object.sha256().equals(
                            HexFormat.of().formatHex(digest.digest())
                    )) {
                return Verification.MISMATCHED;
            }
            return Verification.VERIFIED;
        } catch (ObjectStorageNotFoundException exception) {
            return Verification.ABSENT;
        } catch (IOException exception) {
            throw new ObjectStorageException(
                    "Não foi possível verificar o objeto local.",
                    exception
            );
        }
    }

    private void safeDeleteTarget(String key) {
        try {
            target.delete(key);
        } catch (RuntimeException ignored) {
            // The migration remains failed; source storage is never touched.
        }
    }

    private static void updateManifest(
            MessageDigest manifest,
            MigrationObject object,
            Outcome outcome
    ) {
        String line = String.join(
                "\u0000",
                object.id(),
                object.storageBackend(),
                object.storageKey(),
                object.sha256(),
                Long.toString(object.size()),
                object.mediaType(),
                outcome.name()
        ) + "\n";
        manifest.update(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponível.", exception);
        }
    }

    @FunctionalInterface
    public interface Catalog {
        List<MigrationObject> findAvailableAfter(String afterId, int limit);
    }

    @FunctionalInterface
    public interface ObjectSource {
        ObjectStorageContent get(String key);
    }

    public record MigrationObject(
            String id,
            String storageBackend,
            String storageKey,
            String sha256,
            String mediaType,
            long size
    ) {
        public MigrationObject {
            id = requireOneLine(id, "id");
            storageBackend = requireOneLine(
                    storageBackend,
                    "storageBackend"
            ).toUpperCase(Locale.ROOT);
            storageKey = requireOneLine(storageKey, "storageKey");
            sha256 = requireOneLine(sha256, "sha256")
                    .toLowerCase(Locale.ROOT);
            mediaType = requireOneLine(mediaType, "mediaType");
            if (!BACKEND.matcher(storageBackend).matches()) {
                throw new IllegalArgumentException(
                        "Backend de objeto não suportado."
                );
            }
            if (!SHA256.matcher(sha256).matches() || size < 0) {
                throw new IllegalArgumentException(
                        "Metadados de integridade do objeto são inválidos."
                );
            }
        }

        private static String requireOneLine(String value, String label) {
            if (value == null || value.isBlank() || value.contains("\n")
                    || value.contains("\r") || value.contains("\u0000")) {
                throw new IllegalArgumentException(label + " inválido.");
            }
            return value.strip();
        }
    }

    private enum Verification {
        ABSENT,
        VERIFIED,
        MISMATCHED
    }

    private enum Outcome {
        COPIED,
        ALREADY_VERIFIED,
        MISSING,
        MISMATCHED,
        FAILED
    }

    private static final class Totals {
        private long selected;
        private long copied;
        private long alreadyVerified;
        private long missing;
        private long mismatched;
        private long failed;
        private long bytes;

        private void record(MigrationObject object, Outcome outcome) {
            selected++;
            switch (outcome) {
                case COPIED -> {
                    copied++;
                    bytes += object.size();
                }
                case ALREADY_VERIFIED -> alreadyVerified++;
                case MISSING -> missing++;
                case MISMATCHED -> mismatched++;
                case FAILED -> failed++;
            }
        }

        private ObjectStorageMigrationResult result(String manifestSha256) {
            return new ObjectStorageMigrationResult(
                    selected,
                    copied,
                    alreadyVerified,
                    missing,
                    mismatched,
                    failed,
                    bytes,
                    manifestSha256
            );
        }
    }

}
