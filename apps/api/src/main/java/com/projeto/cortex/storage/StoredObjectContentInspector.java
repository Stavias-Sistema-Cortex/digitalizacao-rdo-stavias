package com.projeto.cortex.storage;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.xml.sax.SAXException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class StoredObjectContentInspector {

    private static final int SNIFF_BYTES = 8_192;
    private static final int MAX_ZIP_ENTRIES = 2_048;
    private static final int MAX_RELATIONSHIP_BYTES = 1_048_576;
    private static final Set<String> ZIP_DOCUMENT_TYPES = Set.of(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    );

    private final long maxSizeBytes;
    private final Set<String> allowedMediaTypes;

    public StoredObjectContentInspector(
            long maxSizeBytes,
            Set<String> allowedMediaTypes
    ) {
        if (maxSizeBytes <= 0) {
            throw new IllegalArgumentException("maxSizeBytes deve ser positivo.");
        }
        this.maxSizeBytes = maxSizeBytes;
        this.allowedMediaTypes = allowedMediaTypes == null
                ? Set.of()
                : allowedMediaTypes.stream()
                        .map(StoredObjectContentInspector::normalizeMediaType)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public InspectedObjectContent inspect(
            ReopenableInput source,
            long declaredSize,
            String declaredMediaType
    ) {
        if (source == null) {
            throw badRequest("O conteúdo do arquivo é obrigatório.");
        }
        if (declaredSize > maxSizeBytes) {
            throw tooLarge();
        }

        String declared = normalizeMediaType(declaredMediaType);
        MessageDigest digest = sha256();
        ByteArrayOutputStream head = new ByteArrayOutputStream(SNIFF_BYTES);
        long size = 0;
        byte[] buffer = new byte[8_192];

        try (InputStream input = source.open()) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                size += read;
                if (size > maxSizeBytes) {
                    throw tooLarge();
                }
                digest.update(buffer, 0, read);
                int remaining = SNIFF_BYTES - head.size();
                if (remaining > 0) {
                    head.write(buffer, 0, Math.min(remaining, read));
                }
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ObjectStorageException(
                    "Não foi possível validar o conteúdo do arquivo.",
                    exception
            );
        }

        byte[] inspectedHead = head.toByteArray();
        String detected = detect(inspectedHead);
        if ("application/xml".equals(detected)
                && containsUnsafeXmlDeclaration(inspectedHead)) {
            throw unsupported(
                    "XML com DTD ou declaração de entidade não é permitido."
            );
        }
        if (ZIP_DOCUMENT_TYPES.contains(declared)
                && "application/zip".equals(detected)) {
            validateOfficePackage(source, declared);
            detected = declared;
        }
        requireAllowed(declared, detected);

        return new InspectedObjectContent(
                HexFormat.of().formatHex(digest.digest()),
                size,
                declared,
                detected
        );
    }

    private void requireAllowed(String declared, String detected) {
        if (declared != null && !allowedMediaTypes.contains(declared)) {
            throw unsupported("O tipo declarado do arquivo não é permitido.");
        }
        if (!allowedMediaTypes.contains(detected)) {
            throw unsupported("O conteúdo detectado do arquivo não é permitido.");
        }
        if (declared != null && !compatible(declared, detected)) {
            throw unsupported(
                    "O tipo declarado não corresponde ao conteúdo do arquivo."
            );
        }
    }

    private void validateOfficePackage(
            ReopenableInput source,
            String declaredMediaType
    ) {
        String requiredPart = switch (declaredMediaType) {
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
                    "word/document.xml";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ->
                    "xl/workbook.xml";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation" ->
                    "ppt/presentation.xml";
            default -> throw unsupported("Pacote Office inválido.");
        };
        long maxUncompressed = Math.min(
                256L * 1024L * 1024L,
                maxSizeBytes > Long.MAX_VALUE / 10L
                        ? Long.MAX_VALUE
                        : maxSizeBytes * 10L
        );
        int entries = 0;
        long totalUncompressed = 0L;
        boolean contentTypes = false;
        boolean rootRelationships = false;
        boolean requiredDocumentPart = false;
        byte[] buffer = new byte[8_192];

        try (InputStream raw = source.open();
                ZipInputStream zip = new ZipInputStream(raw)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries++;
                if (entries > MAX_ZIP_ENTRIES) {
                    throw unsupported("Pacote Office contém itens demais.");
                }
                String name = entry.getName().replace('\\', '/');
                String lower = name.toLowerCase(Locale.ROOT);
                if (name.isBlank() || name.startsWith("/")
                        || name.contains("../") || name.equals("..")
                        || lower.endsWith("vbaproject.bin")
                        || lower.contains("/externallinks/")
                        || lower.contains("/embeddings/")
                        || lower.contains("oleobject")) {
                    throw unsupported("Pacote Office contém conteúdo inseguro.");
                }
                contentTypes |= "[Content_Types].xml".equals(name);
                rootRelationships |= "_rels/.rels".equals(name);
                requiredDocumentPart |= requiredPart.equals(name);
                if (entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }

                ByteArrayOutputStream relationship = lower.endsWith(".rels")
                        ? new ByteArrayOutputStream()
                        : null;
                int read;
                while ((read = zip.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    totalUncompressed += read;
                    if (totalUncompressed > maxUncompressed) {
                        throw tooLarge();
                    }
                    if (relationship != null) {
                        if (relationship.size() + read > MAX_RELATIONSHIP_BYTES) {
                            throw unsupported("Relacionamentos Office excedem o limite permitido.");
                        }
                        relationship.write(buffer, 0, read);
                    }
                }
                if (relationship != null) {
                    if (hasExternalRelationship(relationship.toByteArray())) {
                        throw unsupported("Relacionamentos Office externos não são permitidos.");
                    }
                }
                zip.closeEntry();
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (IOException exception) {
            throw unsupported("Pacote Office inválido.");
        }
        if (!contentTypes || !rootRelationships || !requiredDocumentPart) {
            throw unsupported("A estrutura do pacote Office é inválida.");
        }
    }

    private boolean hasExternalRelationship(byte[] xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature(
                    "http://apache.org/xml/features/disallow-doctype-decl",
                    true
            );
            factory.setFeature(
                    "http://xml.org/sax/features/external-general-entities",
                    false
            );
            factory.setFeature(
                    "http://xml.org/sax/features/external-parameter-entities",
                    false
            );
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var document = factory.newDocumentBuilder().parse(
                    new ByteArrayInputStream(xml)
            );
            var relationships = document.getElementsByTagNameNS(
                    "*", "Relationship"
            );
            for (int index = 0; index < relationships.getLength(); index++) {
                var element = relationships.item(index);
                var targetMode = element.getAttributes().getNamedItem("TargetMode");
                if (targetMode != null
                        && "external".equalsIgnoreCase(
                                targetMode.getNodeValue().strip()
                        )) {
                    return true;
                }
            }
            return false;
        } catch (ParserConfigurationException | SAXException | IOException exception) {
            throw unsupported("Relacionamentos Office inválidos.");
        }
    }

    private static boolean compatible(String declared, String detected) {
        if (declared.equals(detected)) {
            return true;
        }
        if (("application/xml".equals(declared) || "text/xml".equals(declared))
                && "application/xml".equals(detected)) {
            return true;
        }
        return declared.startsWith("text/")
                && "text/plain".equals(detected);
    }

    private static String detect(byte[] head) {
        if (startsWith(head, new byte[] {
                (byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a
        })) {
            return "image/png";
        }
        if (startsWith(head, new byte[] {
                (byte) 0xff, (byte) 0xd8, (byte) 0xff
        })) {
            return "image/jpeg";
        }
        if (startsWith(head, "GIF87a".getBytes(StandardCharsets.US_ASCII))
                || startsWith(
                        head,
                        "GIF89a".getBytes(StandardCharsets.US_ASCII)
                )) {
            return "image/gif";
        }
        if (head.length >= 12
                && startsWith(
                        head,
                        "RIFF".getBytes(StandardCharsets.US_ASCII)
                )
                && matchesAt(
                        head,
                        8,
                        "WEBP".getBytes(StandardCharsets.US_ASCII)
                )) {
            return "image/webp";
        }
        if (startsWith(head, "%PDF-".getBytes(StandardCharsets.US_ASCII))) {
            return "application/pdf";
        }
        if (startsWith(head, new byte[] {'I', 'I', 0x2a, 0x00})
                || startsWith(head, new byte[] {'M', 'M', 0x00, 0x2a})) {
            return "image/tiff";
        }
        if (startsWith(head, new byte[] {'P', 'K', 0x03, 0x04})
                || startsWith(head, new byte[] {'P', 'K', 0x05, 0x06})
                || startsWith(head, new byte[] {'P', 'K', 0x07, 0x08})) {
            return "application/zip";
        }
        if (isXmlDocument(head)) {
            return "application/xml";
        }
        if (isUtf8Text(head)) {
            return "text/plain";
        }
        return "application/octet-stream";
    }

    private static boolean isXmlDocument(byte[] head) {
        if (!isUtf8Text(head)) {
            return false;
        }
        String sample = new String(head, StandardCharsets.UTF_8);
        if (!sample.isEmpty() && sample.charAt(0) == '\ufeff') {
            sample = sample.substring(1);
        }
        sample = sample.stripLeading();
        if (sample.startsWith("<?xml")) {
            return true;
        }
        return sample.matches("(?s)<[A-Za-z_][A-Za-z0-9_.:-]*(?:\\s|/?>).*");
    }

    private static boolean containsUnsafeXmlDeclaration(byte[] head) {
        String sample = new String(head, StandardCharsets.UTF_8)
                .toUpperCase(Locale.ROOT);
        return sample.contains("<!DOCTYPE") || sample.contains("<!ENTITY");
    }

    private static boolean isUtf8Text(byte[] bytes) {
        if (bytes.length == 0) {
            return true;
        }
        for (byte value : bytes) {
            int unsigned = Byte.toUnsignedInt(value);
            if (unsigned == 0 || (unsigned < 0x09)
                    || (unsigned > 0x0d && unsigned < 0x20)) {
                return false;
            }
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException exception) {
            return false;
        }
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        return matchesAt(value, 0, prefix);
    }

    private static boolean matchesAt(byte[] value, int offset, byte[] expected) {
        if (value.length < offset + expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (value[offset + index] != expected[index]) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeMediaType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        int parameters = value.indexOf(';');
        String normalized = (parameters >= 0
                ? value.substring(0, parameters)
                : value).strip().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponível.", exception);
        }
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private static ResponseStatusException tooLarge() {
        return new ResponseStatusException(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "O arquivo excede o tamanho máximo permitido."
        );
    }

    private static ResponseStatusException unsupported(String reason) {
        return new ResponseStatusException(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                reason
        );
    }
}
