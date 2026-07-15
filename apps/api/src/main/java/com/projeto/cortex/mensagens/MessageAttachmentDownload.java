package com.projeto.cortex.mensagens;

import java.io.InputStream;

public record MessageAttachmentDownload(
        MensagemAnexoResponse attachment,
        InputStream content
) {
}
