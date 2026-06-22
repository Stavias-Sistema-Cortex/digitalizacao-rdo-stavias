package com.projeto.cortex.rdos;

import com.projeto.cortex.memory.CortexOperationalMemoryService;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class RdoMemoryPublisher {

    private static final String FONTE = "RDO_API";

    private final CortexOperationalMemoryService memoryService;
    private final JdbcTemplate jdbcTemplate;

    public RdoMemoryPublisher(
            CortexOperationalMemoryService memoryService,
            JdbcTemplate jdbcTemplate
    ) {
        this.memoryService = memoryService;
        this.jdbcTemplate = jdbcTemplate;
    }

    public void registrarRdoCriado(
            String rdoId,
            String obraId,
            String programacaoId,
            String numeroRdo,
            String status
    ) {
        registrarObjetoERelacoes(
                rdoId,
                obraId,
                programacaoId,
                numeroRdo,
                status
        );

        memoryService.registrarEvento(
                "RDO",
                rdoId,
                "RDO_CRIADO",
                FONTE,
                payloadBase(
                        obraId,
                        programacaoId,
                        numeroRdo,
                        status
                )
        );
    }

    public void registrarRdoEditado(
            String rdoId,
            String obraId,
            String programacaoId,
            String numeroRdo,
            String status
    ) {
        registrarObjetoERelacoes(
                rdoId,
                obraId,
                programacaoId,
                numeroRdo,
                status
        );

        memoryService.registrarEvento(
                "RDO",
                rdoId,
                "RDO_EDITADO",
                FONTE,
                payloadBase(
                        obraId,
                        programacaoId,
                        numeroRdo,
                        status
                )
        );
    }

    public void registrarRdoEnviado(
            String rdoId,
            String obraId,
            String programacaoId,
            String numeroRdo
    ) {
        registrarObjetoERelacoes(
                rdoId,
                obraId,
                programacaoId,
                numeroRdo,
                "ENVIADO"
        );

        memoryService.registrarEvento(
                "RDO",
                rdoId,
                "RDO_ENVIADO",
                FONTE,
                payloadBase(
                        obraId,
                        programacaoId,
                        numeroRdo,
                        "ENVIADO"
                )
        );
    }

    private void registrarObjetoERelacoes(
            String rdoId,
            String obraId,
            String programacaoId,
            String numeroRdo,
            String status
    ) {
        ObraMemoryData obra = buscarObra(obraId);

        memoryService.registrarObjeto(
                "OBRA",
                obra.id(),
                obra.codigoExterno(),
                obra.nome(),
                obra.status(),
                FONTE
        );

        ProgramacaoMemoryData programacao =
                buscarProgramacaoOpcional(
                        programacaoId,
                        obraId
                );

        if (programacao != null) {
            memoryService.registrarObjeto(
                    "PROGRAMACAO_OPERACIONAL",
                    programacao.id(),
                    programacao.codigoExterno(),
                    programacao.nome(),
                    programacao.status(),
                    FONTE
            );
        }

        String nomeRdo =
                numeroRdo == null || numeroRdo.isBlank()
                        ? "RDO " + rdoId
                        : "RDO " + numeroRdo;

        memoryService.registrarObjeto(
                "RDO",
                rdoId,
                numeroRdo,
                nomeRdo,
                status,
                FONTE
        );

        memoryService.substituirRelacaoAtiva(
                "RDO",
                rdoId,
                "OBRA",
                obra.id(),
                "PERTENCE_A",
                FONTE,
                "RDO pertence à obra."
        );

        if (programacao != null) {
            memoryService.substituirRelacaoAtiva(
                    "RDO",
                    rdoId,
                    "PROGRAMACAO_OPERACIONAL",
                    programacao.id(),
                    "GERADO_A_PARTIR_DE",
                    FONTE,
                    "RDO gerado a partir da programação operacional."
            );
        }
    }

    private ObraMemoryData buscarObra(String obraId) {
        if (obraId == null || obraId.isBlank()) {
            throw new IllegalArgumentException(
                    "A obra deve ser informada para publicar o RDO."
            );
        }

        try {
            return jdbcTemplate.queryForObject(
                    """
                    SELECT
                        id,
                        COALESCE(
                            NULLIF(codigo_cw, ''),
                            NULLIF(codigo_contrato, ''),
                            NULLIF(codigo_interno, ''),
                            id
                        ) AS codigo_externo,
                        nome,
                        status
                    FROM obra
                    WHERE id = ?
                      AND arquivado_em IS NULL
                    """,
                    (resultSet, rowNumber) ->
                            new ObraMemoryData(
                                    resultSet.getString("id"),
                                    resultSet.getString(
                                            "codigo_externo"
                                    ),
                                    resultSet.getString("nome"),
                                    resultSet.getString("status")
                            ),
                    obraId.trim()
            );
        } catch (DataAccessException exception) {
            throw new IllegalStateException(
                    "Não foi possível registrar a obra "
                            + obraId
                            + " na memória operacional.",
                    exception
            );
        }
    }

    private ProgramacaoMemoryData buscarProgramacaoOpcional(
            String programacaoId,
            String obraId
    ) {
        if (
                programacaoId == null
                || programacaoId.isBlank()
        ) {
            return null;
        }

        try {
            return jdbcTemplate.queryForObject(
                    """
                    SELECT
                        id,
                        COALESCE(
                            NULLIF(chave_negocio, ''),
                            CONCAT('PROG-', id)
                        ) AS codigo_externo,
                        CONCAT(
                            'Programação ',
                            DATE_FORMAT(
                                data_programacao,
                                '%d/%m/%Y'
                            ),
                            CASE
                                WHEN servico IS NULL
                                  OR TRIM(servico) = ''
                                    THEN ''
                                ELSE CONCAT(' - ', servico)
                            END
                        ) AS nome,
                        status
                    FROM programacao_operacional
                    WHERE id = ?
                      AND obra_id = ?
                      AND cancelado_em IS NULL
                    """,
                    (resultSet, rowNumber) ->
                            new ProgramacaoMemoryData(
                                    resultSet.getString("id"),
                                    resultSet.getString(
                                            "codigo_externo"
                                    ),
                                    resultSet.getString("nome"),
                                    resultSet.getString("status")
                            ),
                    programacaoId.trim(),
                    obraId.trim()
            );
        } catch (DataAccessException exception) {
            throw new IllegalStateException(
                    "Não foi possível registrar a programação "
                            + programacaoId
                            + " na memória operacional.",
                    exception
            );
        }
    }

    private Map<String, Object> payloadBase(
            String obraId,
            String programacaoId,
            String numeroRdo,
            String status
    ) {
        Map<String, Object> payload =
                new LinkedHashMap<>();

        payload.put("obraId", obraId);
        payload.put("programacaoId", programacaoId);
        payload.put("numeroRdo", numeroRdo);
        payload.put("status", status);

        return payload;
    }

    private record ObraMemoryData(
            String id,
            String codigoExterno,
            String nome,
            String status
    ) {
    }

    private record ProgramacaoMemoryData(
            String id,
            String codigoExterno,
            String nome,
            String status
    ) {
    }
}
