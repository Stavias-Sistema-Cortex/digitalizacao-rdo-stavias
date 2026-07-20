package com.projeto.cortex.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class MigrationVersionUniquenessTest {

    private static final Path MIGRATIONS = Path.of(
            "src/main/resources/db/migration"
    );
    private static final Pattern VERSIONED_SQL = Pattern.compile(
            "^V([0-9]+)__.+\\.sql$"
    );

    @Test
    void versionedMigrationsMustUseUniqueVersions() throws IOException {
        Map<String, List<String>> filesByVersion;
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            filesByVersion = files
                    .map(path -> path.getFileName().toString())
                    .map(VERSIONED_SQL::matcher)
                    .filter(java.util.regex.Matcher::matches)
                    .collect(Collectors.groupingBy(
                            matcher -> matcher.group(1),
                            Collectors.mapping(
                                    java.util.regex.Matcher::group,
                                    Collectors.toList()
                            )
                    ));
        }

        assertThat(filesByVersion)
                .allSatisfy((version, files) -> assertThat(files)
                        .as("Flyway version %s", version)
                        .hasSize(1));
    }
}
