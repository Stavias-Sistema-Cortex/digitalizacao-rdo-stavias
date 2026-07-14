package com.projeto.cortex.email;

import com.projeto.cortex.config.SecretMaterialLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import java.util.UUID;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/** Authenticated SMTP adapter with bounded network operations. */
public final class SmtpEmailGateway implements EmailGateway {

    private static final int MAX_TIMEOUT_MS = 60_000;

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpEmailGateway(
            String host,
            int port,
            String username,
            String from,
            boolean startTls,
            int connectTimeoutMs,
            int readTimeoutMs,
            int writeTimeoutMs,
            Path passwordFile,
            String passwordInline
    ) {
        String validHost = requireText(host);
        String validUsername = requireText(username);
        String validFrom = EmailMessage.requireAddress(from);
        if (port < 1
                || port > 65_535
                || connectTimeoutMs < 1
                || connectTimeoutMs > MAX_TIMEOUT_MS
                || readTimeoutMs < 1
                || readTimeoutMs > MAX_TIMEOUT_MS
                || writeTimeoutMs < 1
                || writeTimeoutMs > MAX_TIMEOUT_MS) {
            throw incompleteConfiguration();
        }

        byte[] password = SecretMaterialLoader.required(
                passwordFile,
                emptyToNull(passwordInline),
                "SMTP"
        );
        try {
            JavaMailSenderImpl sender = new JavaMailSenderImpl();
            sender.setHost(validHost);
            sender.setPort(port);
            sender.setUsername(validUsername);
            sender.setPassword(new String(password, StandardCharsets.UTF_8));

            Properties properties = sender.getJavaMailProperties();
            properties.put("mail.smtp.auth", "true");
            properties.put("mail.smtp.starttls.enable",
                    Boolean.toString(startTls));
            properties.put("mail.smtp.starttls.required",
                    Boolean.toString(startTls));
            properties.put("mail.smtp.ssl.checkserveridentity", "true");
            properties.put("mail.smtp.connectiontimeout",
                    Integer.toString(connectTimeoutMs));
            properties.put("mail.smtp.timeout",
                    Integer.toString(readTimeoutMs));
            properties.put("mail.smtp.writetimeout",
                    Integer.toString(writeTimeoutMs));

            this.mailSender = sender;
            this.from = validFrom;
        } finally {
            Arrays.fill(password, (byte) 0);
        }
    }

    SmtpEmailGateway(JavaMailSender mailSender, String from) {
        if (mailSender == null) {
            throw incompleteConfiguration();
        }
        this.mailSender = mailSender;
        this.from = EmailMessage.requireAddress(from);
    }

    @Override
    public DeliveryReceipt send(EmailMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("Mensagem de e-mail inválida.");
        }
        SimpleMailMessage outbound = new SimpleMailMessage();
        outbound.setFrom(from);
        outbound.setTo(message.recipient());
        outbound.setSubject(message.subject());
        outbound.setText(message.textBody());
        if (message.replyTo() != null) {
            outbound.setReplyTo(message.replyTo());
        }
        try {
            mailSender.send(outbound);
            return new DeliveryReceipt("smtp", UUID.randomUUID().toString());
        } catch (MailException exception) {
            throw new EmailGateway.DeliveryException(
                    "SMTP_RESULTADO_AMBIGUO",
                    "Falha no envio de e-mail pelo provider SMTP.",
                    false
            );
        }
    }

    private static String requireText(String value) {
        if (value == null
                || value.isBlank()
                || value.contains("\r")
                || value.contains("\n")) {
            throw incompleteConfiguration();
        }
        return value.strip();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static IllegalStateException incompleteConfiguration() {
        return new IllegalStateException(
                "Configuração do provider SMTP incompleta."
        );
    }
}
