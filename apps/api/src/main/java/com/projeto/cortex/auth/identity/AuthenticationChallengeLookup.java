package com.projeto.cortex.auth.identity;

import java.util.Optional;

/** Locates at most one identity eligible to receive an authentication OTP. */
public interface AuthenticationChallengeLookup {

    Optional<AuthIdentity> find(String identifier);
}
