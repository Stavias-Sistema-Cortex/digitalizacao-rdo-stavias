package com.projeto.cortex.email;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** In-process capture only; intentionally has no HTTP mailbox endpoint. */
@Component
@Profile({"local", "test"})
@ConditionalOnProperty(
        prefix = "cortex.email",
        name = "provider",
        havingValue = "fake"
)
public class FakeEmailGateway implements EmailGateway {

    private final CopyOnWriteArrayList<EmailMessage> messages =
            new CopyOnWriteArrayList<>();

    @Override
    public DeliveryReceipt send(EmailMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("Mensagem de e-mail inválida.");
        }
        messages.add(message);
        return new DeliveryReceipt("fake", UUID.randomUUID().toString());
    }

    public List<EmailMessage> capturedMessages() {
        return List.copyOf(messages);
    }
}
