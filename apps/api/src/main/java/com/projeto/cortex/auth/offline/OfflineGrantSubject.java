package com.projeto.cortex.auth.offline;

import java.time.Instant;

record OfflineGrantSubject(String nome, Instant databaseNow, long authEpoch) {

    OfflineGrantSubject {
        if (authEpoch < 1) {
            throw new IllegalArgumentException(
                    "Época de autorização offline inválida."
            );
        }
    }

    @Override
    public String toString() {
        return "OfflineGrantSubject[identity=[REDACTED], databaseNow="
                + databaseNow + "]";
    }
}
