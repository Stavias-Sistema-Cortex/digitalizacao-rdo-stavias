package com.projeto.cortex.migration;

import org.flywaydb.core.internal.resolver.ChecksumCalculator;
import org.flywaydb.core.internal.resource.filesystem.FileSystemResource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AppliedMigrationChecksumTest {

    @Test
    void v8ShouldRemainIdenticalToTheMigrationAppliedToSharedDatabases() {
        var migration = new FileSystemResource(
                null,
                "src/main/resources/db/migration/V8__create_rdo.sql",
                StandardCharsets.UTF_8,
                false
        );

        assertThat(ChecksumCalculator.calculate(migration))
                .isEqualTo(167591114);
    }
}
