package com.projeto.cortex.storage.migrate;

import static org.assertj.core.api.Assertions.assertThat;

import com.projeto.cortex.storage.ObjectStorage;
import com.projeto.cortex.storage.ObjectStorageContent;
import com.projeto.cortex.storage.ObjectStorageException;
import com.projeto.cortex.storage.ObjectStorageNotFoundException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ObjectStorageMigrationRunnerTest {

    @Test
    void copiesAvailableObjectsInPagesAndIsIdempotent() throws Exception {
        byte[] first = bytes("primeiro");
        byte[] second = bytes("segundo");
        FakeStorage source = new FakeStorage("S3");
        FakeStorage target = new FakeStorage("LOCAL");
        source.seed("objects/aa/one/hash", first, "text/plain");
        source.seed("objects/bb/two/hash", second, "text/plain");

        ObjectStorageMigrationRunner runner = runner(
                List.of(
                        object("1", "S3", "objects/aa/one/hash", first),
                        object("2", "S3", "objects/bb/two/hash", second)
                ),
                source,
                target,
                1
        );

        ObjectStorageMigrationResult firstRun = runner.migrate();
        ObjectStorageMigrationResult secondRun = runner.migrate();

        assertThat(firstRun.selected()).isEqualTo(2);
        assertThat(firstRun.copied()).isEqualTo(2);
        assertThat(firstRun.alreadyVerified()).isZero();
        assertThat(firstRun.bytes()).isEqualTo(first.length + second.length);
        assertThat(firstRun.complete()).isTrue();
        assertThat(secondRun.selected()).isEqualTo(2);
        assertThat(secondRun.copied()).isZero();
        assertThat(secondRun.alreadyVerified()).isEqualTo(2);
        assertThat(secondRun.complete()).isTrue();
        assertThat(secondRun.manifestSha256()).hasSize(64);
        assertThat(source.deletedKeys).isEmpty();
    }

    @Test
    void reportsMissingMismatchUnsafeAndWriteFailureWithoutDeletingSource()
            throws Exception {
        byte[] valid = bytes("conteudo valido");
        byte[] wrong = valid.clone();
        wrong[0] = (byte) (wrong[0] ^ 1);
        FakeStorage source = new FakeStorage("S3");
        FakeStorage target = new FakeStorage("LOCAL");
        source.seed("objects/aa/mismatch/hash", wrong, "text/plain");
        source.seed("../unsafe", valid, "text/plain");
        source.seed("objects/cc/failure/hash", valid, "text/plain");
        target.failWritesFor = "objects/cc/failure/hash";

        ObjectStorageMigrationRunner runner = runner(
                List.of(
                        object(
                                "1",
                                "S3",
                                "objects/00/missing/hash",
                                valid
                        ),
                        object(
                                "2",
                                "S3",
                                "objects/aa/mismatch/hash",
                                valid
                        ),
                        object("3", "S3", "../unsafe", valid),
                        object(
                                "4",
                                "S3",
                                "objects/cc/failure/hash",
                                valid
                        )
                ),
                source,
                target,
                10
        );

        ObjectStorageMigrationResult result = runner.migrate();

        assertThat(result.selected()).isEqualTo(4);
        assertThat(result.missing()).isEqualTo(1);
        assertThat(result.mismatched()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(2);
        assertThat(result.complete()).isFalse();
        assertThat(target.objects).isEmpty();
        assertThat(source.deletedKeys).isEmpty();
    }

    @Test
    void verifiesLegacyLocalRowsInPlaceWithoutReadingOrDeletingS3()
            throws Exception {
        byte[] body = bytes("ja local");
        FakeStorage source = new FakeStorage("S3");
        FakeStorage target = new FakeStorage("LOCAL");
        target.seed("objects/aa/local/hash", body, "text/plain");

        ObjectStorageMigrationResult result = runner(
                List.of(object(
                        "1",
                        "LOCAL",
                        "objects/aa/local/hash",
                        body
                )),
                source,
                target,
                10
        ).migrate();

        assertThat(result.alreadyVerified()).isEqualTo(1);
        assertThat(result.complete()).isTrue();
        assertThat(source.readKeys).isEmpty();
        assertThat(source.deletedKeys).isEmpty();
    }

    @Test
    void emptyCatalogProducesACompleteEmptyManifest() {
        ObjectStorageMigrationResult result = runner(
                List.of(),
                new FakeStorage("S3"),
                new FakeStorage("LOCAL"),
                2
        ).migrate();

        assertThat(result.selected()).isZero();
        assertThat(result.complete()).isTrue();
        assertThat(result.manifestSha256()).hasSize(64);
    }

    private ObjectStorageMigrationRunner runner(
            List<ObjectStorageMigrationRunner.MigrationObject> objects,
            ObjectStorage source,
            ObjectStorage target,
            int pageSize
    ) {
        ObjectStorageMigrationRunner.Catalog catalog = (afterId, limit) ->
                objects.stream()
                        .filter(object -> afterId == null
                                || object.id().compareTo(afterId) > 0)
                        .limit(limit)
                        .toList();
        return new ObjectStorageMigrationRunner(
                catalog,
                source::get,
                target,
                pageSize
        );
    }

    private ObjectStorageMigrationRunner.MigrationObject object(
            String id,
            String backend,
            String key,
            byte[] expected
    ) throws Exception {
        return new ObjectStorageMigrationRunner.MigrationObject(
                id,
                backend,
                key,
                hex(MessageDigest.getInstance("SHA-256").digest(expected)),
                "text/plain",
                expected.length
        );
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private String hex(byte[] value) {
        return java.util.HexFormat.of().formatHex(value);
    }

    private static final class FakeStorage implements ObjectStorage {
        private final String backend;
        private final Map<String, Stored> objects = new LinkedHashMap<>();
        private final List<String> readKeys = new ArrayList<>();
        private final List<String> deletedKeys = new ArrayList<>();
        private String failWritesFor;

        private FakeStorage(String backend) {
            this.backend = backend;
        }

        private void seed(String key, byte[] body, String mediaType) {
            objects.put(key, new Stored(body.clone(), mediaType));
        }

        @Override
        public String backend() {
            return backend;
        }

        @Override
        public void put(
                String key,
                InputStream inputStream,
                long length,
                String mediaType
        ) {
            if (key.equals(failWritesFor)) {
                throw new ObjectStorageException("simulated target failure");
            }
            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                inputStream.transferTo(output);
                byte[] body = output.toByteArray();
                if (body.length != length) {
                    throw new ObjectStorageException("length mismatch");
                }
                seed(key, body, mediaType);
            } catch (java.io.IOException exception) {
                throw new ObjectStorageException("simulated read failure", exception);
            }
        }

        @Override
        public ObjectStorageContent get(String key) {
            readKeys.add(key);
            if (key.startsWith("../")) {
                throw new IllegalArgumentException("unsafe key");
            }
            Stored stored = objects.get(key);
            if (stored == null) {
                throw new ObjectStorageNotFoundException("missing");
            }
            return new ObjectStorageContent(
                    new ByteArrayInputStream(stored.body()),
                    stored.body().length,
                    stored.mediaType()
            );
        }

        @Override
        public void delete(String key) {
            deletedKeys.add(key);
            objects.remove(key);
        }

        private record Stored(byte[] body, String mediaType) {
        }
    }
}
