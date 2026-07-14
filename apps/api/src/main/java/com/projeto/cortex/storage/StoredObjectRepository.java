package com.projeto.cortex.storage;

import java.util.Optional;

public interface StoredObjectRepository {

    Optional<StoredObjectRecord> findAvailableByDedupeKey(String dedupeKey);

    Optional<StoredObjectRecord> findAvailableById(String id);

    boolean reserve(StoredObjectRecord object);

    void markAvailable(String id);

    void markQuarantined(String id);
}
