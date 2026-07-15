package com.projeto.cortex.storage;

public record StoredObjectUploadResult(
        StoredObjectRecord object,
        boolean created
) {
}
