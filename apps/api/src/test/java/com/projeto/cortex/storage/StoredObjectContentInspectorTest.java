package com.projeto.cortex.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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

    @Test
    void detectsFiscalXmlAndRejectsDoctypeBeforeStorage() {
        StoredObjectContentInspector fiscal = new StoredObjectContentInspector(
                2_048,
                Set.of("application/xml", "text/xml")
        );
        byte[] xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <nfeProc><NFe><infNFe Id="NFe123"/></NFe></nfeProc>
                """.getBytes(StandardCharsets.UTF_8);

        InspectedObjectContent result = fiscal.inspect(
                () -> new ByteArrayInputStream(xml),
                xml.length,
                "application/xml"
        );

        assertThat(result.detectedMediaType()).isEqualTo("application/xml");

        byte[] unsafe = """
                <?xml version="1.0"?>
                <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <foo>&xxe;</foo>
                """.getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> fiscal.inspect(
                () -> new ByteArrayInputStream(unsafe),
                unsafe.length,
                "application/xml"
        )).isInstanceOfSatisfying(
                ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode())
                        .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
        );
    }

    @Test
    void detectsBothTiffByteOrders() {
        StoredObjectContentInspector fiscal = new StoredObjectContentInspector(
                32,
                Set.of("image/tiff")
        );
        byte[] littleEndian = new byte[] {'I', 'I', 0x2a, 0x00, 8, 0, 0, 0};
        byte[] bigEndian = new byte[] {'M', 'M', 0x00, 0x2a, 0, 0, 0, 8};

        assertThat(fiscal.inspect(
                () -> new ByteArrayInputStream(littleEndian),
                littleEndian.length,
                "image/tiff"
        ).detectedMediaType()).isEqualTo("image/tiff");
        assertThat(fiscal.inspect(
                () -> new ByteArrayInputStream(bigEndian),
                bigEndian.length,
                "image/tiff"
        ).detectedMediaType()).isEqualTo("image/tiff");
    }

    @Test
    void validatesOfficeStructureAndRejectsArbitraryZipOrExternalLinks()
            throws Exception {
        String xlsx = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        StoredObjectContentInspector office = new StoredObjectContentInspector(
                16_384,
                Set.of(xlsx)
        );
        byte[] valid = zip(
                "[Content_Types].xml", "<Types/>",
                "_rels/.rels", "<Relationships/>",
                "xl/workbook.xml", "<workbook/>"
        );
        assertThat(office.inspect(
                () -> new ByteArrayInputStream(valid), valid.length, xlsx
        ).detectedMediaType()).isEqualTo(xlsx);

        byte[] arbitrary = zip("payload.bin", "not office");
        assertThatThrownBy(() -> office.inspect(
                () -> new ByteArrayInputStream(arbitrary), arbitrary.length, xlsx
        )).isInstanceOf(ResponseStatusException.class);

        byte[] external = zip(
                "[Content_Types].xml", "<Types/>",
                "_rels/.rels",
                "<Relationships><Relationship TargetMode=\"External\"/></Relationships>",
                "xl/workbook.xml", "<workbook/>"
        );
        assertThatThrownBy(() -> office.inspect(
                () -> new ByteArrayInputStream(external), external.length, xlsx
        )).isInstanceOf(ResponseStatusException.class);

        byte[] encodedExternal = zip(
                "[Content_Types].xml", "<Types/>",
                "_rels/.rels",
                "<Relationships><Relationship TargetMode = \"Ex&#116;ernal\"/></Relationships>",
                "xl/workbook.xml", "<workbook/>"
        );
        assertThatThrownBy(() -> office.inspect(
                () -> new ByteArrayInputStream(encodedExternal),
                encodedExternal.length,
                xlsx
        )).isInstanceOf(ResponseStatusException.class);
    }

    private byte[] zip(String... entries) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (int index = 0; index < entries.length; index += 2) {
                zip.putNextEntry(new ZipEntry(entries[index]));
                zip.write(entries[index + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
