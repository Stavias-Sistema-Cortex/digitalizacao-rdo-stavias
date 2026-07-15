package com.projeto.cortex.mensagens;

import java.io.IOException;
import java.io.InputStream;

public interface AttachmentStorage {

    StagedAttachment stage(InputStream content, long maxBytes) throws IOException;

    InputStream open(String storageKey) throws IOException;

    void delete(String storageKey) throws IOException;

    interface StagedAttachment extends AutoCloseable {
        long sizeBytes();

        String sha256();

        byte[] header();

        InputStream open() throws IOException;

        String commit() throws IOException;

        @Override
        void close() throws IOException;
    }
}
