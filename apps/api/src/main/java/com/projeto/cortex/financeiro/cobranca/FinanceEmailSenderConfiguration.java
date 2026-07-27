package com.projeto.cortex.financeiro.cobranca;

import com.projeto.cortex.financeiro.core.FinanceValidation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Binds database sender profiles to the single authenticated provider config. */
@Component
@Profile("legacy-finance")
public final class FinanceEmailSenderConfiguration {

    private final String configuredProfileKey;

    public FinanceEmailSenderConfiguration(
            @Value("${cortex.email.sender-profile-key:}")
            String configuredProfileKey
    ) {
        this.configuredProfileKey = configuredProfileKey == null
                ? "" : configuredProfileKey.strip();
    }

    public void requireConfiguredProfile(String databaseProfileKey) {
        String requested = FinanceValidation.requiredText(
                databaseProfileKey,
                "configuracaoRemetenteChave",
                120
        );
        if (configuredProfileKey.isBlank()
                || !constantTimeEquals(configuredProfileKey, requested)) {
            throw new IllegalStateException(
                    "O perfil remetente não corresponde ao provider autenticado."
            );
        }
    }

    private boolean constantTimeEquals(String left, String right) {
        byte[] a = left.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] b = right.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(a, b);
    }
}
