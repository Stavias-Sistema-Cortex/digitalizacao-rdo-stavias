package com.projeto.cortex.rdos.export;

import java.util.Objects;

record RdoExportFile(byte[] content, String filename) {

    RdoExportFile {
        content = content == null ? new byte[0] : content.clone();
        filename = Objects.requireNonNull(filename, "filename");
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
