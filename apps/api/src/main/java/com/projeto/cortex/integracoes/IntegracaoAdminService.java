package com.projeto.cortex.integracoes;

import com.projeto.cortex.assets.AssetImportResult;
import com.projeto.cortex.assets.AssetImportService;
import com.projeto.cortex.colaboradores.ColaboradorImportResult;
import com.projeto.cortex.colaboradores.ColaboradorImportService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
public class IntegracaoAdminService {

    private final JdbcTemplate jdbcTemplate;
    private final AcademySourceAdapter academySourceAdapter;
    private final ZeladoriaSourceAdapter zeladoriaSourceAdapter;
    private final ColaboradorImportService colaboradorImportService;
    private final AssetImportService assetImportService;

    public IntegracaoAdminService(
            JdbcTemplate jdbcTemplate,
            AcademySourceAdapter academySourceAdapter,
            ZeladoriaSourceAdapter zeladoriaSourceAdapter,
            ColaboradorImportService colaboradorImportService,
            AssetImportService assetImportService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.academySourceAdapter = academySourceAdapter;
        this.zeladoriaSourceAdapter = zeladoriaSourceAdapter;
        this.colaboradorImportService = colaboradorImportService;
        this.assetImportService = assetImportService;
    }

    public List<IntegracaoStatusResponse> listStatus() {
        return List.of(
                status(
                        "academy",
                        "Academy",
                        "acad_colaborador_import",
                        "dbstavias_acad",
                        "usuarios"
                ),
                status(
                        "zeladoria",
                        "Zeladoria",
                        "zld_asset_import",
                        "dbstavias_zld",
                        "ativos"
                )
        );
    }

    public IntegracaoActionResponse testConnection(String integrationId) {
        boolean ok =
                switch (integrationId) {
                    case "academy" -> academySourceAdapter.testConnection();
                    case "zeladoria" -> zeladoriaSourceAdapter.testConnection();
                    default -> throw new IllegalArgumentException(
                            "Integracao desconhecida: " + integrationId
                    );
                };

        return new IntegracaoActionResponse(
                integrationId,
                ok ? "SUCCESS" : "FAILED",
                ok
                        ? "Conexao somente leitura validada."
                        : "Nao foi possivel validar a conexao somente leitura."
        );
    }

    public IntegracaoActionResponse startSync(String integrationId) {
        return switch (integrationId) {
            case "academy" -> {
                try {
                    ColaboradorImportResult result =
                            colaboradorImportService.importarUsuariosDaAcademy();
                    yield new IntegracaoActionResponse(
                            integrationId,
                            result.status(),
                            actionMessage(
                                    "Sincronizacao Academy",
                                    result.status(),
                                    result.registrosLidos(),
                                    result.mensagemErro()
                            )
                    );
                } catch (Exception exception) {
                    yield failedAction(
                            integrationId,
                            "Sincronizacao Academy",
                            exception
                    );
                }
            }
            case "zeladoria" -> {
                try {
                    AssetImportResult result =
                            assetImportService.importFromZldAtivos();
                    yield new IntegracaoActionResponse(
                            integrationId,
                            result.status(),
                            actionMessage(
                                    "Sincronizacao Zeladoria",
                                    result.status(),
                                    result.recordsRead(),
                                    result.errorMessage()
                            )
                    );
                } catch (Exception exception) {
                    yield failedAction(
                            integrationId,
                            "Sincronizacao Zeladoria",
                            exception
                    );
                }
            }
            default -> throw new IllegalArgumentException(
                    "Integracao desconhecida: " + integrationId
            );
        };
    }

    private IntegracaoActionResponse failedAction(
            String integrationId,
            String label,
            Exception exception
    ) {
        return new IntegracaoActionResponse(
                integrationId,
                "FAILED",
                label + " falhou. " + rootCauseMessage(exception)
        );
    }

    private String actionMessage(
            String label,
            String status,
            int recordsRead,
            String errorMessage
    ) {
        if (Objects.equals(status, "SUCCESS")) {
            return label + " concluida. Registros lidos: " + recordsRead;
        }

        return label + " falhou. "
                + (errorMessage == null || errorMessage.isBlank()
                        ? "Consulte o relatório da integração para detalhes."
                        : errorMessage);
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable current = throwable;

        while (current.getCause() != null) {
            current = current.getCause();
        }

        String message = current.getMessage();

        if (message == null || message.isBlank()) {
            return "Erro interno ao executar a integração.";
        }

        return message;
    }

