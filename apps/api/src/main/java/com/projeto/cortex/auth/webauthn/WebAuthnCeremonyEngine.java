package com.projeto.cortex.auth.webauthn;

import com.fasterxml.jackson.databind.JsonNode;
import com.projeto.cortex.auth.otp.AuthenticatedIdentity;
import com.yubico.webauthn.data.ByteArray;

interface WebAuthnCeremonyEngine {

    StartedWebAuthnCeremony startRegistration(
            AuthenticatedIdentity identity,
            ByteArray prfSalt
    );

    VerifiedWebAuthnRegistration finishRegistration(
            String requestJson,
            JsonNode credential
    );

    StartedWebAuthnCeremony startAuthentication();

    VerifiedWebAuthnAuthentication finishAuthentication(
            String requestJson,
            JsonNode credential
    );
}
