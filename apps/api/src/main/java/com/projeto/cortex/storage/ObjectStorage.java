package com.projeto.cortex.storage;

import java.io.InputStream;

public interface ObjectStorage {

    String backend();

    void put(
            String key,
            InputStream inputStream,
            long length,
            String mediaType
    );

    ObjectStorageContent get(String key);

    void delete(String key);
}
