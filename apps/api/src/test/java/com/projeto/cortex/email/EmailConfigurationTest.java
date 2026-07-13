package com.projeto.cortex.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class EmailConfigurationTest {

    private static final String TEST_PASSWORD =
            "test-only-smtp-secret-material-0001";

    @TempDir
    Path tempDir;

    @Test
    void productionRejectsFakeProvider() {
        assertThatThrownBy(() -> EmailConfiguration.validateProvider(
                "fake",
                false
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fake");
    }

    @Test
    void fakeProviderIsAcceptedOnlyForLocalOrTest() {
        assertThatCode(() -> EmailConfiguration.validateProvider(
                "fake",
                true
        )).doesNotThrowAnyException();
    }

    @Test
    void productionContextFailsClosedForFakeProvider() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        EmailConfiguration.class,
                        FakeEmailGateway.class
                )
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "cortex.email.provider=fake"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .rootCause()
                            .hasMessageContaining("fake");
                });
    }

    @Test
    void mixedProductionAndTestProfilesRejectFakeProvider() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        EmailConfiguration.class,
                        FakeEmailGateway.class
                )
                .withPropertyValues(
                        "spring.profiles.active=prod,test",
                        "cortex.email.provider=fake"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .rootCause()
                            .hasMessageContaining("fake");
                });
    }

    @Test
    void productionSmtpRejectsInlinePassword() {
        new ApplicationContextRunner()
                .withUserConfiguration(EmailConfiguration.class)
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "cortex.email.provider=smtp",
                        "cortex.email.from=sender@example.invalid",
                        "cortex.email.smtp.host=smtp.example.invalid",
                        "cortex.email.smtp.username=sender@example.invalid",
                        "cortex.email.smtp.password-inline=" + TEST_PASSWORD
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .rootCause()
                            .hasMessageContaining("arquivo")
                            .hasMessageNotContaining(TEST_PASSWORD);
                });
    }

    @Test
    void productionSmtpRequiresStartTls() throws Exception {
        Path passwordFile = smtpPasswordFile();

        new ApplicationContextRunner()
                .withUserConfiguration(EmailConfiguration.class)
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "cortex.email.provider=smtp",
                        "cortex.email.from=sender@example.invalid",
                        "cortex.email.smtp.host=smtp.example.invalid",
                        "cortex.email.smtp.username=sender@example.invalid",
                        "cortex.email.smtp.password-file=" + passwordFile,
                        "cortex.email.smtp.start-tls=false"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .rootCause()
                            .hasMessageContaining("STARTTLS")
                            .hasMessageNotContaining(TEST_PASSWORD);
                });
    }

    @Test
    void productionContextFailsClosedForIncompleteSmtp() {
        new ApplicationContextRunner()
                .withUserConfiguration(EmailConfiguration.class)
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "cortex.email.provider=smtp",
                        "cortex.email.smtp.host=smtp.example.invalid"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .rootCause()
                            .hasMessageContaining("SMTP")
                            .hasMessageNotContaining("password");
                });
    }

    @Test
    void fakeProviderCapturesOnlyInProcessMessages() {
        FakeEmailGateway gateway = new FakeEmailGateway();
        EmailMessage message = new EmailMessage(
                "destinatario@example.invalid",
                "Assunto sintético",
                "Corpo sintético",
                "otp:synthetic-1"
        );

        EmailGateway.DeliveryReceipt receipt = gateway.send(message);
        List<EmailMessage> captured = gateway.capturedMessages();

        assertThat(receipt.provider()).isEqualTo("fake");
        assertThat(receipt.messageId()).isNotBlank();
        assertThat(captured).containsExactly(message);
        assertThatThrownBy(() -> captured.add(message))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void applicationUsesAuthoritativeSmtpEnvironmentNames() throws Exception {
        String configuration = Files.readString(
                Path.of("src/main/resources/application.yml")
        );

        assertThat(configuration)
                .contains("${CORTEX_SMTP_FROM:}")
                .contains("${CORTEX_SMTP_HOST:}")
                .contains("${CORTEX_SMTP_PORT:587}")
                .contains("${CORTEX_SMTP_USERNAME:}")
                .contains("${CORTEX_SMTP_PASSWORD_FILE:}")
                .contains("${CORTEX_SMTP_PASSWORD:}")
                .contains("${CORTEX_SMTP_START_TLS:true}")
                .doesNotContain("CORTEX_EMAIL_SMTP_")
                .doesNotContain("CORTEX_EMAIL_FROM");
    }

    private Path smtpPasswordFile() throws Exception {
        Path passwordFile = tempDir.resolve("smtp-password");
        Files.writeString(passwordFile, TEST_PASSWORD);
        return passwordFile;
    }
}
