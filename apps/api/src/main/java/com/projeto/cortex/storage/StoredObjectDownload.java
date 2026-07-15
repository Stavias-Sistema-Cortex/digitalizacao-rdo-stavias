package com.projeto.cortex.storage;

public record StoredObjectDownload(
        StoredObjectRecord object,
        ObjectStorageContent content
) implements AutoCloseable {

    @Override
    public void close() {
        content.close();
    }
}
