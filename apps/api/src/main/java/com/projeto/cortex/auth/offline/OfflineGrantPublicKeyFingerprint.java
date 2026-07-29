package com.projeto.cortex.auth.offline;

/** Public, non-secret fingerprint of the offline-grant signing key. */
@FunctionalInterface
public interface OfflineGrantPublicKeyFingerprint {

    String value();
}
