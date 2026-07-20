package com.projeto.cortex.storage;

import java.io.InputStream;
import java.util.Objects;

public record ObjectStorageContent(
        InputStream inputStream,
        long length,
        String mediaType
) implements AutoCloseable {

    public ObjectStorageContent {
        Objects.requireNonNull(inputStream, "inputStream");
        Objects.requireNonNull(mediaType, "mediaType");
    }

    @Override
    public void close() {
        try {
            inputStream.close();
        } catch (java.io.IOException exception) {
            throw new ObjectStorageException(
                    "Não foi possível fechar o conteúdo armazenado.",
                    exception
            );
        }
    }
}
