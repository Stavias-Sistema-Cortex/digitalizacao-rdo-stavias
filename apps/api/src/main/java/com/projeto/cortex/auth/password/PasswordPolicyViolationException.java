package com.projeto.cortex.auth.password;

public final class PasswordPolicyViolationException
        extends IllegalArgumentException {

    PasswordPolicyViolationException(String message) {
        super(message);
    }
}
