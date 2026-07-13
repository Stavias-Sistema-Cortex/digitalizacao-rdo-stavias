package com.projeto.cortex.auth.identity;

import java.util.List;

/** Versioned shape read only from the mounted provisioning secret file. */
public record ProvisioningManifest(
        int version,
        List<Identity> identities
) {

    public record Identity(String cpf, String email) {
    }
}
