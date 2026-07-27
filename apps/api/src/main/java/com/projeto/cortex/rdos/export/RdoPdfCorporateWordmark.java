package com.projeto.cortex.rdos.export;

import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;

final class RdoPdfCorporateWordmark {

    private static final String RESOURCE =
            "/rdo/export/corporate-wordmark.png.b64";
    private static final int MAX_ENCODED_BYTES = 128 * 1024;
    private static final int MAX_PNG_BYTES = 96 * 1024;
    private static final byte[] PNG_BYTES = decodeResource();

    private RdoPdfCorporateWordmark() {
    }

    static byte[] bytes() {
        return PNG_BYTES;
    }

    private static byte[] decodeResource() {
        try (InputStream input =
                RdoPdfCorporateWordmark.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IOException(
                        "A marca corporativa do PDF não foi empacotada."
                );
            }
            byte[] encoded = input.readNBytes(MAX_ENCODED_BYTES + 1);
            if (encoded.length > MAX_ENCODED_BYTES) {
                throw new IOException(
                        "A marca corporativa codificada excede o limite."
                );
            }
            byte[] decoded;
            try {
                decoded = Base64.getMimeDecoder().decode(encoded);
            } catch (IllegalArgumentException exception) {
                throw new IOException(
                        "A marca corporativa codificada é inválida.",
                        exception
                );
            }
            if (decoded.length == 0 || decoded.length > MAX_PNG_BYTES) {
                throw new IOException(
                        "A marca corporativa decodificada excede o limite."
                );
            }
            return decoded;
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
