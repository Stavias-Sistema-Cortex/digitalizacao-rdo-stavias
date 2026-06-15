package com.projeto.cortex.colaboradores;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class ColaboradorImportService {

    private static final String CONNECTOR_NAME = "acad_colaborador_import";
    private static final String BANCO_ORIGEM = "dbstavias_acad";
    private static final String TABELA_ORIGEM = "usuarios";

    private static final String SQL_SELECT_USUARIOS = """
            SELECT
                u.id_usuario,
                u.nome,
                u.email,
                u.ativo,
                u.id_grupo,
                g.nome AS nome_grupo,
                u.id_perfil,
                p.nome_perfil,
                u.criado_em
            FROM usuarios u
            LEFT JOIN grupos g
                ON g.id_grupo = u.id_grupo
            LEFT JOIN perfil p
                ON p.id_perfil = u.id_perfil
            ORDER BY u.id_usuario
            """;

    private final JdbcTemplate jdbcTemplate;

    @Value("${cortex.sources.acad.url:}")
    private String acadUrl;

    @Value("${cortex.sources.acad.username:}")
    private String acadUsername;

    @Value("${cortex.sources.acad.password:}")
    private String acadPassword;

    public ColaboradorImportService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ColaboradorImportResult importarUsuariosDaAcademy() {
        String syncRunId = UUID.randomUUID().toString();
        LocalDateTime iniciadoEm = LocalDateTime.now();

        criarExecucaoSync(syncRunId, iniciadoEm);

        int registrosLidos = 0;
        int registrosInseridos = 0;
        int registrosAtualizados = 0;

        try {
            validarConfiguracaoAcademy();

            try (
                    Connection connection = DriverManager.getConnection(acadUrl, acadUsername, acadPassword);
                    PreparedStatement statement = connection.prepareStatement(SQL_SELECT_USUARIOS);
                    ResultSet resultSet = statement.executeQuery()
            ) {
                while (resultSet.next()) {
                    UsuarioAcademy usuario = lerUsuario(resultSet);
                    registrosLidos++;

                    String hashOrigem = gerarHash(usuario);
                    String hashExistente = buscarHashExistente(usuario.pkOrigem());

                    if (hashExistente == null) {
                        registrosInseridos++;
                    } else if (!hashExistente.equals(hashOrigem)) {
                        registrosAtualizados++;
                    }

                    salvarOuAtualizar(usuario, hashOrigem);
                }
            }

            int registrosDesativados = desativarAusentes(iniciadoEm);

            finalizarExecucaoComSucesso(
                    syncRunId,
                    registrosLidos,
                    registrosInseridos,
                    registrosAtualizados,
                    registrosDesativados
            );

            atualizarCheckpointComSucesso();

            return new ColaboradorImportResult(
                    syncRunId,
                    BANCO_ORIGEM,
                    TABELA_ORIGEM,
                    "SUCCESS",
                    registrosLidos,
                    registrosLidos,
                    registrosInseridos,
                    registrosAtualizados,
                    registrosDesativados,
                    null
            );
        } catch (Exception exception) {
            String mensagemErro = limitarTexto(mensagemRaiz(exception), 1000);
            finalizarExecucaoComFalha(syncRunId, mensagemErro);
            atualizarCheckpointComFalha(mensagemErro);

            throw new RuntimeException("Falha ao importar colaboradores da Academy.", exception);
        }
    }

    private UsuarioAcademy lerUsuario(ResultSet resultSet) throws Exception {
        int idUsuario = resultSet.getInt("id_usuario");
        String pkOrigem = String.valueOf(idUsuario);

        Timestamp criadoEmTimestamp = resultSet.getTimestamp("criado_em");
        LocalDateTime criadoEmOrigem = criadoEmTimestamp == null
                ? null
                : criadoEmTimestamp.toLocalDateTime();

        return new UsuarioAcademy(
                stableColaboradorId(pkOrigem),
                pkOrigem,
                pkOrigem,
                resultSet.getString("nome"),
                resultSet.getString("email"),
                getNullableString(resultSet, "id_grupo"),
                resultSet.getString("nome_grupo"),
                getNullableString(resultSet, "id_perfil"),
                resultSet.getString("nome_perfil"),
                resultSet.getBoolean("ativo"),
                criadoEmOrigem
        );
    }

    private void salvarOuAtualizar(UsuarioAcademy usuario, String hashOrigem) {
        jdbcTemplate.update("""
                INSERT INTO colaborador (
                    id,
                    banco_origem,
                    tabela_origem,
                    pk_origem,
                    codigo_colaborador,
                    nome,
                    email,
                    id_grupo_origem,
                    nome_grupo,
                    id_perfil_origem,
                    nome_perfil,
                    ativo,
                    criado_em_origem,
                    atualizado_em_origem,
                    hash_origem,
                    visto_por_ultimo_em,
                    deletado_em
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, CURRENT_TIMESTAMP(6), NULL)
                ON DUPLICATE KEY UPDATE
                    codigo_colaborador = VALUES(codigo_colaborador),
                    nome = VALUES(nome),
                    email = VALUES(email),
                    id_grupo_origem = VALUES(id_grupo_origem),
                    nome_grupo = VALUES(nome_grupo),
                    id_perfil_origem = VALUES(id_perfil_origem),
                    nome_perfil = VALUES(nome_perfil),
                    ativo = VALUES(ativo),
                    criado_em_origem = VALUES(criado_em_origem),
                    atualizado_em_origem = VALUES(atualizado_em_origem),
                    atualizado_em = IF(hash_origem <> VALUES(hash_origem), CURRENT_TIMESTAMP(6), atualizado_em),
                    versao_linha = IF(hash_origem <> VALUES(hash_origem), versao_linha + 1, versao_linha),
                    hash_origem = VALUES(hash_origem),
                    visto_por_ultimo_em = CURRENT_TIMESTAMP(6),
                    deletado_em = NULL
                """,
                usuario.id(),
                BANCO_ORIGEM,
                TABELA_ORIGEM,
                usuario.pkOrigem(),
                usuario.codigoColaborador(),
                usuario.nome(),
                usuario.email(),
                usuario.idGrupoOrigem(),
                usuario.nomeGrupo(),
                usuario.idPerfilOrigem(),
                usuario.nomePerfil(),
                usuario.ativo(),
                usuario.criadoEmOrigem(),
                hashOrigem
        );
    }

    private String buscarHashExistente(String pkOrigem) {
        List<String> hashes = jdbcTemplate.query(
                """
                SELECT hash_origem
                FROM colaborador
                WHERE banco_origem = ?
                  AND tabela_origem = ?
                  AND pk_origem = ?
                """,
                (resultSet, rowNumber) -> resultSet.getString("hash_origem"),
                BANCO_ORIGEM,
                TABELA_ORIGEM,
                pkOrigem
        );

        return hashes.isEmpty() ? null : hashes.get(0);
    }

    private int desativarAusentes(LocalDateTime iniciadoEm) {
        return jdbcTemplate.update("""
                UPDATE colaborador
                SET
                    ativo = 0,
                    deletado_em = COALESCE(deletado_em, CURRENT_TIMESTAMP(6)),
                    atualizado_em = CURRENT_TIMESTAMP(6),
                    versao_linha = versao_linha + 1
                WHERE banco_origem = ?
                  AND tabela_origem = ?
                  AND visto_por_ultimo_em < ?
                  AND deletado_em IS NULL
                """,
                BANCO_ORIGEM,
                TABELA_ORIGEM,
                iniciadoEm
        );
    }

    private void criarExecucaoSync(String syncRunId, LocalDateTime iniciadoEm) {
        jdbcTemplate.update("""
                INSERT INTO source_sync_run (
                    id,
                    connector_name,
                    source_database,
                    source_table,
                    started_at,
                    status
                )
                VALUES (?, ?, ?, ?, ?, 'RUNNING')
                """,
                syncRunId,
                CONNECTOR_NAME,
                BANCO_ORIGEM,
                TABELA_ORIGEM,
                iniciadoEm
        );
    }

    private void finalizarExecucaoComSucesso(
            String syncRunId,
            int registrosLidos,
            int registrosInseridos,
            int registrosAtualizados,
            int registrosDesativados
    ) {
        jdbcTemplate.update("""
                UPDATE source_sync_run
                SET
                    finished_at = CURRENT_TIMESTAMP(6),
                    status = 'SUCCESS',
                    records_read = ?,
                    records_inserted = ?,
                    records_updated = ?,
                    records_deactivated = ?
                WHERE id = ?
                """,
                registrosLidos,
                registrosInseridos,
                registrosAtualizados,
                registrosDesativados,
                syncRunId
        );
    }

    private void finalizarExecucaoComFalha(String syncRunId, String mensagemErro) {
        try {
            jdbcTemplate.update("""
                    UPDATE source_sync_run
                    SET
                        finished_at = CURRENT_TIMESTAMP(6),
                        status = 'FAILED',
                        error_message = ?
                    WHERE id = ?
                    """,
                    mensagemErro,
                    syncRunId
            );
        } catch (Exception ignored) {
        }
    }

    private void atualizarCheckpointComSucesso() {
        jdbcTemplate.update("""
                INSERT INTO source_sync_checkpoint (
                    id,
                    connector_name,
                    source_database,
                    source_table,
                    last_full_scan_at,
                    last_success_at,
                    last_error_at,
                    last_error_message
                )
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), NULL, NULL)
                ON DUPLICATE KEY UPDATE
                    last_full_scan_at = VALUES(last_full_scan_at),
                    last_success_at = VALUES(last_success_at),
                    last_error_at = NULL,
                    last_error_message = NULL
                """,
                stableCheckpointId(),
                CONNECTOR_NAME,
                BANCO_ORIGEM,
                TABELA_ORIGEM
        );
    }

    private void atualizarCheckpointComFalha(String mensagemErro) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO source_sync_checkpoint (
                        id,
                        connector_name,
                        source_database,
                        source_table,
                        last_error_at,
                        last_error_message
                    )
                    VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP(6), ?)
                    ON DUPLICATE KEY UPDATE
                        last_error_at = VALUES(last_error_at),
                        last_error_message = VALUES(last_error_message)
                    """,
                    stableCheckpointId(),
                    CONNECTOR_NAME,
                    BANCO_ORIGEM,
                    TABELA_ORIGEM,
                    mensagemErro
            );
        } catch (Exception ignored) {
        }
    }

    private void validarConfiguracaoAcademy() {
        if (isBlank(acadUrl) || isBlank(acadUsername) || isBlank(acadPassword)) {
            throw new IllegalStateException(
                    "Configuração da fonte Academy incompleta. Defina ACAD_DB_URL, ACAD_DB_USER e ACAD_DB_PASSWORD."
            );
        }
    }

    private String gerarHash(UsuarioAcademy usuario) throws Exception {
        String valor = String.join("|",
                nullToEmpty(usuario.pkOrigem()),
                nullToEmpty(usuario.codigoColaborador()),
                nullToEmpty(usuario.nome()),
                nullToEmpty(usuario.email()),
                nullToEmpty(usuario.idGrupoOrigem()),
                nullToEmpty(usuario.nomeGrupo()),
                nullToEmpty(usuario.idPerfilOrigem()),
                nullToEmpty(usuario.nomePerfil()),
                String.valueOf(usuario.ativo()),
                usuario.criadoEmOrigem() == null ? "" : usuario.criadoEmOrigem().toString()
        );

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(valor.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    private String stableColaboradorId(String pkOrigem) {
        return UUID.nameUUIDFromBytes(
                (BANCO_ORIGEM + ":" + TABELA_ORIGEM + ":" + pkOrigem)
                        .getBytes(StandardCharsets.UTF_8)
        ).toString();
    }

    private String stableCheckpointId() {
        return UUID.nameUUIDFromBytes(
                ("checkpoint:" + CONNECTOR_NAME + ":" + BANCO_ORIGEM + ":" + TABELA_ORIGEM)
                        .getBytes(StandardCharsets.UTF_8)
        ).toString();
    }

    private String getNullableString(ResultSet resultSet, String columnName) throws Exception {
        Object value = resultSet.getObject(columnName);
        return value == null ? null : String.valueOf(value);
    }

    private String mensagemRaiz(Throwable throwable) {
        Throwable current = throwable;

        while (current.getCause() != null) {
            current = current.getCause();
        }

        return current.getMessage() == null ? current.toString() : current.getMessage();
    }

    private String limitarTexto(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }

        return value.substring(0, maxLength);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record UsuarioAcademy(
            String id,
            String pkOrigem,
            String codigoColaborador,
            String nome,
            String email,
            String idGrupoOrigem,
            String nomeGrupo,
            String idPerfilOrigem,
            String nomePerfil,
            boolean ativo,
            LocalDateTime criadoEmOrigem
    ) {}
}
