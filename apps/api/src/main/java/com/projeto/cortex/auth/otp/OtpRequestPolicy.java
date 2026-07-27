package com.projeto.cortex.auth.otp;

/** Bounded public e-mail OTP payload policy applied before MVC JSON parsing. */
public record OtpRequestPolicy(int maxBodyBytes) {

    public OtpRequestPolicy {
        if (maxBodyBytes < 512 || maxBodyBytes > 16_384) {
            throw new IllegalArgumentException(
                    "Limite de payload OTP inválido."
            );
        }
    }
}
