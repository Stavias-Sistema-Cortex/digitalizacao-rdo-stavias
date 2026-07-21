package com.projeto.cortex.auth.otp;

/** Profile-selected canonicalization boundary for public OTP identifiers. */
public interface AuthenticationIdentifierNormalizer {

    String INVALID_VALUE = "<invalid>";

    /**
     * Returns a bounded canonical identifier or the fixed invalid sentinel.
     * Implementations must never retain, log, or return the rejected raw value.
     */
    String canonicalize(String identifier);

    default boolean isInvalid(String identifier) {
        return INVALID_VALUE.equals(identifier);
    }
}
