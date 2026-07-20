package com.projeto.cortex.auth.otp;

/** Public request deliberately names a generic identifier, not CPF. */
public record OtpChallengeRequest(String identifier) {
}
