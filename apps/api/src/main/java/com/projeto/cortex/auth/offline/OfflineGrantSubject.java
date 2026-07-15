package com.projeto.cortex.auth.offline;

import java.time.Instant;

record OfflineGrantSubject(String nome, Instant databaseNow) {

    @Override
    public String toString() {
        return "OfflineGrantSubject[identity=[REDACTED], databaseNow="
                + databaseNow + "]";
    }
}
