package com.projeto.cortex.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class EmailConfigurationTest {

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
}
