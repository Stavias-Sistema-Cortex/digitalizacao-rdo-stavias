package com.projeto.cortex.storage.migrate;

import static org.assertj.core.api.Assertions.assertThat;

import com.projeto.cortex.storage.ObjectStorage;
import com.projeto.cortex.storage.ObjectStorageContent;
import com.projeto.cortex.storage.ObjectStorageNotFoundException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

class PostgresqlObjectStorageMigrationIT {

    @TempDir
    Path temporary;

    @Test
    void pagesMixedCatalogWithoutMutatingPostgresqlAndWritesSafeEvidence()
            throws Exception {
        try (PostgreSQLContainer<?> database =
                     new PostgreSQLContainer<>("postgres:18")) {
            database.start();
            JdbcTemplate jdbc = jdbc(database);
            jdbc.execute("""
                    CREATE TABLE stored_object (
                        id varchar(36) PRIMARY KEY,
                        storage_backend varchar(20) NOT NULL,
                        storage_key varchar(700) NOT NULL,
                        sha256 char(64) NOT NULL,
                        media_type_detectado varchar(160) NOT NULL,
                        tamanho_bytes bigint NOT NULL,
                        status varchar(24) NOT NULL
                    )
                    """);

            byte[] remote = "r2-object".getBytes(StandardCharsets.UTF_8);
            byte[] local = "local-object".getBytes(StandardCharsets.UTF_8);
            insert(jdbc, "1", "S3", "objects/aa/remote/hash", remote);
            insert(jdbc, "2", "LOCAL", "objects/bb/local/hash", local);
            insertArchived(jdbc, "3", remote);
            List<Map<String, Object>> before = snapshot(jdbc);

            MemoryStorage source = new MemoryStorage("S3");
            MemoryStorage target = new MemoryStorage("LOCAL");
            source.seed("objects/aa/remote/hash", remote);
            target.seed("objects/bb/local/hash", local);
            ObjectStorageMigrationRunner runner =
                    new ObjectStorageMigrationRunner(
                            ObjectStorageMigrationApplication.jdbcCatalog(jdbc),
                            source::get,
                            target,
                            1
                    );

            ObjectStorageMigrationResult first = runner.migrate();
            ObjectStorageMigrationResult second = runner.migrate();

            assertThat(first.selected()).isEqualTo(2);
            assertThat(first.copied()).isEqualTo(1);
            assertThat(first.alreadyVerified()).isEqualTo(1);
            assertThat(first.complete()).isTrue();
            assertThat(second.copied()).isZero();
            assertThat(second.alreadyVerified()).isEqualTo(2);
            assertThat(snapshot(jdbc)).isEqualTo(before);
            assertThat(source.deleted).isZero();

            Path evidenceDirectory = temporary.resolve("evidence");
            Files.createDirectory(evidenceDirectory);
            Path evidence = evidenceDirectory.resolve("result.json");
            ObjectStorageMigrationApplication.writeEvidence(evidence, second);
            ObjectStorageMigrationApplication.writeEvidence(evidence, second);
            String json = Files.readString(evidence);
            assertThat(json)
                    .contains("\"complete\":true")
                    .contains(second.manifestSha256())
                    .doesNotContain("objects/aa/remote/hash")
                    .doesNotContain("objects/bb/local/hash")
                    .doesNotContain("remote-object")
                    .doesNotContain("local-object");
            assertThat(Files.getPosixFilePermissions(evidence))
                    .containsExactlyInAnyOrder(
                            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
                    );
        }
    }

    private JdbcTemplate jdbc(PostgreSQLContainer<?> database) {
        DriverManagerDataSource source = new DriverManagerDataSource(
                database.getJdbcUrl(),
                database.getUsername(),
                database.getPassword()
        );
        source.setDriverClassName("org.postgresql.Driver");
        return new JdbcTemplate(source);
    }

    private void insert(
            JdbcTemplate jdbc,
            String id,
            String backend,
            String key,
            byte[] body
    ) throws Exception {
        jdbc.update(
                """
                INSERT INTO stored_object (
                    id, storage_backend, storage_key, sha256,
                    media_type_detectado, tamanho_bytes, status
                ) VALUES (?, ?, ?, ?, 'text/plain', ?, 'DISPONIVEL')
                """,
                id,
                backend,
                key,
                sha(body),
                body.length
        );
    }

    private void insertArchived(
            JdbcTemplate jdbc,
            String id,
            byte[] body
    ) throws Exception {
        jdbc.update(
                """
                INSERT INTO stored_object (
                    id, storage_backend, storage_key, sha256,
                    media_type_detectado, tamanho_bytes, status
                ) VALUES (?, 'S3', ?, ?, 'text/plain', ?, 'ARQUIVADO')
                """,
                id,
                "objects/cc/archived/hash",
                sha(body),
                body.length
        );
    }

    private List<Map<String, Object>> snapshot(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT * FROM stored_object ORDER BY id");
    }

    private String sha(byte[] body) throws Exception {
        return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(body)
        );
    }

    private static final class MemoryStorage implements ObjectStorage {
        private final String backend;
        private final Map<String, byte[]> values = new LinkedHashMap<>();
        private int deleted;

        private MemoryStorage(String backend) {
            this.backend = backend;
        }

        private void seed(String key, byte[] body) {
            values.put(key, body.clone());
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
            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                inputStream.transferTo(output);
                byte[] body = output.toByteArray();
                if (body.length != length) {
                    throw new IllegalStateException("length");
                }
                seed(key, body);
            } catch (java.io.IOException exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public ObjectStorageContent get(String key) {
            byte[] body = values.get(key);
            if (body == null) {
                throw new ObjectStorageNotFoundException("missing");
            }
            return new ObjectStorageContent(
                    new ByteArrayInputStream(body),
                    body.length,
                    "text/plain"
            );
        }

        @Override
        public void delete(String key) {
            deleted++;
            values.remove(key);
        }
    }
}
