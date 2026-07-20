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

    /** Sanitized provider failure with an explicit retry-safety decision. */
    final class DeliveryException extends IllegalStateException {

        private final String category;
        private final boolean knownSafeToRetry;

        public DeliveryException(
                String category,
                String sanitizedMessage,
                boolean knownSafeToRetry
        ) {
            super(sanitizedMessage);
            if (category == null || category.isBlank()
                    || sanitizedMessage == null || sanitizedMessage.isBlank()) {
                throw new IllegalArgumentException(
                        "Falha de entrega de e-mail inválida."
                );
            }
            this.category = category;
            this.knownSafeToRetry = knownSafeToRetry;
        }

        public String category() {
            return category;
        }

        public boolean knownSafeToRetry() {
            return knownSafeToRetry;
        }
    }
}