    private IntegracaoStatusResponse status(
            String id,
            String name,
            String connector,
            String sourceDatabase,
            String sourceTable
    ) {
        SyncRun latestRun =
                latestRun(connector);

        SyncCheckpoint checkpoint =
                checkpoint(connector, sourceDatabase, sourceTable);

        LocalDateTime lastSuccess =
                checkpoint == null
                        ? null
                        : checkpoint.lastSuccessAt();

        String state =
                latestRun == null
                        ? "SEM_SINCRONIZACAO"
                        : latestRun.status();

        String error =
                latestRun != null && latestRun.errorMessage() != null
                        ? latestRun.errorMessage()
                        : checkpoint == null
                                ? null
                                : checkpoint.lastErrorMessage();

        return new IntegracaoStatusResponse(
                id,
                name,
                state,
                lastSuccess == null ? null : lastSuccess.toString(),
                latestRun == null ? null : latestRun.durationSeconds(),
                latestRun == null ? 0 : latestRun.recordsRead(),
                latestRun == null ? 0 : latestRun.recordsInserted(),
                latestRun == null ? 0 : latestRun.recordsUpdated(),
                latestRun == null ? 0 : latestRun.recordsDeactivated(),
                error,
                "Manual ou agendada pelo CORTEX_SYNC_FIXED_DELAY_MS",
                defasagem(lastSuccess)
        );
    }

    private SyncRun latestRun(String connector) {
        List<SyncRun> rows =
                jdbcTemplate.query(
                        """
                        SELECT
                            id,
                            status,
                            records_read,
                            records_inserted,
                            records_updated,
                            records_deactivated,
                            error_message,
                            started_at,
                            finished_at
                        FROM source_sync_run
                        WHERE connector_name = ?
                        ORDER BY started_at DESC
                        LIMIT 1
                        """,
                        (rs, rowNumber) -> {
                            LocalDateTime startedAt =
                                    toLocalDateTime(rs.getTimestamp("started_at"));
                            LocalDateTime finishedAt =
                                    toLocalDateTime(rs.getTimestamp("finished_at"));

                            return new SyncRun(
                                    rs.getString("id"),
                                    rs.getString("status"),
                                    rs.getInt("records_read"),
                                    rs.getInt("records_inserted"),
                                    rs.getInt("records_updated"),
                                    rs.getInt("records_deactivated"),
                                    rs.getString("error_message"),
                                    startedAt,
                                    finishedAt,
                                    durationSeconds(startedAt, finishedAt)
                            );
                        },
                        connector
                );

        return rows.isEmpty() ? null : rows.getFirst();
    }

    private SyncCheckpoint checkpoint(
            String connector,
            String sourceDatabase,
            String sourceTable
    ) {
        List<SyncCheckpoint> rows =
                jdbcTemplate.query(
                        """
                        SELECT
                            last_success_at,
                            last_error_message
                        FROM source_sync_checkpoint
                        WHERE connector_name = ?
                          AND source_database = ?
                          AND source_table = ?
                        ORDER BY COALESCE(last_success_at, last_error_at) DESC
                        LIMIT 1
                        """,
                        (rs, rowNumber) ->
                                new SyncCheckpoint(
                                        toLocalDateTime(
                                                rs.getTimestamp(
                                                        "last_success_at"
                                                )
                                        ),
                                        rs.getString("last_error_message")
                                ),
                        connector,
                        sourceDatabase,
                        sourceTable
                );

        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Long durationSeconds(
            LocalDateTime startedAt,
            LocalDateTime finishedAt
    ) {
        if (startedAt == null || finishedAt == null) {
            return null;
        }

        return Duration.between(startedAt, finishedAt).toSeconds();
    }

    private String defasagem(LocalDateTime lastSuccessAt) {
        if (lastSuccessAt == null) {
            return "Sem sincronizacao concluida";
        }

        Duration duration =
                Duration.between(lastSuccessAt, LocalDateTime.now());

        long hours = duration.toHours();

        if (hours < 1) {
            long minutes = Math.max(0, duration.toMinutes());
            return "Atualizado ha " + minutes + " minuto(s)";
        }

        return "Atualizado ha " + hours + " hora(s)";
    }

    private LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null
                ? null
                : timestamp.toLocalDateTime();
    }

    private record SyncRun(
            String id,
            String status,
            int recordsRead,
            int recordsInserted,
            int recordsUpdated,
            int recordsDeactivated,
            String errorMessage,
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            Long durationSeconds
    ) {
    }

    private record SyncCheckpoint(
            LocalDateTime lastSuccessAt,
            String lastErrorMessage
    ) {
    }
}
