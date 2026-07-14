package com.projeto.cortex.auth.otp;

/** Verification payload deliberately contains no identifier or identity data. */
public record OtpVerifyRequest(String code) {
}
