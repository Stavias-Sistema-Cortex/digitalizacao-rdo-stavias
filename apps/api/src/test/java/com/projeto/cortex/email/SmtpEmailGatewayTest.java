package com.projeto.cortex.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.ReflectionTestUtils;

class SmtpEmailGatewayTest {

    private static final String CONFIGURED_FROM =
            "remetente-autorizado@example.invalid";
    private static final String RECIPIENT =
            "destinatario@example.invalid";
    private static final String BODY = "Corpo confidencial sintético";

    @TempDir
    Path tempDir;

    @Test
    void constructsAuthenticatedSenderFromSecretFileAndBoundedProperties()
            throws Exception {
        Path passwordFile = tempDir.resolve("smtp-password");
        Files.writeString(
                passwordFile,
                "test-only-smtp-secret-material-0001"
        );

        SmtpEmailGateway gateway = new SmtpEmailGateway(
                "smtp.example.invalid",
                2525,
                "smtp-user@example.invalid",
                CONFIGURED_FROM,
                true,
                1100,
                1200,
                1300,
                passwordFile,
                null
        );

        JavaMailSenderImpl sender = (JavaMailSenderImpl)
                ReflectionTestUtils.getField(gateway, "mailSender");
        assertThat(sender).isNotNull();
        assertThat(sender.getHost()).isEqualTo("smtp.example.invalid");
        assertThat(sender.getPort()).isEqualTo(2525);
        assertThat(sender.getUsername())
                .isEqualTo("smtp-user@example.invalid");
        assertThat(sender.getPassword()).isNotBlank();
        assertThat(sender.getJavaMailProperties())
                .containsEntry("mail.smtp.auth", "true")
                .containsEntry("mail.smtp.starttls.enable", "true")
                .containsEntry("mail.smtp.starttls.required", "true")
                .containsEntry(
                        "mail.smtp.ssl.checkserveridentity",
                        "true"
                )
                .containsEntry("mail.smtp.connectiontimeout", "1100")
                .containsEntry("mail.smtp.timeout", "1200")
                .containsEntry("mail.smtp.writetimeout", "1300");
    }

    @Test
    void rejectsTimeoutsAboveSixtySeconds() throws Exception {
        Path passwordFile = tempDir.resolve("smtp-password-max-timeout");
        Files.writeString(
                passwordFile,
                "test-only-smtp-secret-material-0001"
        );

        assertThatThrownBy(() -> new SmtpEmailGateway(
                "smtp.example.invalid",
                2525,
                "smtp-user@example.invalid",
                CONFIGURED_FROM,
                true,
                60_001,
                1200,
                1300,
                passwordFile,
                null
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SMTP");
    }

    @Test
    void sendsConfiguredFromAndMessagePayloadOnlyToMailSender() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        SmtpEmailGateway gateway = new SmtpEmailGateway(
                mailSender,
                CONFIGURED_FROM
        );
        EmailMessage message = new EmailMessage(
                RECIPIENT,
                "Assunto sintético",
                BODY,
                "financeiro:synthetic-1"
        );

        EmailGateway.DeliveryReceipt receipt = gateway.send(message);

        ArgumentCaptor<SimpleMailMessage> outbound =
                ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(outbound.capture());
        assertThat(outbound.getValue().getFrom()).isEqualTo(CONFIGURED_FROM);
        assertThat(outbound.getValue().getTo()).containsExactly(RECIPIENT);
        assertThat(outbound.getValue().getSubject())
                .isEqualTo("Assunto sintético");
        assertThat(outbound.getValue().getText()).isEqualTo(BODY);
        assertThat(receipt.provider()).isEqualTo("smtp");
        assertThat(receipt.messageId()).isNotBlank();
    }

    @Test
    void sanitizesMailFailureWithoutRecipientBodyOrProviderCause() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        doThrow(new MailSendException(
                "transport rejected " + RECIPIENT + " " + BODY
        )).when(mailSender).send(any(SimpleMailMessage.class));
        SmtpEmailGateway gateway = new SmtpEmailGateway(
                mailSender,
                CONFIGURED_FROM
        );
        EmailMessage message = new EmailMessage(
                RECIPIENT,
                "Assunto sintético",
                BODY,
                "otp:synthetic-2"
        );

        assertThatThrownBy(() -> gateway.send(message))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Falha no envio de e-mail pelo provider SMTP.")
                .hasMessageNotContaining(RECIPIENT)
                .hasMessageNotContaining(BODY)
                .hasNoCause();
    }

    @Test
    void deliveryReceiptDoesNotClaimWebhookDeliveryState() {
        assertThat(EmailGateway.DeliveryReceipt.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("provider", "messageId");
    }
}
