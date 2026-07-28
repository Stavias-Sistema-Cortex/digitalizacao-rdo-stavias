package com.projeto.cortex.integracoes;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalSourceAdapterTest {

    private static final String INLINE_SECRET =
            "test-only-academy-inline-secret";
    private static final String REDACTED_URL =
            "jdbc:mysql://academy-sensitive.internal:3306/academy";
    private static final String REDACTED_USER =
            "academy_sensitive_user";

    @TempDir
    Path tempDir;

    @Test
    void academyAdapterShouldRequireCortexReadOnlyConfiguration() {
        AcademySourceAdapter adapter =
                new AcademySourceAdapter("", "", "");

        assertThatThrownBy(() -> adapter.fetchCompleteSnapshot(10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CORTEX_ACADEMY_DB_URL")
                .hasMessageContaining("CORTEX_ACADEMY_DB_USER")
                .hasMessageContaining("CORTEX_ACADEMY_DB_PASSWORD_FILE");
    }

    @Test
    void academyBootstrapLookupShouldRequireCortexReadOnlyConfiguration() {
        AcademySourceAdapter adapter =
                new AcademySourceAdapter("", "", "");

        assertThatThrownBy(() ->
                adapter.findSingleActiveUserForBootstrap("synthetic-canonical-id")
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CORTEX_ACADEMY_DB_URL")
                .hasMessageContaining("CORTEX_ACADEMY_DB_USER")
                .hasMessageContaining("CORTEX_ACADEMY_DB_PASSWORD_FILE");
    }

    @Test
    void productionEnabledFailsAtStartupWithoutFileBackedCredentials() {
        productionContext(
                "cortex.sync.academy.enabled=true"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .rootCause()
                    .hasMessageContaining("Academy")
                    .hasMessageNotContaining(REDACTED_URL)
                    .hasMessageNotContaining(REDACTED_USER)
                    .hasMessageNotContaining(INLINE_SECRET);
        });
    }

    @Test
    void productionRejectsInlinePasswordEvenWhenSchedulerIsDisabled() {
        productionContext(
                "cortex.sources.academy.password=" + INLINE_SECRET
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .rootCause()
                    .hasMessageContaining("arquivo")
                    .hasMessageNotContaining(INLINE_SECRET);
        });
    }

    @Test
    void fileAndInlinePasswordFailClosedInLocalProfile() throws Exception {
        Path passwordFile = academyPasswordFile();

        localContext(
                "cortex.sources.academy.password=" + INLINE_SECRET,
                "cortex.sources.academy.password-file=" + passwordFile
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .rootCause()
                    .hasMessageContaining("nunca ambos")
                    .hasMessageNotContaining(INLINE_SECRET);
        });
    }

    @Test
    void productionEnabledRequiresMysqlIdentityVerifyingTls()
            throws Exception {
        Path passwordFile = academyPasswordFile();

        productionContext(
                "cortex.sync.academy.enabled=true",
                "cortex.sources.academy.url=" + REDACTED_URL,
                "cortex.sources.academy.username=" + REDACTED_USER,
                "cortex.sources.academy.password-file=" + passwordFile
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .rootCause()
                    .hasMessageContaining("VERIFY_IDENTITY")
                    .hasMessageNotContaining(REDACTED_URL)
                    .hasMessageNotContaining(REDACTED_USER)
                    .hasMessageNotContaining(INLINE_SECRET);
        });
    }

    @Test
    void productionEnabledAcceptsFileAndVerifiedTls() throws Exception {
        Path passwordFile = academyPasswordFile();

        productionContext(
                "cortex.sync.academy.enabled=true",
                "cortex.sources.academy.url="
                        + REDACTED_URL
                        + "?sslMode=VERIFY_IDENTITY",
                "cortex.sources.academy.username=" + REDACTED_USER,
                "cortex.sources.academy.password-file=" + passwordFile
        ).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void productionRejectsVerifyCaWithoutTheExactLeafPinContract()
            throws Exception {
        Path passwordFile = academyPasswordFile();

        productionContext(
                "cortex.sync.academy.enabled=true",
                "cortex.sources.academy.url="
                        + REDACTED_URL
                        + "?sslMode=VERIFY_CA",
                "cortex.sources.academy.username=" + REDACTED_USER,
                "cortex.sources.academy.password-file=" + passwordFile
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .rootCause()
                    .hasMessageContaining("VERIFY_IDENTITY")
                    .hasMessageContaining("VERIFY_CA")
                    .hasMessageContaining("certificado folha")
                    .hasMessageNotContaining(REDACTED_URL)
                    .hasMessageNotContaining(REDACTED_USER)
                    .hasMessageNotContaining(INLINE_SECRET);
        });
    }

    @Test
    void productionAndTestProfilesStillRejectInlinePassword() {
        context(
                "production,test",
                "cortex.sources.academy.password=" + INLINE_SECRET
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .rootCause()
                    .hasMessageContaining("arquivo")
                    .hasMessageNotContaining(INLINE_SECRET);
        });
    }

    @Test
    void productionEnabledRejectsDuplicateSslMode() throws Exception {
        Path passwordFile = academyPasswordFile();

        productionContext(
                "cortex.sync.academy.enabled=true",
                "cortex.sources.academy.url="
                        + REDACTED_URL
                        + "?sslMode=VERIFY_IDENTITY"
                        + "&sslMode=VERIFY_IDENTITY",
                "cortex.sources.academy.username=" + REDACTED_USER,
                "cortex.sources.academy.password-file=" + passwordFile
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .rootCause()
                    .hasMessageContaining("VERIFY_IDENTITY")
                    .hasMessageNotContaining(REDACTED_URL)
                    .hasMessageNotContaining(REDACTED_USER);
        });
    }

    @Test
    void productionEnabledRejectsUnreadableSecretWithoutLeakingPath() {
        Path missingFile = tempDir.resolve(
                "sensitive-academy-secret-file"
        );

        productionContext(
                "cortex.sync.academy.enabled=true",
                "cortex.sources.academy.url="
                        + REDACTED_URL
                        + "?sslMode=VERIFY_IDENTITY",
                "cortex.sources.academy.username=" + REDACTED_USER,
                "cortex.sources.academy.password-file=" + missingFile
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .rootCause()
                    .hasMessageContaining("indisponivel")
                    .hasMessageNotContaining(missingFile.toString())
                    .hasMessageNotContaining(REDACTED_URL)
                    .hasMessageNotContaining(REDACTED_USER);
        });
    }

    @Test
    void productionDisabledMayStartBeforeAcademyQaConfiguration() {
        productionContext(
                "cortex.sync.academy.enabled=false"
        ).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void localEnabledAcceptsInlinePassword() {
        localContext(
                "cortex.sync.academy.enabled=true",
                "cortex.sources.academy.url=jdbc:mysql://127.0.0.1/academy",
                "cortex.sources.academy.username=academy_local",
                "cortex.sources.academy.password=" + INLINE_SECRET
        ).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void zeladoriaAdapterShouldRequireCortexReadOnlyConfiguration() {
        ZeladoriaSourceAdapter adapter =
                new ZeladoriaSourceAdapter("", "", "");

        assertThatThrownBy(() -> adapter.fetchAssets(10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CORTEX_ZELADORIA_DB_URL")
                .hasMessageContaining("CORTEX_ZELADORIA_DB_USER")
                .hasMessageContaining("CORTEX_ZELADORIA_DB_PASSWORD");
    }

    private ApplicationContextRunner productionContext(
            String... propertyValues
    ) {
        return context("production", propertyValues);
    }

    private ApplicationContextRunner localContext(String... propertyValues) {
        return context("local,test", propertyValues);
    }

    private ApplicationContextRunner context(
            String profiles,
            String... propertyValues
    ) {
        return new ApplicationContextRunner()
                .withUserConfiguration(AcademyContextConfiguration.class)
                .withPropertyValues(
                        java.util.stream.Stream.concat(
                                java.util.stream.Stream.of(
                                        "spring.profiles.active=" + profiles
                                ),
                                java.util.Arrays.stream(propertyValues)
                        ).toArray(String[]::new)
                );
    }

    private Path academyPasswordFile() throws Exception {
        Path file = tempDir.resolve("academy-password");
        Files.writeString(file, INLINE_SECRET);
        return file;
    }

    @Configuration(proxyBeanMethods = false)
    @Import(AcademySourceAdapter.class)
    static class AcademyContextConfiguration {
    }
}
