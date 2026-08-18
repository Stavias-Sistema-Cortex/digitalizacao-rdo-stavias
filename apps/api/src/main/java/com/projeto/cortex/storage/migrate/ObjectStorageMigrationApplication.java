package com.projeto.cortex.storage.migrate;

import com.projeto.cortex.storage.LocalObjectStorage;
import com.projeto.cortex.storage.ObjectStorage;
import com.projeto.cortex.storage.S3ObjectStorage;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;

public final class ObjectStorageMigrationApplication {

    private static final int DEFAULT_PAGE_SIZE = 250;

    private ObjectStorageMigrationApplication() {
    }

    public static void main(String[] args) {
        Configuration configuration = Configuration.fromEnvironment();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                configuration.postgresUrl(),
                configuration.postgresUser(),
                readSecret(configuration.postgresPasswordFile())
        );
        dataSource.setDriverClassName("org.postgresql.Driver");

        try (java.sql.Connection connection = dataSource.getConnection();
             S3Client client = s3Client(configuration)) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(
                    java.sql.Connection.TRANSACTION_REPEATABLE_READ
            );
            connection.setAutoCommit(false);
            ObjectStorage source = new S3ObjectStorage(
                    client,
                    configuration.sourceBucket(),
                    configuration.sourcePrefix(),
                    false
            );
            ObjectStorage target = new LocalObjectStorage(
                    configuration.targetRoot()
            );
            ObjectStorageMigrationResult result =
                    new ObjectStorageMigrationRunner(
                            jdbcCatalog(new JdbcTemplate(
                                    new SingleConnectionDataSource(
                                            connection,
                                            true
                                    )
                            )),
                            source::get,
                            target,
                            configuration.pageSize()
                    ).migrate();
            writeEvidence(configuration.evidenceFile(), result);
            connection.rollback();
            if (!result.complete()) {
                throw new IllegalStateException(
                        "A cópia de objetos terminou com pendências."
                );
            }
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException(
                    "Não foi possível abrir a snapshot PostgreSQL somente leitura.",
                    exception
            );
        }
    }

    static ObjectStorageMigrationRunner.Catalog jdbcCatalog(
            JdbcTemplate jdbcTemplate
    ) {
        return (afterId, limit) -> jdbcTemplate.query(
                """
                SELECT id, storage_backend, storage_key, sha256,
                       media_type_detectado, tamanho_bytes
                FROM stored_object
                WHERE status = 'DISPONIVEL'
                  AND id > ?
                ORDER BY id
                LIMIT ?
                """,
                (resultSet, rowNumber) ->
                        new ObjectStorageMigrationRunner.MigrationObject(
                                resultSet.getString("id"),
                                resultSet.getString("storage_backend"),
                                resultSet.getString("storage_key"),
                                resultSet.getString("sha256"),
                                resultSet.getString("media_type_detectado"),
                                resultSet.getLong("tamanho_bytes")
                        ),
                afterId == null ? "" : afterId,
                limit
        );
    }

    private static S3Client s3Client(Configuration configuration) {
        S3ClientBuilder builder = S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                                readSecret(configuration.accessKeyFile()),
                                readSecret(configuration.secretKeyFile())
                        )
                ))
                .region(Region.of(configuration.sourceRegion()))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallAttemptTimeout(Duration.ofSeconds(10))
                        .apiCallTimeout(Duration.ofSeconds(30))
                        .build())
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(configuration.pathStyle())
                        .build());
        if (!configuration.sourceEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(
                    configuration.sourceEndpoint()
            ));
        }
        return builder.build();
    }

    private static String readSecret(Path path) {
        try {
            if (!path.isAbsolute() || Files.isSymbolicLink(path)
                    || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException(
                        "Arquivo secreto inválido."
                );
            }
            String value = Files.readString(path, StandardCharsets.UTF_8)
                    .strip();
            if (value.isBlank() || value.contains("\n")
                    || value.contains("\r")) {
                throw new IllegalArgumentException(
                        "Arquivo secreto deve conter uma única linha."
                );
            }
            return value;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Não foi possível ler um arquivo secreto.",
                    exception
            );
        }
    }

    static void writeEvidence(
            Path destination,
            ObjectStorageMigrationResult result
    ) {
        try {
            Path absolute = destination.toAbsolutePath().normalize();
            Path parent = absolute.getParent();
            if (parent == null || Files.isSymbolicLink(absolute)
                    || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException(
                        "Destino da evidência de objetos é inválido."
                );
            }
            requirePrivateDirectory(parent);
            String json = """
                    {"schemaVersion":1,"capturedAt":"%s","selected":%d,"copied":%d,"alreadyVerified":%d,"missing":%d,"mismatched":%d,"failed":%d,"bytes":%d,"manifestSha256":"%s","complete":%s}
                    """.formatted(
                    Instant.now(),
                    result.selected(),
                    result.copied(),
                    result.alreadyVerified(),
                    result.missing(),
                    result.mismatched(),
                    result.failed(),
                    result.bytes(),
                    result.manifestSha256(),
                    result.complete()
            );
            Path temporary = Files.createTempFile(
                    parent,
                    ".object-migration-",
                    ".tmp"
            );
            try {
                Files.writeString(
                        temporary,
                        json,
                        StandardCharsets.UTF_8
                );
                setOwnerOnly(temporary);
                try {
                    Files.move(
                            temporary,
                            absolute,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING
                    );
                } catch (AtomicMoveNotSupportedException exception) {
                    throw new IllegalStateException(
                            "O destino da evidência não oferece rename atômico.",
                            exception
                    );
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Não foi possível persistir a evidência da migração.",
                    exception
            );
        }
    }

    private static void requirePrivateDirectory(Path directory)
            throws IOException {
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(
                directory,
                LinkOption.NOFOLLOW_LINKS
        );
        if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
            throw new IllegalArgumentException(
                    "Diretório da evidência não pode ser gravável por terceiros."
            );
        }
    }

    private static void setOwnerOnly(Path file) throws IOException {
        Files.setPosixFilePermissions(file, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE
        ));
    }

    private record Configuration(
            String postgresUrl,
            String postgresUser,
            Path postgresPasswordFile,
            Path accessKeyFile,
            Path secretKeyFile,
            String sourceBucket,
            String sourceRegion,
            String sourceEndpoint,
            String sourcePrefix,
            boolean pathStyle,
            Path targetRoot,
            Path evidenceFile,
            int pageSize
    ) {
        private static Configuration fromEnvironment() {
            int pageSize = Integer.parseInt(optional(
                    "CORTEX_OBJECT_MIGRATION_PAGE_SIZE",
                    Integer.toString(DEFAULT_PAGE_SIZE)
            ));
            if (pageSize < 1 || pageSize > 1_000) {
                throw new IllegalArgumentException(
                        "CORTEX_OBJECT_MIGRATION_PAGE_SIZE é inválido."
                );
            }
            return new Configuration(
                    required("CORTEX_POSTGRES_URL"),
                    required("CORTEX_POSTGRES_USER"),
                    Path.of(required("CORTEX_POSTGRES_PASSWORD_FILE")),
                    Path.of(required("AWS_ACCESS_KEY_ID_FILE")),
                    Path.of(required("AWS_SECRET_ACCESS_KEY_FILE")),
                    required("CORTEX_OBJECT_MIGRATION_SOURCE_S3_BUCKET"),
                    required("CORTEX_OBJECT_MIGRATION_SOURCE_S3_REGION"),
                    optional("CORTEX_OBJECT_MIGRATION_SOURCE_S3_ENDPOINT", ""),
                    optional("CORTEX_OBJECT_MIGRATION_SOURCE_S3_PREFIX", ""),
                    strictBoolean(
                            "CORTEX_OBJECT_MIGRATION_SOURCE_S3_PATH_STYLE",
                            optional(
                                    "CORTEX_OBJECT_MIGRATION_SOURCE_S3_PATH_STYLE",
                                    "false"
                            )
                    ),
                    Path.of(required(
                            "CORTEX_OBJECT_MIGRATION_TARGET_LOCAL_ROOT"
                    )),
                    Path.of(required("CORTEX_OBJECT_MIGRATION_EVIDENCE_FILE")),
                    pageSize
            );
        }

        private static String required(String name) {
            String value = System.getenv(name);
            if (value == null || value.isBlank() || value.contains("\n")
                    || value.contains("\r")) {
                throw new IllegalArgumentException(
                        name + " deve conter uma linha não vazia."
                );
            }
            return value.strip();
        }

        private static String optional(String name, String fallback) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                return fallback;
            }
            if (value.contains("\n") || value.contains("\r")) {
                throw new IllegalArgumentException(
                        name + " deve conter no máximo uma linha."
                );
            }
            return value.strip();
        }

        private static boolean strictBoolean(String name, String value) {
            if (!"true".equals(value) && !"false".equals(value)) {
                throw new IllegalArgumentException(
                        name + " deve ser true ou false."
                );
            }
            return Boolean.parseBoolean(value);
        }
    }
}
