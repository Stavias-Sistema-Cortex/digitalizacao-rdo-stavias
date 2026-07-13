package com.projeto.cortex.email;

/** Single provider-neutral boundary shared by authentication and finance. */
public interface EmailGateway {

    DeliveryReceipt send(EmailMessage message);

    record DeliveryReceipt(String provider, String messageId) {

        public DeliveryReceipt {
            if (provider == null
                    || provider.isBlank()
                    || messageId == null
                    || messageId.isBlank()) {
                throw new IllegalArgumentException(
                        "Comprovante de entrega inválido."
                );
            }
        }
    }
}
