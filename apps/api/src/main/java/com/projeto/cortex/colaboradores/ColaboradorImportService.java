package com.projeto.cortex.colaboradores;

import com.projeto.cortex.auth.identity.AuthIdentityRepository;
import com.projeto.cortex.auth.identity.CpfNormalizer;
import com.projeto.cortex.integracoes.AcademySourceAdapter;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ColaboradorImportService {

    private static final String CONNECTOR_NAME = "acad_colaborador_import";
    private static final String BANCO_ORIGEM = "dbstavias_acad";
    private static final String TABELA_ORIGEM = "usuarios";
    private static final int MAX_IMPORT_ROWS = 10_000;

    private final JdbcTemplate jdbcTemplate;
    private final AcademySourceAdapter academySourceAdapter;
    private final CortexOperationalMemoryService memoryService;
    private final AuthIdentityRepository authIdentityRepository;

    public ColaboradorImportService(
            JdbcTemplate jdbcTemplate,
            AcademySourceAdapter academySourceAdapter,
            CortexOperationalMemoryService memoryService,
            AuthIdentityRepository authIdentityRepository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.academySourceAdapter = academySourceAdapter;
        this.memoryService = memoryService;
        this.authIdentityRepository = authIdentityRepository;
    }

    public ColaboradorImportResult importarUsuariosDaAcademy() {
        String syncRunId = UUID.randomUUID().toString();
        LocalDateTime iniciadoEm = LocalDateTime.now();

        criarExecucaoSync(syncRunId, iniciadoEm);

        int registrosLidos = 0;
        int registrosInseridos = 0;
        int registrosAtualizados = 0;

        try {
            for (AcademySourceAdapter.UsuarioAcademyRecord sourceUser
                    : academySourceAdapter.fetchUsers(MAX_IMPORT_ROWS)) {
                UsuarioAcademy usuario = lerUsuario(sourceUser);
                registrosLidos++;

                String hashOrigem = gerarHash(usuario);
                String hashExistente =
                        buscarHashExistente(usuario.pkOrigem());

                if (hashExistente == null) {
                    registrosInseridos++;
                } else if (!hashExistente.equals(hashOrigem)) {
                    registrosAtualizados++;
                }

                salvarOuAtualizar(usuario, hashOrigem);
                authIdentityRepository.upsertAcademyIdentity(
                        usuario.id(),
                        usuario.cpfNormalizado(),
                        usuario.email()
                );

                registrarColaboradorNaMemoria(
                        syncRunId,
                        usuario,
                        hashOrigem,
                        hashExistente == null,
                        hashExistente != null
                                && !hashExistente.equals(hashOrigem)
                );
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
            registrarImportacaoNaMemoria(
                    syncRunId,
                    "SUCCESS",
                    registrosLidos,
                    registrosInseridos,
                    registrosAtualizados,
                    registrosDesativados,
                    null
            );

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
            registrarImportacaoNaMemoria(
                    syncRunId,
                    "FAILED",
                    registrosLidos,
                    registrosInseridos,
                    registrosAtualizados,
                    0,
                    mensagemErro
            );

            throw new RuntimeException("Falha ao importar colaboradores da Academy.", exception);
        }
    }

    private UsuarioAcademy lerUsuario(
            AcademySourceAdapter.UsuarioAcademyRecord sourceUser
    ) throws Exception {
        int idUsuario = sourceUser.idUsuario();
        String pkOrigem = String.valueOf(idUsuario);

        String cpfNormalizado = CpfNormalizer.requireValid(sourceUser.cpf());
        LocalDateTime criadoEmOrigem = sourceUser.criadoEm();

        return new UsuarioAcademy(
                AcademyCollaboratorIdentity.fromAcademyUserId(idUsuario),
                pkOrigem,
                pkOrigem,
                cpfNormalizado,
                CpfHasher.hashDeDigitos(cpfNormalizado),
                CpfHasher.mascarar(cpfNormalizado),
                sourceUser.nome(),
                sourceUser.email(),
                sourceUser.idGrupo(),
                sourceUser.nomeGrupo(),
                sourceUser.idPerfil(),
                sourceUser.nomePerfil(),
                sourceUser.ativo(),
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
                    cpf_hash,
                    cpf_mascarado,
                    nome,
                    email,
                    id_grupo_origem,
                    nome_grupo,
                    id_perfil_origem,
                    nome_perfil,
                    papel_acesso,
                    ativo,
                    criado_em_origem,
                    atualizado_em_origem,
                    hash_origem,
                    visto_por_ultimo_em,
                    deletado_em
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'BETA', ?, ?, NULL, ?, CURRENT_TIMESTAMP(6), NULL)
                ON CONFLICT (banco_origem, tabela_origem, pk_origem) DO UPDATE SET
                    codigo_colaborador = EXCLUDED.codigo_colaborador,
                    cpf_mascarado = EXCLUDED.cpf_mascarado,
                    nome = EXCLUDED.nome,
                    email = EXCLUDED.email,
                    id_grupo_origem = EXCLUDED.id_grupo_origem,
                    nome_grupo = EXCLUDED.nome_grupo,
                    id_perfil_origem = EXCLUDED.id_perfil_origem,
                    nome_perfil = EXCLUDED.nome_perfil,
                    papel_acesso = COALESCE(colaborador.papel_acesso, 'BETA'),
                    ativo = EXCLUDED.ativo,
                    criado_em_origem = EXCLUDED.criado_em_origem,
                    atualizado_em_origem = EXCLUDED.atualizado_em_origem,
                    atualizado_em = CASE
                        WHEN colaborador.hash_origem IS DISTINCT FROM EXCLUDED.hash_origem
                            OR colaborador.cpf_hash IS DISTINCT FROM EXCLUDED.cpf_hash
                        THEN CURRENT_TIMESTAMP(6)
                        ELSE colaborador.atualizado_em
                    END,
                    versao_linha = CASE
                        WHEN colaborador.hash_origem IS DISTINCT FROM EXCLUDED.hash_origem
                            OR colaborador.cpf_hash IS DISTINCT FROM EXCLUDED.cpf_hash
                        THEN colaborador.versao_linha + 1
                        ELSE colaborador.versao_linha
                    END,
                    cpf_hash = EXCLUDED.cpf_hash,
                    hash_origem = EXCLUDED.hash_origem,
                    visto_por_ultimo_em = CURRENT_TIMESTAMP(6),
                    deletado_em = NULL
                """,
                usuario.id(),
                BANCO_ORIGEM,
                TABELA_ORIGEM,
                usuario.pkOrigem(),
                usuario.codigoColaborador(),
                usuario.cpfHash(),
                usuario.cpfMascarado(),
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
        List<ColaboradorAusente> ausentes = buscarColaboradoresAusentes(iniciadoEm);

        int total = jdbcTemplate.update("""
                UPDATE colaborador
                SET
                    ativo = FALSE,
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

        for (ColaboradorAusente ausente : ausentes) {
            registrarColaboradorAusenteNaMemoria(ausente);
        }

        return total;
    }

    private List<ColaboradorAusente> buscarColaboradoresAusentes(
            LocalDateTime iniciadoEm
    ) {
        return jdbcTemplate.query(
                """
                SELECT
                    id,
                    pk_origem,
                    codigo_colaborador,
                    nome,
                    hash_origem
                FROM colaborador
                WHERE banco_origem = ?
                  AND tabela_origem = ?
                  AND visto_por_ultimo_em < ?
                  AND deletado_em IS NULL
                """,
                (resultSet, rowNumber) ->
                        new ColaboradorAusente(
                                resultSet.getString("id"),
                                resultSet.getString("pk_origem"),
                                resultSet.getString(
                                        "codigo_colaborador"
                                ),
                                resultSet.getString("nome"),
                                resultSet.getString("hash_origem")
                        ),
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
                ON CONFLICT (connector_name, source_database, source_table) DO UPDATE SET
                    last_full_scan_at = EXCLUDED.last_full_scan_at,
                    last_success_at = EXCLUDED.last_success_at,
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
                    ON CONFLICT (connector_name, source_database, source_table) DO UPDATE SET
                        last_error_at = EXCLUDED.last_error_at,
                        last_error_message = EXCLUDED.last_error_message
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

    private String gerarHash(UsuarioAcademy usuario) throws Exception {
        String valor = String.join("|",
                nullToEmpty(usuario.pkOrigem()),
                nullToEmpty(usuario.codigoColaborador()),
                nullToEmpty(usuario.cpfMascarado()),
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

    private void registrarColaboradorNaMemoria(
            String syncRunId,
            UsuarioAcademy usuario,
            String hashOrigem,
            boolean inserted,
            boolean updated
    ) {
        Map<String, Object> metadata = metadataLegado(
                syncRunId,
                usuario.pkOrigem(),
                hashOrigem
        );

        memoryService.registrarObjeto(
                "COLABORADOR",
                usuario.id(),
                usuario.codigoColaborador(),
                usuario.nome(),
                usuario.ativo() ? "ATIVO" : "INATIVO",
                "IMPORTACAO_LEGADO",
                "colaborador",
                metadata
        );

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("codigo_colaborador", usuario.codigoColaborador());
        fields.put("nome", usuario.nome());
        fields.put("email", usuario.email());
        fields.put("cpf_mascarado", usuario.cpfMascarado());
        fields.put("grupo", usuario.nomeGrupo());
        fields.put("perfil", usuario.nomePerfil());
        fields.put("ativo", usuario.ativo());
        fields.put("source_database", BANCO_ORIGEM);
        fields.put("source_table", TABELA_ORIGEM);
        fields.put("source_pk", usuario.pkOrigem());
        fields.put("source_hash", hashOrigem);

        memoryService.registrarEvidencias(
                "COLABORADOR",
                usuario.id(),
                "IMPORTACAO_LEGADO",
                fields
        );

        memoryService.registrarMapeamentoLegado(
                "COLABORADOR",
                usuario.id(),
                BANCO_ORIGEM,
                TABELA_ORIGEM,
                usuario.pkOrigem(),
                hashOrigem,
                syncRunId,
                fields
        );

        if (!inserted && !updated) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>(metadata);
        payload.put("schemaVersion", 1);
        payload.put("colaboradorId", usuario.id());
        payload.put("codigoColaborador", usuario.codigoColaborador());
        payload.put("nome", usuario.nome());
        payload.put("ativo", usuario.ativo());

        memoryService.registrarEvento(
                "COLABORADOR",
                usuario.id(),
                inserted
                        ? "COLABORADOR_IMPORTADO_DO_LEGADO"
                        : "COLABORADOR_ATUALIZADO_DO_LEGADO",
                "IMPORTACAO_LEGADO",
                payload
        );
    }

    private void registrarColaboradorAusenteNaMemoria(
            ColaboradorAusente ausente
    ) {
        Map<String, Object> metadata = metadataLegado(
                null,
                ausente.pkOrigem(),
                ausente.hashOrigem()
        );

        memoryService.registrarObjeto(
                "COLABORADOR",
                ausente.id(),
                ausente.codigoColaborador(),
                ausente.nome(),
                "INATIVO",
                "IMPORTACAO_LEGADO",
                "colaborador",
                metadata
        );

        memoryService.registrarEvidencia(
                "COLABORADOR",
                ausente.id(),
                "ativo",
                false,
                "BOOLEANO",
                "IMPORTACAO_LEGADO",
                java.math.BigDecimal.ONE,
                metadata
        );

        memoryService.registrarEvento(
                "COLABORADOR",
                ausente.id(),
                "COLABORADOR_AUSENTE_NO_LEGADO",
                "IMPORTACAO_LEGADO",
                metadata
        );
    }

    private void registrarImportacaoNaMemoria(
            String syncRunId,
            String status,
            int registrosLidos,
            int registrosInseridos,
            int registrosAtualizados,
            int registrosDesativados,
            String mensagemErro
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("connectorName", CONNECTOR_NAME);
        payload.put("sourceDatabase", BANCO_ORIGEM);
        payload.put("sourceTable", TABELA_ORIGEM);
        payload.put("recordsRead", registrosLidos);
        payload.put("recordsInserted", registrosInseridos);
        payload.put("recordsUpdated", registrosAtualizados);
        payload.put("recordsDeactivated", registrosDesativados);
        payload.put("errorMessage", mensagemErro);

        memoryService.registrarObjeto(
                "IMPORTACAO_LEGADA",
                syncRunId,
                CONNECTOR_NAME + ":" + TABELA_ORIGEM,
                "Importação " + CONNECTOR_NAME,
                status,
                "IMPORTACAO_LEGADO",
                "source_sync_run",
                payload
        );

        memoryService.registrarEvento(
                "IMPORTACAO_LEGADA",
                syncRunId,
                "SUCCESS".equals(status)
                        ? "IMPORTACAO_LEGADA_CONCLUIDA"
                        : "IMPORTACAO_LEGADA_FALHOU",
                "IMPORTACAO_LEGADO",
                payload
        );
    }

    private Map<String, Object> metadataLegado(
            String syncRunId,
            String pkOrigem,
            String hashOrigem
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("legacySystem", BANCO_ORIGEM);
        metadata.put("legacyTable", TABELA_ORIGEM);
        metadata.put("legacyId", pkOrigem);
        metadata.put("importBatchId", syncRunId);
        metadata.put("sourceHash", hashOrigem);
        return metadata;
    }

    private String stableCheckpointId() {
        return UUID.nameUUIDFromBytes(
                ("checkpoint:" + CONNECTOR_NAME + ":" + BANCO_ORIGEM + ":" + TABELA_ORIGEM)
                        .getBytes(StandardCharsets.UTF_8)
        ).toString();
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

    private record UsuarioAcademy(
            String id,
            String pkOrigem,
            String codigoColaborador,
            String cpfNormalizado,
            String cpfHash,
            String cpfMascarado,
            String nome,
            String email,
            String idGrupoOrigem,
            String nomeGrupo,
            String idPerfilOrigem,
            String nomePerfil,
            boolean ativo,
            LocalDateTime criadoEmOrigem
    ) {}

    private record ColaboradorAusente(
            String id,
            String pkOrigem,
            String codigoColaborador,
            String nome,
            String hashOrigem
    ) {}
}
