package com.projeto.cortex.intelligence.stavia.contextdocs;

import com.projeto.cortex.memory.CortexOperationalMemoryService;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class StaviaContextDocumentService {

    private static final String SOURCE =
            "STAVIA_CONTEXT_UPLOAD";
    private static final long MAX_FILE_SIZE_BYTES =
            10L * 1024L * 1024L;
    private static final int MAX_EXTRACTED_TEXT_CHARS =
            200_000;

    private final JdbcTemplate jdbcTemplate;
    private final CortexOperationalMemoryService memoryService;

    public StaviaContextDocumentService(
            JdbcTemplate jdbcTemplate,
            CortexOperationalMemoryService memoryService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.memoryService = memoryService;
    }

    @Transactional
    public StaviaContextDocumentResponse adicionar(
            String obraId,
            MultipartFile arquivo,
            String usuarioId,
            String descricao
    ) {
        if (obraId == null || obraId.isBlank()) {
            throw new IllegalArgumentException(
                    "A obra deve ser informada."
            );
        }

        if (arquivo == null || arquivo.isEmpty()) {
            throw new IllegalArgumentException(
                    "O arquivo de contexto deve ser informado."
            );
        }

        if (arquivo.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException(
                    "O arquivo de contexto deve ter no máximo 10 MB."
            );
        }

        ensureWorksiteExists(obraId.trim());

        byte[] bytes = bytes(arquivo);
        String hash = sha256(bytes);

        StaviaContextDocumentResponse existing =
                findByHash(obraId.trim(), hash);

        if (existing != null) {
            return existing;
        }

        String id = UUID.randomUUID().toString();
        String filename = filename(arquivo);
        String contentType = normalizeOptional(arquivo.getContentType());
        String extractedText =
                extractText(filename, contentType, bytes);
        String status = extractedText == null
                ? "ARQUIVO_ARMAZENADO_SEM_EXTRACAO"
                : "TEXTO_EXTRAIDO";

        jdbcTemplate.update(
                """
                INSERT INTO stavia_contexto_obra (
                    id,
                    obra_id,
                    usuario_id,
                    descricao,
                    nome_arquivo,
                    content_type,
                    tamanho_bytes,
                    hash_sha256,
                    texto_extraido,
                    arquivo_bytes,
                    status_processamento
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                obraId.trim(),
                normalizeOptional(usuarioId),
                normalizeOptional(descricao),
                filename,
                contentType,
                bytes.length,
                hash,
                extractedText,
                bytes,
                status
        );

        memoryService.registrarObjeto(
                "CONTEXTO_OBRA",
                id,
                filename,
                displayName(filename, descricao),
                "ATIVO",
                SOURCE
        );

        memoryService.registrarRelacaoAtiva(
                "CONTEXTO_OBRA",
                id,
                "OBRA",
                obraId.trim(),
                "CONTEXTUALIZA",
                SOURCE,
                "Contexto anexado para orientar respostas da Stav.IA."
        );

        memoryService.registrarEvento(
                "CONTEXTO_OBRA",
                id,
                "CONTEXTO_OBRA_ADICIONADO",
                SOURCE,
                Map.of(
                        "contextoId", id,
                        "obraId", obraId.trim(),
                        "nomeArquivo", filename,
                        "contentType", contentType == null ? "" : contentType,
                        "hashSha256", hash,
                        "textoDisponivel", extractedText != null
                )
        );

        return findById(id);
    }

    public List<StaviaContextDocumentResponse> listar(String obraId) {
        if (obraId == null || obraId.isBlank()) {
            return List.of();
        }

        return jdbcTemplate.query(
                """
                SELECT
                    id,
                    obra_id,
                    nome_arquivo,
                    content_type,
                    tamanho_bytes,
                    hash_sha256,
                    descricao,
                    status_processamento,
                    texto_extraido IS NOT NULL AS texto_disponivel,
                    criado_em
                FROM stavia_contexto_obra
                WHERE obra_id = ?
                  AND ativo = 1
                ORDER BY criado_em DESC, id DESC
                """,
                (rs, rowNum) -> response(rs),
                obraId.trim()
        );
    }

    private void ensureWorksiteExists(String obraId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM obra
                WHERE id = ?
                  AND arquivado_em IS NULL
                """,
                Integer.class,
                obraId
        );

        if (count == null || count == 0) {
            throw new IllegalArgumentException(
                    "Obra não encontrada para anexar contexto."
            );
        }
    }

    private StaviaContextDocumentResponse findByHash(
            String obraId,
            String hash
    ) {
        try {
            return jdbcTemplate.queryForObject(
                    """
                    SELECT
                        id,
                        obra_id,
                        nome_arquivo,
                        content_type,
                        tamanho_bytes,
                        hash_sha256,
                        descricao,
                        status_processamento,
                        texto_extraido IS NOT NULL AS texto_disponivel,
                        criado_em
                    FROM stavia_contexto_obra
                    WHERE obra_id = ?
                      AND hash_sha256 = ?
                      AND ativo = 1
                    """,
                    (rs, rowNum) -> response(rs),
                    obraId,
                    hash
            );
        } catch (EmptyResultDataAccessException ignored) {
            return null;
        }
    }

    private StaviaContextDocumentResponse findById(String id) {
        return jdbcTemplate.queryForObject(
                """
                SELECT
                    id,
                    obra_id,
                    nome_arquivo,
                    content_type,
                    tamanho_bytes,
                    hash_sha256,
                    descricao,
                    status_processamento,
                    texto_extraido IS NOT NULL AS texto_disponivel,
                    criado_em
                FROM stavia_contexto_obra
                WHERE id = ?
                """,
                (rs, rowNum) -> response(rs),
                id
        );
    }

    private StaviaContextDocumentResponse response(ResultSet rs)
            throws SQLException {
        return new StaviaContextDocumentResponse(
                rs.getString("id"),
                rs.getString("obra_id"),
                rs.getString("nome_arquivo"),
                rs.getString("content_type"),
                rs.getLong("tamanho_bytes"),
                rs.getString("hash_sha256"),
                rs.getString("descricao"),
                rs.getString("status_processamento"),
                rs.getBoolean("texto_disponivel"),
                rs.getTimestamp("criado_em").toLocalDateTime()
        );
    }

    private byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "Não foi possível ler o arquivo de contexto.",
                    exception
            );
        }
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 não está disponível na JVM.",
                    exception
            );
        }
    }

    private String extractText(
            String filename,
            String contentType,
            byte[] bytes
    ) {
        boolean textLike =
                contentType != null
                        && (
                        contentType.startsWith("text/")
                                || contentType.contains("json")
                                || contentType.contains("csv")
                );

        String lowerFilename = filename.toLowerCase(java.util.Locale.ROOT);

        if (!textLike
                && !lowerFilename.endsWith(".txt")
                && !lowerFilename.endsWith(".md")
                && !lowerFilename.endsWith(".csv")
                && !lowerFilename.endsWith(".json")) {
            return null;
        }

        String text = new String(bytes, StandardCharsets.UTF_8).trim();

        if (text.isBlank()) {
            return null;
        }

        return text.length() > MAX_EXTRACTED_TEXT_CHARS
                ? text.substring(0, MAX_EXTRACTED_TEXT_CHARS)
                : text;
    }

    private String filename(MultipartFile file) {
        String original = file.getOriginalFilename();

        if (original == null || original.isBlank()) {
            return "contexto-stavia";
        }

        return original.replace('\\', '/')
                .substring(original.replace('\\', '/')
                        .lastIndexOf('/') + 1)
                .trim();
    }

    private String displayName(String filename, String descricao) {
        String normalizedDescription =
                normalizeOptional(descricao);

        return normalizedDescription == null
                ? filename
                : normalizedDescription;
    }

    private String normalizeOptional(String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim();
    }
}
