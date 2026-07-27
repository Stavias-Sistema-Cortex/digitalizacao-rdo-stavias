package com.projeto.cortex.email;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** In-process capture only; intentionally has no HTTP mailbox endpoint. */
@Component
@Profile("(local | test) & (!postgresql | postgresql-activation | legacy-finance)")
@ConditionalOnProperty(
        prefix = "cortex.email",
        name = "provider",
        havingValue = "fake"
)
public class FakeEmailGateway implements EmailGateway {

    private final CopyOnWriteArrayList<EmailMessage> messages =
            new CopyOnWriteArrayList<>();
    private final Map<String, EmailMessage> messagesByIdempotency =
            new ConcurrentHashMap<>();
    private final Map<String, DeliveryReceipt> receiptsByIdempotency =
            new ConcurrentHashMap<>();

    @Override
    public synchronized DeliveryReceipt send(EmailMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("Mensagem de e-mail inválida.");
        }
        EmailMessage existing = messagesByIdempotency.get(
                message.idempotencyKey()
        );
        if (existing != null) {
            if (!existing.equals(message)) {
                throw new IllegalStateException(
                        "Chave de idempotência reutilizada com outra mensagem."
                );
            }
            return receiptsByIdempotency.get(message.idempotencyKey());
        }
        DeliveryReceipt receipt = new DeliveryReceipt(
                "fake", UUID.randomUUID().toString()
        );
        messagesByIdempotency.put(message.idempotencyKey(), message);
        receiptsByIdempotency.put(message.idempotencyKey(), receipt);
        messages.add(message);
        return receipt;
    }

    public List<EmailMessage> capturedMessages() {
        return List.copyOf(messages);
    }
}
