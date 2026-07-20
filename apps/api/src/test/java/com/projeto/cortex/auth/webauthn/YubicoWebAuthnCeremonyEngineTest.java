package com.projeto.cortex.auth.webauthn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.PapelAcesso;
import com.projeto.cortex.auth.otp.AuthenticatedIdentity;
import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.data.ByteArray;
import org.junit.jupiter.api.Test;

class YubicoWebAuthnCeremonyEngineTest {

    private static final String COLLABORATOR_ID =
            "10000000-0000-0000-0000-000000000001";

    @Test
    void registrationRequiresUvResidentKeyAndEvaluatesRandomPrfSalt() {
        ObjectMapper json = new ObjectMapper();
        YubicoWebAuthnCeremonyEngine engine = new YubicoWebAuthnCeremonyEngine(
                relyingParty(),
                json
        );
        ByteArray salt = new ByteArray(new byte[32]);

        StartedWebAuthnCeremony started = engine.startRegistration(
                new AuthenticatedIdentity(
                        COLLABORATOR_ID,
                        "Pessoa Sintética",
                        PapelAcesso.ALFA
                ),
                salt
        );

        assertThat(started.challenge().size()).isGreaterThanOrEqualTo(16);
        assertThat(started.publicKey().at("/rp/id").asText())
                .isEqualTo("cortex.example.invalid");
        assertThat(started.publicKey().at(
                "/authenticatorSelection/residentKey"
        ).asText()).isEqualTo("required");
        assertThat(started.publicKey().at(
                "/authenticatorSelection/userVerification"
        ).asText()).isEqualTo("required");
        assertThat(started.publicKey().at("/attestation").asText())
                .isEqualTo("none");
        assertThat(started.publicKey().at("/extensions/credProps").asBoolean())
                .isTrue();
        assertThat(started.publicKey().at(
                "/extensions/prf/eval/first"
        ).asText()).isEqualTo(salt.getBase64Url());
        assertThat(started.requestJson()).doesNotContain("results");
    }

    @Test
    void discoverableAuthenticationOmitsCredentialAllowlistAndRequiresUv() {
        YubicoWebAuthnCeremonyEngine engine = new YubicoWebAuthnCeremonyEngine(
                relyingParty(),
                new ObjectMapper()
        );

        StartedWebAuthnCeremony started = engine.startAuthentication();

        assertThat(started.publicKey().at("/userVerification").asText())
                .isEqualTo("required");
        assertThat(started.publicKey().has("allowCredentials")).isFalse();
        assertThat(started.publicKey().at("/rpId").asText())
                .isEqualTo("cortex.example.invalid");
    }

    private RelyingParty relyingParty() {
        WebAuthnProperties properties = new WebAuthnProperties(
                "cortex.example.invalid",
                "Cortex",
                "https://cortex.example.invalid",
                300,
                true
        );
        return WebAuthnConfiguration.buildRelyingParty(
                properties,
                mock(CredentialRepository.class)
        );
    }
}
