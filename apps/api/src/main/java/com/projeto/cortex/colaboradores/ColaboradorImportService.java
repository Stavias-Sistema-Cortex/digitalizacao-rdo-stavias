package com.projeto.cortex.colaboradores;

import com.projeto.cortex.auth.identity.AuthIdentityRepository;
import com.projeto.cortex.auth.identity.CpfNormalizer;
import com.projeto.cortex.integracoes.AcademySourceAdapter;
import com.projeto.cortex.integracoes.AcademyUserSnapshot;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ColaboradorImportService {

    private static final String CONNECTOR_NAME = "acad_colaborador_import";
    private static final String BANCO_ORIGEM = "dbstavias_acad";
    private static final String TABELA_ORIGEM = "usuarios";
    private static final int SNAPSHOT_PAGE_SIZE = 500;
    private static final long DEFAULT_MISSING_GRACE_HOURS = 24;
    private static final String SOURCE_FAILURE_MESSAGE =
            "Falha ao ler snapshot completo da Academy.";
    private static final String APPLY_FAILURE_MESSAGE =
            "Falha ao aplicar snapshot Academy.";

    private final JdbcTemplate jdbcTemplate;
    private final AcademySourceAdapter academySourceAdapter;
    private final CortexOperationalMemoryService memoryService;
    private final AuthIdentityRepository authIdentityRepository;
    private final TransactionTemplate applyTransactions;
    private final long missingGraceHours;

    @Autowired
    public ColaboradorImportService(
            JdbcTemplate jdbcTemplate,
            AcademySourceAdapter academySourceAdapter,
            CortexOperationalMemoryService memoryService,
            AuthIdentityRepository authIdentityRepository,
            PlatformTransactionManager transactionManager,
            @Value("${cortex.sync.academy.missing-grace-hours:24}")
            long missingGraceHours
    ) {
        this(
                jdbcTemplate,
                academySourceAdapter,
                memoryService,
                authIdentityRepository,
                new TransactionTemplate(transactionManager),
                missingGraceHours
        );
    }

    /**
     * Compatibility constructor for isolated integrations that instantiate
     * the service without a Spring context.
     */
    public ColaboradorImportService(
            JdbcTemplate jdbcTemplate,
            AcademySourceAdapter academySourceAdapter,
            CortexOperationalMemoryService memoryService,
            AuthIdentityRepository authIdentityRepository
    ) {
        this(
                jdbcTemplate,
                academySourceAdapter,
                memoryService,
                authIdentityRepository,
                transactionTemplateFor(jdbcTemplate),
                DEFAULT_MISSING_GRACE_HOURS
        );
    }

    ColaboradorImportService(
            JdbcTemplate jdbcTemplate,
            AcademySourceAdapter academySourceAdapter,
            CortexOperationalMemoryService memoryService,
            AuthIdentityRepository authIdentityRepository,
            TransactionTemplate applyTransactions,
            long missingGraceHours
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.academySourceAdapter = academySourceAdapter;
        this.memoryService = memoryService;
        this.authIdentityRepository = authIdentityRepository;
        this.applyTransactions = applyTransactions;
        if (missingGraceHours < 0) {
            throw new IllegalArgumentException(
                    "A carencia de ausentes Academy nao pode ser negativa."
            );
        }
        this.missingGraceHours = missingGraceHours;
    }

    public ColaboradorImportResult importarUsuariosDaAcademy() {
        String syncRunId = UUID.randomUUID().toString();
        LocalDateTime iniciadoEm = LocalDateTime.now();

        criarExecucaoSync(syncRunId, iniciadoEm);

        int registrosLidos = 0;
        String safeFailureMessage = SOURCE_FAILURE_MESSAGE;

        try {
            AcademyUserSnapshot snapshot =
                    academySourceAdapter.fetchCompleteSnapshot(
                            SNAPSHOT_PAGE_SIZE
                    );
            registrosLidos = snapshot == null
                    ? 0
                    : snapshot.users().size();
            PreparedSnapshot preparedSnapshot =
                    validarEPrepararSnapshot(snapshot);
            safeFailureMessage = APPLY_FAILURE_MESSAGE;

            ImportApplicationResult applied = Objects.requireNonNull(
                    applyTransactions.execute(status -> aplicarSnapshot(
                            syncRunId,
                            iniciadoEm,
                            preparedSnapshot
                    )),
                    "resultado da transacao de importacao"
            );

            return new ColaboradorImportResult(
                    syncRunId,
                    BANCO_ORIGEM,
                    TABELA_ORIGEM,
                    "SUCCESS",
                    registrosLidos,
                    registrosLidos,
                    applied.registrosInseridos(),
                    applied.registrosAtualizados(),
                    applied.registrosDesativados(),
                    null
            );
        } catch (SnapshotValidationException exception) {
            safeFailureMessage = exception.getMessage();
            registrarExecucaoComFalha(
                    syncRunId,
                    registrosLidos,
                    safeFailureMessage
            );
            throw new RuntimeException(
                    "Falha ao importar colaboradores da Academy."
            );
        } catch (Exception exception) {
            registrarExecucaoComFalha(
                    syncRunId,
                    registrosLidos,
                    safeFailureMessage
            );
            throw new RuntimeException(
                    "Falha ao importar colaboradores da Academy."
            );
        }
    }

    private ImportApplicationResult aplicarSnapshot(
            String syncRunId,
            LocalDateTime iniciadoEm,
            PreparedSnapshot snapshot
    ) {
        int registrosInseridos = 0;
        int registrosAtualizados = 0;

        for (UsuarioAcademy usuario : snapshot.users()) {
            String hashOrigem = gerarHash(usuario);
            String hashExistente = buscarHashExistente(
                    usuario.pkOrigem()
            );

            if (hashExistente == null) {
                registrosInseridos++;
            } else if (!hashExistente.equals(hashOrigem)) {
                registrosAtualizados++;
            }

            salvarOuAtualizar(usuario, hashOrigem);
            if (usuario.ativo() && usuario.cpfNormalizado() != null) {
                authIdentityRepository.upsertAcademyIdentity(
                        usuario.id(),
                        usuario.cpfNormalizado(),
                        usuario.email()
                );
            }

            registrarColaboradorNaMemoria(
                    syncRunId,
                    usuario,
                    hashOrigem,
                    hashExistente == null,
                    hashExistente != null
                            && !hashExistente.equals(hashOrigem)
            );
        }

        LocalDateTime missingCutoff = iniciadoEm.minusHours(
                missingGraceHours
        );
        int registrosDesativados = desativarAusentes(missingCutoff);

        finalizarExecucaoComSucesso(
                syncRunId,
                snapshot.users().size(),
                registrosInseridos,
                registrosAtualizados,
                registrosDesativados
        );
        atualizarCheckpointComSucesso();
        registrarImportacaoNaMemoria(
                syncRunId,
                "SUCCESS",
                snapshot.users().size(),
                registrosInseridos,
                registrosAtualizados,
                registrosDesativados,
                null
        );

        return new ImportApplicationResult(
                registrosInseridos,
                registrosAtualizados,
                registrosDesativados
        );
    }

    private PreparedSnapshot validarEPrepararSnapshot(
            AcademyUserSnapshot snapshot
    ) {
        if (snapshot == null || !snapshot.complete()) {
            throw snapshotConflict(1, "completude");
        }

        List<AcademySourceAdapter.UsuarioAcademyRecord> sourceUsers =
                snapshot.users();
        Map<Integer, Integer> sourceIdCounts = new HashMap<>();
        int invalidSourceIds = 0;
        for (AcademySourceAdapter.UsuarioAcademyRecord sourceUser
                : sourceUsers) {
            if (sourceUser.idUsuario() <= 0) {
                invalidSourceIds++;
            } else {
                sourceIdCounts.merge(
                        sourceUser.idUsuario(),
                        1,
                        Integer::sum
                );
            }
        }
        int sourceIdConflicts = invalidSourceIds
                + duplicatedRecordCount(sourceIdCounts);
        if (sourceIdConflicts > 0) {
            throw snapshotConflict(
                    sourceIdConflicts,
                    "id de origem"
            );
        }

        Map<String, Integer> activeCpfCounts = new HashMap<>();
        Map<String, Integer> eligibleEmailCounts = new HashMap<>();
        for (AcademySourceAdapter.UsuarioAcademyRecord sourceUser
                : sourceUsers) {
            if (!sourceUser.ativo()) {
                continue;
            }
            String canonicalCpf = cpfValidoOuNulo(sourceUser.cpf());
            if (canonicalCpf == null) {
                continue;
            }
            activeCpfCounts.merge(canonicalCpf, 1, Integer::sum);

            String normalizedEmail = normalizeEmail(sourceUser.email());
            if (normalizedEmail != null) {
                eligibleEmailCounts.merge(
                        normalizedEmail,
                        1,
                        Integer::sum
                );
            }
        }

        int cpfConflicts = duplicatedRecordCount(activeCpfCounts);
        if (cpfConflicts > 0) {
            throw snapshotConflict(cpfConflicts, "CPF ativo");
        }

        int emailConflicts = duplicatedRecordCount(
                eligibleEmailCounts
        );
        if (emailConflicts > 0) {
            throw snapshotConflict(
                    emailConflicts,
                    "email de autenticacao"
            );
        }

        return new PreparedSnapshot(
                sourceUsers.stream().map(this::lerUsuario).toList()
        );
    }

    private <T> int duplicatedRecordCount(Map<T, Integer> counts) {
        return counts.values().stream()
                .filter(count -> count > 1)
                .mapToInt(Integer::intValue)
                .sum();
    }

    private SnapshotValidationException snapshotConflict(
            int count,
            String type
    ) {
        return new SnapshotValidationException(
                "Snapshot Academy invalido: "
                        + count
                        + " conflitos de "
                        + type
                        + "."
        );
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private UsuarioAcademy lerUsuario(
            AcademySourceAdapter.UsuarioAcademyRecord sourceUser
    ) {
        int idUsuario = sourceUser.idUsuario();
        String pkOrigem = String.valueOf(idUsuario);

        String cpfNormalizado = cpfValidoOuNulo(sourceUser.cpf());
        LocalDateTime criadoEmOrigem = sourceUser.criadoEm();

        return new UsuarioAcademy(
                AcademyCollaboratorIdentity.fromAcademyUserId(idUsuario),
                pkOrigem,
                pkOrigem,
                cpfNormalizado,
                cpfNormalizado == null
                        ? null
                        : CpfHasher.hashDeDigitos(cpfNormalizado),
                cpfNormalizado == null
                        ? null
                        : CpfHasher.mascarar(cpfNormalizado),
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
                    cpf_mascarado = COALESCE(
                        EXCLUDED.cpf_mascarado,
                        colaborador.cpf_mascarado
                    ),
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
                    cpf_hash = COALESCE(
                        EXCLUDED.cpf_hash,
                        colaborador.cpf_hash
                    ),
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

    private String cpfValidoOuNulo(String cpfRaw) {
        try {
            return CpfNormalizer.requireValid(cpfRaw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
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

    private int desativarAusentes(LocalDateTime missingCutoff) {
        List<ColaboradorAusente> ausentes =
                buscarColaboradoresAusentes(missingCutoff);

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
                  AND ativo = TRUE
                  AND deletado_em IS NULL
                """,
                BANCO_ORIGEM,
                TABELA_ORIGEM,
                missingCutoff
        );

        for (ColaboradorAusente ausente : ausentes) {
            registrarColaboradorAusenteNaMemoria(ausente);
        }

        return total;
    }

    private List<ColaboradorAusente> buscarColaboradoresAusentes(
            LocalDateTime missingCutoff
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
                  AND ativo = TRUE
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
                missingCutoff
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

    private void finalizarExecucaoComFalha(
            String syncRunId,
            int registrosLidos,
            String mensagemErro
    ) {
        try {
            jdbcTemplate.update("""
                    UPDATE source_sync_run
                    SET
                        finished_at = CURRENT_TIMESTAMP(6),
                        status = 'FAILED',
                        records_read = ?,
                        records_inserted = 0,
                        records_updated = 0,
                        records_deactivated = 0,
                        error_message = ?
                    WHERE id = ?
                    """,
                    registrosLidos,
                    mensagemErro,
                    syncRunId
            );
        } catch (Exception ignored) {
        }
    }

    private void registrarExecucaoComFalha(
            String syncRunId,
            int registrosLidos,
            String mensagemErro
    ) {
        String safeMessage = limitarTexto(mensagemErro, 1000);
        finalizarExecucaoComFalha(
                syncRunId,
                registrosLidos,
                safeMessage
        );
        atualizarCheckpointComFalha(safeMessage);
        try {
            registrarImportacaoNaMemoria(
                    syncRunId,
                    "FAILED",
                    registrosLidos,
                    0,
                    0,
                    0,
                    safeMessage
            );
        } catch (Exception ignored) {
            // The durable FAILED run remains the authoritative failure signal.
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

    private String gerarHash(UsuarioAcademy usuario) {
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

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    valor.getBytes(StandardCharsets.UTF_8)
            );
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ignored) {
            throw new IllegalStateException(
                    "Falha ao preparar hash do snapshot Academy."
            );
        }
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

    private String limitarTexto(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }

        return value.substring(0, maxLength);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static TransactionTemplate transactionTemplateFor(
            JdbcTemplate jdbcTemplate
    ) {
        return new TransactionTemplate(
                new DataSourceTransactionManager(
                        Objects.requireNonNull(
                                jdbcTemplate.getDataSource(),
                                "JdbcTemplate sem DataSource transacional"
                        )
                )
        );
    }

    private static final class SnapshotValidationException
            extends IllegalStateException {

        private SnapshotValidationException(String message) {
            super(message);
        }
    }

    private record PreparedSnapshot(List<UsuarioAcademy> users) {

        private PreparedSnapshot {
            users = List.copyOf(users);
        }
    }

    private record ImportApplicationResult(
            int registrosInseridos,
            int registrosAtualizados,
            int registrosDesativados
    ) {}

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
