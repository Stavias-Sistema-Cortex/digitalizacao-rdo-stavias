package com.projeto.cortex.auth.offline;

/**
 * Signed, short-lived authorization snapshot used only to unlock local data.
 * It is not an online session token and cannot authenticate API requests.
 */
public record OfflineGrant(
        String keyId,
        String payload,
        String signature,
        String publicKeySpki
) {

    @Override
    public String toString() {
        return "OfflineGrant[keyId=" + keyId
                + ", payload=[REDACTED], signature=[REDACTED]"
                + ", publicKeySpki=[REDACTED]]";
    }
}
