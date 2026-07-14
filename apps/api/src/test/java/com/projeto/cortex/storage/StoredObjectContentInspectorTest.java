package com.projeto.cortex.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class StoredObjectContentInspectorTest {

    private final StoredObjectContentInspector inspector =
            new StoredObjectContentInspector(
                    32,
                    Set.of("image/png", "text/plain", "application/pdf")
            );

    @Test
    void derivesHashSizeAndMediaTypeFromTheActualBytes() {
        byte[] png = new byte[] {
                (byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a,
                0, 0, 0, 0
        };

        InspectedObjectContent result = inspector.inspect(
                () -> new ByteArrayInputStream(png),
                png.length,
                "image/png"
        );

        assertThat(result.size()).isEqualTo(png.length);
        assertThat(result.detectedMediaType()).isEqualTo("image/png");
        assertThat(result.sha256()).hasSize(64);
    }

    @Test
    void rejectsDeclaredTypeThatDoesNotMatchTheBytes() {
        byte[] text = "not a pdf".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> inspector.inspect(
                () -> new ByteArrayInputStream(text),
                text.length,
                "application/pdf"
        )).isInstanceOfSatisfying(
                ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode())
                        .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
        );
    }

    @Test
    void enforcesTheLimitAgainstActualBytesNotOnlyClientMetadata() {
        byte[] oversized = "x".repeat(33).getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> inspector.inspect(
                () -> new ByteArrayInputStream(oversized),
                1,
                "text/plain"
        )).isInstanceOfSatisfying(
                ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode())
                        .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE)
        );
    }
}
