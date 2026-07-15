package com.projeto.cortex.mensagens;

import java.io.IOException;

public class AttachmentStorageLimitException extends IOException {

    public AttachmentStorageLimitException(String message) {
        super(message);
    }
}
