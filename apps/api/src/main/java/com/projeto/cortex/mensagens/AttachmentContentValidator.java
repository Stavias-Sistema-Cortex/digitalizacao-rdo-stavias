package com.projeto.cortex.mensagens;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
class AttachmentContentValidator {

    private static final String DOCX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final Map<String, Set<String>> EXTENSIONS = Map.of(
            "image/jpeg", Set.of("jpg", "jpeg"),
            "image/png", Set.of("png"),
            "image/webp", Set.of("webp"),
            "application/pdf", Set.of("pdf"),
            "text/plain", Set.of("txt"),
            DOCX, Set.of("docx"),
            XLSX, Set.of("xlsx")
    );

    private final MessageAttachmentProperties properties;

    AttachmentContentValidator(MessageAttachmentProperties properties) {
        this.properties = properties;
    }

    AttachmentValidationResult validate(
            MessageAttachmentRecord attachment,
            String uploadedFilename,
            String uploadedMimeType,
            AttachmentStorage.StagedAttachment staged
    ) throws IOException {
        if (staged.sizeBytes() != attachment.sizeBytes()) {
            throw badRequest("O tamanho real do anexo diverge do informado.");
        }
        if (!staged.sha256().equalsIgnoreCase(attachment.sha256())) {
            throw badRequest("O hash SHA-256 do anexo diverge do informado.");
        }

        String declaredMime = normalizeMime(attachment.mimeType());
        String browserMime = normalizeMime(uploadedMimeType);
        List<String> allowed = properties.getAllowedMimeTypes().stream()
                .map(this::normalizeMime)
                .toList();
        if (!allowed.contains(declaredMime)) {
            throw badRequest("O tipo MIME do anexo não é permitido.");
        }
        if (!declaredMime.equals(browserMime)) {
            throw badRequest("O MIME enviado diverge do MIME declarado.");
        }

        String safeOriginal = safeFilename(attachment.originalFilename());
        String safeUploaded = safeFilename(uploadedFilename);
        requireExtension(safeOriginal, declaredMime);
        requireExtension(safeUploaded, declaredMime);
        if (!matchesMagic(declaredMime, staged)) {
            throw badRequest("O conteúdo real do arquivo não corresponde ao tipo declarado.");
        }
        return new AttachmentValidationResult(safeOriginal, declaredMime);
    }

    private boolean matchesMagic(
            String mime,
            AttachmentStorage.StagedAttachment staged
    ) throws IOException {
        byte[] header = staged.header();
        return switch (mime) {
            case "image/jpeg" -> startsWith(header, 0xff, 0xd8, 0xff);
            case "image/png" -> startsWith(
                    header, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
            );
            case "image/webp" -> asciiAt(header, 0, "RIFF")
                    && asciiAt(header, 8, "WEBP");
            case "application/pdf" -> asciiAt(header, 0, "%PDF-");
            case "text/plain" -> validUtf8Text(staged);
            case DOCX -> validOfficeZip(staged, "word/");
            case XLSX -> validOfficeZip(staged, "xl/");
            default -> false;
        };
    }

    private boolean validUtf8Text(AttachmentStorage.StagedAttachment staged)
            throws IOException {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (InputStreamReader reader = new InputStreamReader(staged.open(), decoder)) {
            char[] chars = new char[4096];
            int read;
            while ((read = reader.read(chars)) != -1) {
                for (int index = 0; index < read; index++) {
                    char character = chars[index];
                    if (character == 0
                            || (Character.isISOControl(character)
                            && character != '\n'
                            && character != '\r'
                            && character != '\t')) {
                        return false;
                    }
                }
            }
            return true;
        } catch (CharacterCodingException exception) {
            return false;
        }
    }

    private boolean validOfficeZip(
            AttachmentStorage.StagedAttachment staged,
            String requiredPrefix
    ) throws IOException {
        boolean contentTypes = false;
        boolean officeDirectory = false;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(staged.open())) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null && entries++ < 2_000) {
                String name = entry.getName();
                contentTypes |= "[Content_Types].xml".equals(name);
                officeDirectory |= name.startsWith(requiredPrefix);
                if (contentTypes && officeDirectory) {
                    return true;
                }
            }
        } catch (IOException exception) {
            return false;
        }
        return false;
    }

    private void requireExtension(String filename, String mime) {
        Set<String> allowed = EXTENSIONS.getOrDefault(mime, Set.of());
        int dot = filename.lastIndexOf('.');
        String extension = dot < 0
                ? ""
                : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!allowed.contains(extension)) {
            throw badRequest("A extensão do arquivo diverge do tipo MIME declarado.");
        }
    }

    private String safeFilename(String rawFilename) {
        String raw = rawFilename == null ? "" : rawFilename;
        String basename = raw.replace('\\', '/');
        basename = basename.substring(basename.lastIndexOf('/') + 1);
        basename = Normalizer.normalize(basename.trim(), Normalizer.Form.NFKC)
                .replaceAll("[\\p{Cntrl}]", "_")
                .replaceAll("[^\\p{L}\\p{N}._ -]", "_");
        basename = basename.replaceAll("^\\.+", "");
        if (basename.isBlank()) {
            throw badRequest("O nome do arquivo é inválido.");
        }
        return basename.substring(0, Math.min(180, basename.length()));
    }

    private String normalizeMime(String mime) {
        if (mime == null) {
            return "";
        }
        int separator = mime.indexOf(';');
        String value = separator < 0 ? mime : mime.substring(0, separator);
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean startsWith(byte[] bytes, int... expected) {
        if (bytes.length < expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if ((bytes[index] & 0xff) != expected[index]) {
                return false;
            }
        }
        return true;
    }

    private boolean asciiAt(byte[] bytes, int offset, String expected) {
        byte[] ascii = expected.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length < offset + ascii.length) {
            return false;
        }
        for (int index = 0; index < ascii.length; index++) {
            if (bytes[offset + index] != ascii[index]) {
                return false;
            }
        }
        return true;
    }

    private ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
