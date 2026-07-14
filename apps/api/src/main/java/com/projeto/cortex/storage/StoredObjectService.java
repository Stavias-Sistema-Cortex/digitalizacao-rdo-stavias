package com.projeto.cortex.storage;

import com.projeto.cortex.auth.CurrentUserService;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StoredObjectService {

    private final StoredObjectRepository repository;
    private final ObjectStorage storage;
    private final StoredObjectContentInspector inspector;
    private final CurrentUserService currentUserService;
    private final List<StoredObjectDomainAccessGuard> domainAccessGuards;

    public StoredObjectService(
            StoredObjectRepository repository,
            ObjectStorage storage,
            StoredObjectContentInspector inspector,
            CurrentUserService currentUserService,
            List<StoredObjectDomainAccessGuard> domainAccessGuards
    ) {
        this.repository = repository;
        this.storage = storage;
        this.inspector = inspector;
        this.currentUserService = currentUserService;
        this.domainAccessGuards = domainAccessGuards == null
                ? List.of()
                : List.copyOf(domainAccessGuards);
    }

    public StoredObjectUploadResult upload(
            String obraId,
            String originalName,
            String declaredMediaType,
            long declaredSize,
            ReopenableInput source
    ) {
        String ownerId = currentUserService.requireUserId();
        String normalizedObraId = normalizeOptionalId(obraId);
        if (normalizedObraId != null) {
            currentUserService.requireWorksiteAccess(normalizedObraId);
        }

        InspectedObjectContent inspected = inspector.inspect(
                source,
                declaredSize,
                declaredMediaType
        );
        String dedupeKey = dedupeKey(
                ownerId,
                normalizedObraId,
                inspected
        );
        Optional<StoredObjectRecord> existing =
                repository.findAvailableByDedupeKey(dedupeKey);
        if (existing.isPresent()) {
            return new StoredObjectUploadResult(existing.get(), false);
        }

        String id = UUID.randomUUID().toString();
        String storageKey = "objects/"
                + inspected.sha256().substring(0, 2)
                + "/" + id + "/" + inspected.sha256();
        StoredObjectRecord reserved = new StoredObjectRecord(
                id,
                ownerId,
                normalizedObraId,
                dedupeKey,
                inspected.sha256(),
                storage.backend(),
                storageKey,
                safeFilename(originalName),
                inspected.declaredMediaType(),
                inspected.detectedMediaType(),
                inspected.size(),
                "TEMPORARIO",
                LocalDateTime.now(ZoneOffset.UTC)
        );

        if (!repository.reserve(reserved)) {
            return repository.findAvailableByDedupeKey(dedupeKey)
                    .map(value -> new StoredObjectUploadResult(value, false))
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Este arquivo já possui um upload em andamento."
                    ));
        }

        try {
            writeAndVerify(source, inspected, reserved);
            repository.markAvailable(id);
            return new StoredObjectUploadResult(reserved.available(), true);
        } catch (RuntimeException exception) {
            quarantine(reserved);
            throw exception;
        }
    }

    public StoredObjectDownload download(String objectId) {
        String currentUserId = currentUserService.requireUserId();
        return downloadAuthorized(objectId, object -> {
            boolean domainProtected = false;
            for (StoredObjectDomainAccessGuard guard : domainAccessGuards) {
                if (guard.protects(object)) {
                    domainProtected = true;
                    guard.authorize(object);
                }
            }
            if (domainProtected) {
                return;
            }
            if (!currentUserId.equals(object.ownerId())) {
                if (object.obraId() == null) {
                    currentUserService.requireSelfOrAdmin(object.ownerId());
                } else {
                    currentUserService.requireWorksiteAccess(object.obraId());
                }
            }
        });
    }

    /**
     * Abre um objeto depois que o domínio chamador comprova sua própria
     * fronteira de autorização. Usado, por exemplo, por anexos de conversa
     * direta, onde ser participante ativo é mais preciso que ser o uploader.
     */
    public StoredObjectDownload downloadAuthorized(
            String objectId,
            Consumer<StoredObjectRecord> authorization
    ) {
        String id = requireId(objectId);
        StoredObjectRecord object = repository.findAvailableById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Objeto não encontrado."
                ));
        if (authorization == null) {
            throw new IllegalArgumentException(
                    "Autorização do domínio é obrigatória."
            );
        }
        authorization.accept(object);

        ObjectStorageContent content;
        try {
            content = storage.get(object.storageKey());
        } catch (ObjectStorageNotFoundException exception) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "O conteúdo do objeto está temporariamente indisponível."
            );
        }
        if ((content.length() >= 0 && content.length() != object.size())
                || !object.detectedMediaType().equals(content.mediaType())) {
            closeQuietly(content);
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A integridade do objeto armazenado não foi confirmada."
            );
        }
        return new StoredObjectDownload(object, content);
    }

    private void writeAndVerify(
            ReopenableInput source,
            InspectedObjectContent inspected,
            StoredObjectRecord object
    ) {
        try (VerifyingInputStream input = new VerifyingInputStream(
                source.open()
        )) {
            storage.put(
                    object.storageKey(),
                    input,
                    inspected.size(),
                    inspected.detectedMediaType()
            );
            if (input.count() != inspected.size()
                    || !input.sha256().equals(inspected.sha256())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "O conteúdo mudou durante o upload; envie novamente."
                );
            }
        } catch (ResponseStatusException | ObjectStorageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ObjectStorageException(
                    "Não foi possível reler o arquivo validado.",
                    exception
            );
        }
    }

    private void quarantine(StoredObjectRecord object) {
        try {
            storage.delete(object.storageKey());
        } catch (RuntimeException ignored) {
            // Metadata remains quarantined and can be reconciled operationally.
        }
        repository.markQuarantined(object.id());
    }

    private static String dedupeKey(
            String ownerId,
            String obraId,
            InspectedObjectContent inspected
    ) {
        String material = "stored-object-v1\n"
                + ownerId + "\n"
                + (obraId == null ? "-" : obraId) + "\n"
                + inspected.sha256() + "\n"
                + inspected.size();
        return HexFormat.of().formatHex(
                digest().digest(material.getBytes(StandardCharsets.UTF_8))
        );
    }

    private static String safeFilename(String value) {
        if (value == null || value.isBlank()) {
            return "arquivo";
        }
        String normalized = value.replace('\\', '/');
        normalized = normalized.substring(normalized.lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}]", "")
                .strip();
        if (normalized.isBlank()) {
            return "arquivo";
        }
        return normalized.length() <= 255
                ? normalized
                : normalized.substring(0, 255);
    }

    private static String normalizeOptionalId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requireId(value);
    }

    private static String requireId(String value) {
        if (value == null || value.isBlank() || value.length() > 120) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Identificador de objeto ou obra inválido."
            );
        }
        return value.strip();
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponível.", exception);
        }
    }

    private static void closeQuietly(ObjectStorageContent content) {
        try {
            content.close();
        } catch (ObjectStorageException ignored) {
            // Integrity failure remains the primary signal.
        }
    }

    private static final class VerifyingInputStream extends FilterInputStream {

        private final MessageDigest digest = digest();
        private long count;

        private VerifyingInputStream(InputStream inputStream) {
            super(inputStream);
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                digest.update((byte) value);
                count++;
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length)
                throws IOException {
            int read = super.read(bytes, offset, length);
            if (read > 0) {
                digest.update(bytes, offset, read);
                count += read;
            }
            return read;
        }

        private long count() {
            return count;
        }

        private String sha256() {
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
