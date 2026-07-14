package com.projeto.cortex.email;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

/** Provider-neutral outbound e-mail payload. */
public record EmailMessage(
        String recipient,
        String subject,
        String textBody,
        String idempotencyKey,
        String replyTo
) {

    public EmailMessage(
            String recipient,
            String subject,
            String textBody,
            String idempotencyKey
    ) {
        this(recipient, subject, textBody, idempotencyKey, null);
    }

    public static boolean isValidRecipient(String raw) {
        try {
            requireAddress(raw);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public EmailMessage {
        recipient = requireAddress(recipient);
        if (subject == null
                || subject.isBlank()
                || subject.contains("\r")
                || subject.contains("\n")) {
            throw invalidMessage();
        }
        if (textBody == null) {
            throw invalidMessage();
        }
        if (idempotencyKey == null
                || idempotencyKey.isBlank()
                || idempotencyKey.length() > 200
                || idempotencyKey.contains("\r")
                || idempotencyKey.contains("\n")) {
            throw invalidMessage();
        }
        replyTo = replyTo == null || replyTo.isBlank()
                ? null : requireAddress(replyTo);
    }

    static String requireAddress(String raw) {
        String value = raw == null ? null : raw.strip();
        if (value == null
                || value.isBlank()
                || value.length() > 320
                || value.contains("\r")
                || value.contains("\n")) {
            throw invalidMessage();
        }
        try {
            InternetAddress address = new InternetAddress(value, true);
            address.validate();
            if (!value.equals(address.getAddress())) {
                throw invalidMessage();
            }
            return value;
        } catch (AddressException exception) {
            throw invalidMessage();
        }
    }

    private static IllegalArgumentException invalidMessage() {
        return new IllegalArgumentException("Mensagem de e-mail inválida.");
    }

    @Override
    public String toString() {
        return "EmailMessage[redacted]";
    }
}
