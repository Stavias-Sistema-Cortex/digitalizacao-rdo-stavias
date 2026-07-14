package com.projeto.cortex.auth.identity;

/** Enumeration-safe signal for multiple owners of one protected identifier. */
public final class AmbiguousAuthIdentityException
        extends IllegalStateException {

    AmbiguousAuthIdentityException(String message) {
        super(message);
    }
}
