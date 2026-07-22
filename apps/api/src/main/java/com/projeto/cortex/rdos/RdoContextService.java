package com.projeto.cortex.rdos;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Service
public class RdoContextService {

    private final JdbcTemplate jdbcTemplate;

    public RdoContextService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RdoContextResponse buscarContexto(String obraId, LocalDate data) {
        if (obraId == null || obraId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "obraId é obrigatório.");
        }
        if (data == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "data é obrigatória.");
        }
        RdoContextResponse.ObraContexto obra = buscarObra(obraId);

        RdoContextResponse.PreviousRdo previousRdo = buscarRdoAnterior(obraId, data);

        List<RdoContextResponse.PreviousWorkforceItem> previousWorkforce =
                previousRdo == null
                        ? List.of()
                        : buscarEquipeAnterior(previousRdo.id(), obraId);

        List<RdoContextResponse.ProgramacaoContexto> programacoes =
                buscarProgramacoesDaObraNaData(obraId, data);

        List<RdoContextResponse.ColaboradorContexto> colaboradores =
                listarColaboradoresAtivosDaObra(obraId);

        List<RdoContextResponse.EquipamentoContexto> equipamentos =
                listarEquipamentosAtivosDaObra(obraId);

        long sourceVersion = calcularSourceVersion(obraId);

        return new RdoContextResponse(
                obra,
                data,
                sugerirProximoNumero(obraId),
                previousRdo,
                previousWorkforce,
                programacoes,
                colaboradores,
                equipamentos,
                new RdoContextResponse.CreationProvenance(
                        sourceVersion,
                        obraId,
                        data,
                        previousRdo == null ? null : previousRdo.id(),
                        Instant.now()
                )
        );
    }

    private RdoContextResponse.ObraContexto buscarObra(String obraId) {
        try {
            return jdbcTemplate.queryForObject(
                    """
                    SELECT
                        id,
                        codigo_contrato,
                        codigo_cw,
                        nome,
                        cliente,
                        cidade,
                        uf,
                        rodovia,
                        status,
                        versao_linha
                    FROM obra
                    WHERE id = ?
                      AND arquivado_em IS NULL
                    """,
                    (rs, rowNum) -> new RdoContextResponse.ObraContexto(
                            rs.getString("id"),
                            rs.getString("codigo_contrato"),
                            rs.getString("codigo_cw"),
                            rs.getString("nome"),
                            rs.getString("cliente"),
                            rs.getString("cidade"),
                            rs.getString("uf"),
                            rs.getString("rodovia"),
                            rs.getString("status"),
                            rs.getLong("versao_linha")
                    ),
                    obraId
            );
        } catch (DataAccessException exception) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Obra não encontrada: " + obraId
            );
        }
    }

    /**
     * The source is strictly before the selected date. Same-day RDOs are not a
     * workforce source because the new report may itself be an offline replay
     * for that date. Ties are resolved by persisted recency and finally by ID.
     */
    private RdoContextResponse.PreviousRdo buscarRdoAnterior(
            String obraId,
            LocalDate selectedDate
    ) {
        return jdbcTemplate.query(
                """
                SELECT id, numero_rdo, data_rdo, status, versao_linha
                FROM rdo
                WHERE obra_id = ?
                  AND data_rdo < ?
                  AND status <> 'CANCELADO'
                  AND cancelado_em IS NULL
                ORDER BY data_rdo DESC,
                         atualizado_em DESC,
                         criado_em DESC,
                         id DESC
                LIMIT 1
                """,
                rs -> rs.next()
                        ? new RdoContextResponse.PreviousRdo(
                                rs.getString("id"),
                                rs.getString("numero_rdo"),
                                rs.getDate("data_rdo").toLocalDate(),
                                rs.getString("status"),
                                rs.getLong("versao_linha")
                        )
                        : null,
                obraId,
                selectedDate
        );
    }

    private List<RdoContextResponse.PreviousWorkforceItem> buscarEquipeAnterior(
            String previousRdoId,
            String obraId
    ) {
        return jdbcTemplate.query(
                """
                SELECT
                    item.id,
                    item.rdo_id,
                    item.colaborador_id,
                    item.nome_colaborador,
                    item.cargo,
                    item.tipo_vinculo,
                    item.quantidade,
                    item.hora_inicio,
                    item.hora_fim,
                    item.observacoes,
                    CASE WHEN collaborator.id IS NOT NULL
                              AND link.status = 'ATIVO'
                         THEN 'AVAILABLE'
                         ELSE 'UNAVAILABLE'
                    END AS availability
                FROM rdo_mao_obra item
                JOIN rdo source_rdo
                  ON source_rdo.id = item.rdo_id
                 AND source_rdo.obra_id = ?
                LEFT JOIN colaborador collaborator
                  ON collaborator.id = item.colaborador_id
                 AND collaborator.ativo = TRUE
                 AND collaborator.deletado_em IS NULL
                LEFT JOIN vinculo_colaborador_obra link
                  ON link.colaborador_id = collaborator.id
                 AND link.obra_id = source_rdo.obra_id
                 AND link.status = 'ATIVO'
                WHERE item.rdo_id = ?
                ORDER BY item.cargo, item.nome_colaborador, item.id
                """,
                (rs, rowNum) -> new RdoContextResponse.PreviousWorkforceItem(
                        rs.getString("id"),
                        rs.getString("rdo_id"),
                        rs.getString("colaborador_id"),
                        rs.getString("nome_colaborador"),
                        rs.getString("cargo"),
                        rs.getString("tipo_vinculo"),
                        rs.getBigDecimal("quantidade"),
                        toLocalTime(rs.getTime("hora_inicio")),
                        toLocalTime(rs.getTime("hora_fim")),
                        rs.getString("observacoes"),
                        rs.getString("availability")
                ),
                obraId,
                previousRdoId
        );
    }

    private List<RdoContextResponse.ProgramacaoContexto> buscarProgramacoesDaObraNaData(
            String obraId,
            LocalDate data
    ) {
        return jdbcTemplate.query(
                """
                SELECT
                    id,
                    data_programacao,
                    equipe,
                    encarregado,
                    engenheiro,
                    cliente,
                    servico,
                    tipo_servico,
                    cidade,
                    uf,
                    rodovia,
                    sentido,
                    faixa,
                    km_inicial,
                    km_final,
                    extensao_m,
                    largura_m,
                    espessura_cm,
                    area_m2,
                    volume_m3,
                    status
                FROM programacao_operacional
                WHERE obra_id = ?
                  AND data_programacao = ?
                  AND cancelado_em IS NULL
                ORDER BY equipe, servico, km_inicial, id
                """,
                (rs, rowNum) -> new RdoContextResponse.ProgramacaoContexto(
                        rs.getString("id"),
                        rs.getDate("data_programacao").toLocalDate(),
                        rs.getString("equipe"),
                        rs.getString("encarregado"),
                        rs.getString("engenheiro"),
                        rs.getString("cliente"),
                        rs.getString("servico"),
                        rs.getString("tipo_servico"),
                        rs.getString("cidade"),
                        rs.getString("uf"),
                        rs.getString("rodovia"),
                        rs.getString("sentido"),
                        rs.getString("faixa"),
                        rs.getString("km_inicial"),
                        rs.getString("km_final"),
                        rs.getBigDecimal("extensao_m"),
                        rs.getBigDecimal("largura_m"),
                        rs.getBigDecimal("espessura_cm"),
                        rs.getBigDecimal("area_m2"),
                        rs.getBigDecimal("volume_m3"),
                        rs.getString("status")
                ),
                obraId,
                data
        );
    }

    private List<RdoContextResponse.ColaboradorContexto> listarColaboradoresAtivosDaObra(
            String obraId
    ) {
        return jdbcTemplate.query(
                """
                SELECT
                    collaborator.id,
                    collaborator.codigo_colaborador,
                    collaborator.nome,
                    link.papel_na_obra,
                    collaborator.nome_perfil
                FROM vinculo_colaborador_obra link
                JOIN colaborador collaborator
                  ON collaborator.id = link.colaborador_id
                 AND collaborator.ativo = TRUE
                 AND collaborator.deletado_em IS NULL
                WHERE link.obra_id = ?
                  AND link.status = 'ATIVO'
                ORDER BY collaborator.nome, collaborator.id
                LIMIT 300
                """,
                (rs, rowNum) -> new RdoContextResponse.ColaboradorContexto(
                        rs.getString("id"),
                        rs.getString("codigo_colaborador"),
                        rs.getString("nome"),
                        rs.getString("papel_na_obra"),
                        rs.getString("nome_perfil")
                ),
                obraId
        );
    }

    private String sugerirProximoNumero(String obraId) {
        Long next = jdbcTemplate.queryForObject(
                """
                SELECT GREATEST(
                    COALESCE((
                        SELECT next_value
                        FROM rdo_number_sequence
                        WHERE obra_id = ?
                    ), 1),
                    COALESCE((
                        SELECT MAX(substring(numero_rdo FROM '^RDO-([0-9]{1,18})$')::bigint) + 1
                        FROM rdo
                        WHERE obra_id = ?
                          AND numero_rdo ~ '^RDO-[0-9]{1,18}$'
                    ), 1)
                )
                """,
                Long.class,
                obraId,
                obraId
        );
        return formatNumber(next == null ? 1L : next);
    }

    private long calcularSourceVersion(String obraId) {
        Long version = jdbcTemplate.queryForObject(
                """
                SELECT GREATEST(
                    1,
                    COALESCE((
                        SELECT floor(extract(epoch FROM MAX(changed_at)) * 1000000)::bigint
                        FROM (
                            SELECT atualizado_em AS changed_at FROM obra WHERE id = ?
                            UNION ALL
                            SELECT atualizado_em FROM rdo WHERE obra_id = ?
                            UNION ALL
                            SELECT item.atualizado_em
                            FROM rdo_mao_obra item
                            JOIN rdo ON rdo.id = item.rdo_id
                            WHERE rdo.obra_id = ?
                            UNION ALL
                            SELECT atualizado_em
                            FROM programacao_operacional
                            WHERE obra_id = ?
                            UNION ALL
                            SELECT atualizado_em
                            FROM vinculo_colaborador_obra
                            WHERE obra_id = ?
                            UNION ALL
                            SELECT collaborator.atualizado_em
                            FROM colaborador collaborator
                            JOIN vinculo_colaborador_obra link
                              ON link.colaborador_id = collaborator.id
                            WHERE link.obra_id = ?
                            UNION ALL
                            SELECT item.atualizado_em
                            FROM rdo_equipamento item
                            JOIN rdo ON rdo.id = item.rdo_id
                            WHERE rdo.obra_id = ?
                            UNION ALL
                            SELECT asset.updated_at
                            FROM asset
                            JOIN rdo_equipamento item ON item.asset_id = asset.id
                            JOIN rdo ON rdo.id = item.rdo_id
                            WHERE rdo.obra_id = ?
                        ) revisions
                    ), 1)
                )
                """,
                Long.class,
                obraId,
                obraId,
                obraId,
                obraId,
                obraId,
                obraId,
                obraId,
                obraId
        );
        return version == null ? 1L : version;
    }

    static String formatNumber(long value) {
        return "RDO-%04d".formatted(value);
    }

    private LocalTime toLocalTime(java.sql.Time value) {
        return value == null ? null : value.toLocalTime();
    }

    private List<RdoContextResponse.EquipamentoContexto> listarEquipamentosAtivosDaObra(
            String obraId
    ) {
        return jdbcTemplate.query(
                """
                SELECT DISTINCT
                    asset.id,
                    asset.external_code,
                    asset.name,
                    asset.category
                FROM asset_obra_eligibilidade eligibility
                JOIN asset ON asset.id = eligibility.asset_id
                WHERE eligibility.obra_id = ?
                  AND eligibility.status = 'ATIVO'
                  AND asset.active = TRUE
                  AND asset.deleted_at IS NULL
                ORDER BY asset.external_code, asset.name, asset.id
                LIMIT 300
                """,
                (rs, rowNum) -> new RdoContextResponse.EquipamentoContexto(
                        rs.getString("id"),
                        rs.getString("external_code"),
                        rs.getString("name"),
                        rs.getString("category")
                ),
                obraId
        );
    }
}
