package com.projeto.cortex.rdos;

import com.projeto.cortex.memory.CortexOperationalMemoryService;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
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

        memoryService.registrarEventoDetalhado(
                null,
                "RDO",
                rdoId,
                "RDO_CRIADO",
                FONTE,
                obraId,
                rdoId,
                null,
                relatedEntities(obraId, programacaoId),
                "ONLINE",
                "SYNCED",
                null,
                LocalDateTime.now(),
                1,
                payloadBase(
                        rdoId,
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
            String status,
            long versaoAnterior,
            long versaoNova,
            List<Map<String, Object>> alteracoes
    ) {
        registrarObjetoERelacoes(
                rdoId,
                obraId,
                programacaoId,
                numeroRdo,
                status
        );

        memoryService.registrarEventoDetalhado(
                null,
                "RDO",
                rdoId,
                "RDO_EDITADO",
                FONTE,
                obraId,
                rdoId,
                null,
                relatedEntities(obraId, programacaoId),
                "ONLINE",
                "SYNCED",
                null,
                LocalDateTime.now(),
                1,
                payloadEdicao(
                        rdoId,
                        obraId,
                        programacaoId,
                        numeroRdo,
                        status,
                        versaoAnterior,
                        versaoNova,
                        alteracoes
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

        memoryService.registrarEventoDetalhado(
                null,
                "RDO",
                rdoId,
                "RDO_ENVIADO",
                FONTE,
                obraId,
                rdoId,
                null,
                relatedEntities(obraId, programacaoId),
                "ONLINE",
                "SYNCED",
                null,
                LocalDateTime.now(),
                1,
                payloadBase(
                        rdoId,
                        obraId,
                        programacaoId,
                        numeroRdo,
                        "ENVIADO"
                )
        );
    }

    public void registrarRdoImportado(
            String rdoId,
            String obraId,
            String programacaoId,
            String numeroRdo,
            String importacaoId,
            String arquivo,
            String aba,
            Integer linha,
            String status
    ) {
        registrarObjetoERelacoes(
                rdoId,
                obraId,
                programacaoId,
                numeroRdo,
                status
        );

        Map<String, Object> payload = payloadBase(
                rdoId,
                obraId,
                programacaoId,
                numeroRdo,
                status
        );
        payload.put("schemaVersion", 1);
        payload.put("importacaoId", importacaoId);
        payload.put("arquivo", arquivo);
        payload.put("aba", aba);
        payload.put("linha", linha);
        payload.put("fonteCriacao", "IMPORTACAO_HISTORICA");

        memoryService.registrarEventoDetalhado(
                null,
                "RDO",
                rdoId,
                "RDO_IMPORTADO",
                "IMPORTACAO_HISTORICA",
                obraId,
                rdoId,
                null,
                relatedEntities(obraId, programacaoId),
                "ONLINE",
                "SYNCED",
                null,
                LocalDateTime.now(),
                1,
                payload
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

        registrarAtributosRdo(rdoId);
        registrarMaoObraRdo(rdoId);
        registrarEquipamentosRdo(rdoId);
        registrarMateriaisRdo(rdoId);
        registrarControlesGeometricosRdo(rdoId);
        registrarFotosRdo(rdoId);

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

    private void registrarAtributosRdo(String rdoId) {
        jdbcTemplate.query(
                """
                SELECT
                    numero_rdo,
                    data_rdo,
                    dia_semana,
                    cliente,
                    contrato,
                    rodovia,
                    cidade,
                    uf,
                    turno,
                    hora_inicio,
                    hora_fim,
                    status,
                    obra_id,
                    programacao_id,
                    fonte_criacao
                FROM rdo
                WHERE id = ?
                """,
                resultSet -> {
                    if (!resultSet.next()) {
                        return null;
                    }

                    Map<String, Object> fields = new LinkedHashMap<>();
                    fields.put("numero_rdo", resultSet.getString("numero_rdo"));
                    fields.put("data_rdo", resultSet.getDate("data_rdo"));
                    fields.put("dia_semana", resultSet.getString("dia_semana"));
                    fields.put("cliente", resultSet.getString("cliente"));
                    fields.put("contrato", resultSet.getString("contrato"));
                    fields.put("rodovia", resultSet.getString("rodovia"));
                    fields.put("cidade", resultSet.getString("cidade"));
                    fields.put("uf", resultSet.getString("uf"));
                    fields.put("turno", resultSet.getString("turno"));
                    fields.put("hora_inicio", resultSet.getTime("hora_inicio"));
                    fields.put("hora_fim", resultSet.getTime("hora_fim"));
                    fields.put("status", resultSet.getString("status"));
                    fields.put("obra_id", resultSet.getString("obra_id"));
                    fields.put("programacao_id", resultSet.getString("programacao_id"));
                    fields.put("fonte_criacao", resultSet.getString("fonte_criacao"));

                    memoryService.registrarEvidencias(
                            "RDO",
                            rdoId,
                            FONTE,
                            fields
                    );

                    return null;
                },
                rdoId
        );
    }

    private void registrarMaoObraRdo(String rdoId) {
        jdbcTemplate.query(
                """
                SELECT
                    id,
                    colaborador_id,
                    nome_colaborador,
                    cargo,
                    tipo_vinculo,
                    quantidade
                FROM rdo_mao_obra
                WHERE rdo_id = ?
                """,
                resultSet -> {
                    while (resultSet.next()) {
                        String itemId = resultSet.getString("id");
                        String colaboradorId = resultSet.getString("colaborador_id");
                        String nome = primeiroNaoVazio(
                                resultSet.getString("nome_colaborador"),
                                resultSet.getString("cargo"),
                                itemId
                        );

                        memoryService.registrarObjeto(
                                "RDO_MAO_OBRA",
                                itemId,
                                itemId,
                                "Mão de obra " + nome,
                                "REGISTRADA",
                                FONTE,
                                "rdo_mao_obra",
                                metadataRdo(rdoId)
                        );

                        memoryService.registrarRelacaoAtiva(
                                "RDO",
                                rdoId,
                                "RDO_MAO_OBRA",
                                itemId,
                                "REGISTRA_MAO_DE_OBRA",
                                FONTE,
                                "RDO registra item de mão de obra."
                        );

                        if (colaboradorId != null && !colaboradorId.isBlank()) {
                            memoryService.registrarRelacaoAtiva(
                                    "RDO_MAO_OBRA",
                                    itemId,
                                    "COLABORADOR",
                                    colaboradorId,
                                    "REFERENCIA_COLABORADOR",
                                    FONTE,
                                    "Item de mão de obra referencia colaborador cadastrado."
                            );
                        }

                        Map<String, Object> fields = new LinkedHashMap<>();
                        fields.put("colaborador_id", colaboradorId);
                        fields.put("nome_colaborador", resultSet.getString("nome_colaborador"));
                        fields.put("cargo", resultSet.getString("cargo"));
                        fields.put("tipo_vinculo", resultSet.getString("tipo_vinculo"));
                        fields.put("quantidade", resultSet.getBigDecimal("quantidade"));

                        memoryService.registrarEvidencias(
                                "RDO_MAO_OBRA",
                                itemId,
                                FONTE,
                                fields
                        );
                    }

                    return null;
                },
                rdoId
        );
    }

    private void registrarEquipamentosRdo(String rdoId) {
        jdbcTemplate.query(
                """
                SELECT
                    id,
                    asset_id,
                    prefixo,
                    descricao,
                    tipo_equipamento,
                    tipo_vinculo,
                    quantidade
                FROM rdo_equipamento
                WHERE rdo_id = ?
                """,
                resultSet -> {
                    while (resultSet.next()) {
                        String itemId = resultSet.getString("id");
                        String assetId = resultSet.getString("asset_id");
                        String nome = primeiroNaoVazio(
                                resultSet.getString("prefixo"),
                                resultSet.getString("descricao"),
                                itemId
                        );

                        memoryService.registrarObjeto(
                                "RDO_EQUIPAMENTO",
                                itemId,
                                resultSet.getString("prefixo"),
                                "Equipamento " + nome,
                                "REGISTRADO",
                                FONTE,
                                "rdo_equipamento",
                                metadataRdo(rdoId)
                        );

                        memoryService.registrarRelacaoAtiva(
                                "RDO",
                                rdoId,
                                "RDO_EQUIPAMENTO",
                                itemId,
                                "USA_EQUIPAMENTO",
                                FONTE,
                                "RDO usa equipamento."
                        );

                        if (assetId != null && !assetId.isBlank()) {
                            memoryService.registrarRelacaoAtiva(
                                    "RDO_EQUIPAMENTO",
                                    itemId,
                                    "ATIVO",
                                    assetId,
                                    "REFERENCIA_ATIVO",
                                    FONTE,
                                    "Item de equipamento referencia ativo cadastrado."
                            );
                        }

                        Map<String, Object> fields = new LinkedHashMap<>();
                        fields.put("asset_id", assetId);
                        fields.put("prefixo", resultSet.getString("prefixo"));
                        fields.put("descricao", resultSet.getString("descricao"));
                        fields.put("tipo_equipamento", resultSet.getString("tipo_equipamento"));
                        fields.put("tipo_vinculo", resultSet.getString("tipo_vinculo"));
                        fields.put("quantidade", resultSet.getBigDecimal("quantidade"));

                        memoryService.registrarEvidencias(
                                "RDO_EQUIPAMENTO",
                                itemId,
                                FONTE,
                                fields
                        );
                    }

                    return null;
                },
                rdoId
        );
    }

    private void registrarMateriaisRdo(String rdoId) {
        jdbcTemplate.query(
                """
                SELECT
                    id,
                    material_nome,
                    unidade,
                    quantidade_prevista,
                    quantidade_usinada,
                    quantidade_aplicada,
                    quantidade_sobra,
                    nota_fiscal,
                    fornecedor
                FROM rdo_material
                WHERE rdo_id = ?
                """,
                resultSet -> {
                    while (resultSet.next()) {
                        String itemId = resultSet.getString("id");
                        String materialNome = resultSet.getString("material_nome");

                        memoryService.registrarObjeto(
                                "MATERIAL_RDO",
                                itemId,
                                materialNome,
                                "Material " + materialNome,
                                "REGISTRADO",
                                FONTE,
                                "rdo_material",
                                metadataRdo(rdoId)
                        );

                        memoryService.registrarRelacaoAtiva(
                                "RDO",
                                rdoId,
                                "MATERIAL_RDO",
                                itemId,
                                "CONSOME_MATERIAL",
                                FONTE,
                                "RDO registra consumo ou movimentação de material."
                        );

                        Map<String, Object> fields = new LinkedHashMap<>();
                        fields.put("material_nome", materialNome);
                        fields.put("unidade", resultSet.getString("unidade"));
                        fields.put("quantidade_prevista", resultSet.getBigDecimal("quantidade_prevista"));
                        fields.put("quantidade_usinada", resultSet.getBigDecimal("quantidade_usinada"));
                        fields.put("quantidade_aplicada", resultSet.getBigDecimal("quantidade_aplicada"));
                        fields.put("quantidade_sobra", resultSet.getBigDecimal("quantidade_sobra"));
                        fields.put("nota_fiscal", resultSet.getString("nota_fiscal"));
                        fields.put("fornecedor", resultSet.getString("fornecedor"));

                        memoryService.registrarEvidencias(
                                "MATERIAL_RDO",
                                itemId,
                                FONTE,
                                fields
                        );
                    }

                    return null;
                },
                rdoId
        );
    }

    private void registrarControlesGeometricosRdo(String rdoId) {
        jdbcTemplate.query(
                """
                SELECT
                    id,
                    subtrecho,
                    estaca_inicial,
                    estaca_final,
                    km_inicial,
                    km_final,
                    comprimento_m,
                    largura_m,
                    espessura_media_cm,
                    area_m2,
                    volume_m3,
                    massa_tonelada
                FROM rdo_controle_geometrico
                WHERE rdo_id = ?
                """,
                resultSet -> {
                    while (resultSet.next()) {
                        String itemId = resultSet.getString("id");
                        String nome = primeiroNaoVazio(
                                resultSet.getString("subtrecho"),
                                resultSet.getString("km_inicial"),
                                itemId
                        );

                        memoryService.registrarObjeto(
                                "CONTROLE_GEOMETRICO",
                                itemId,
                                itemId,
                                "Controle geométrico " + nome,
                                "REGISTRADO",
                                FONTE,
                                "rdo_controle_geometrico",
                                metadataRdo(rdoId)
                        );

                        memoryService.registrarRelacaoAtiva(
                                "RDO",
                                rdoId,
                                "CONTROLE_GEOMETRICO",
                                itemId,
                                "POSSUI_CONTROLE_GEOMETRICO",
                                FONTE,
                                "RDO possui controle geométrico."
                        );

                        Map<String, Object> fields = new LinkedHashMap<>();
                        fields.put("subtrecho", resultSet.getString("subtrecho"));
                        fields.put("estaca_inicial", resultSet.getString("estaca_inicial"));
                        fields.put("estaca_final", resultSet.getString("estaca_final"));
                        fields.put("km_inicial", resultSet.getString("km_inicial"));
                        fields.put("km_final", resultSet.getString("km_final"));
                        fields.put("comprimento_m", resultSet.getBigDecimal("comprimento_m"));
                        fields.put("largura_m", resultSet.getBigDecimal("largura_m"));
                        fields.put("espessura_media_cm", resultSet.getBigDecimal("espessura_media_cm"));
                        fields.put("area_m2", resultSet.getBigDecimal("area_m2"));
                        fields.put("volume_m3", resultSet.getBigDecimal("volume_m3"));
                        fields.put("massa_tonelada", resultSet.getBigDecimal("massa_tonelada"));

                        memoryService.registrarEvidencias(
                                "CONTROLE_GEOMETRICO",
                                itemId,
                                FONTE,
                                fields
                        );
                    }

                    return null;
                },
                rdoId
        );
    }

    private void registrarFotosRdo(String rdoId) {
        jdbcTemplate.query(
                """
                SELECT
                    id,
                    nome,
                    nome_original,
                    mime_type,
                    tamanho_original_bytes,
                    tamanho_comprimido_bytes,
                    sync_status,
                    criado_em
                FROM rdo_attachment
                WHERE rdo_id = ?
                  AND removido_em IS NULL
                """,
                resultSet -> {
                    while (resultSet.next()) {
                        String attachmentId = resultSet.getString("id");
                        String nome = primeiroNaoVazio(
                                resultSet.getString("nome"),
                                resultSet.getString("nome_original"),
                                attachmentId
                        );

                        memoryService.registrarObjeto(
                                "RDO_FOTO",
                                attachmentId,
                                attachmentId,
                                "Foto " + nome,
                                resultSet.getString("sync_status"),
                                FONTE,
                                "rdo_attachment",
                                metadataRdo(rdoId)
                        );

                        memoryService.registrarRelacaoAtiva(
                                "RDO",
                                rdoId,
                                "RDO_FOTO",
                                attachmentId,
                                "POSSUI_FOTO",
                                FONTE,
                                "RDO possui foto registrada offline ou sincronizada."
                        );

                        Map<String, Object> fields = new LinkedHashMap<>();
                        fields.put("nome", resultSet.getString("nome"));
                        fields.put("nome_original", resultSet.getString("nome_original"));
                        fields.put("mime_type", resultSet.getString("mime_type"));
                        fields.put("tamanho_original_bytes", resultSet.getLong("tamanho_original_bytes"));
                        fields.put("tamanho_comprimido_bytes", resultSet.getLong("tamanho_comprimido_bytes"));
                        fields.put("sync_status", resultSet.getString("sync_status"));
                        fields.put("criado_em", resultSet.getTimestamp("criado_em"));

                        memoryService.registrarEvidencias(
                                "RDO_FOTO",
                                attachmentId,
                                FONTE,
                                fields
                        );
                    }

                    return null;
                },
                rdoId
        );
    }

    private Map<String, Object> metadataRdo(String rdoId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("rdoId", rdoId);
        return metadata;
    }

    private List<Map<String, Object>> relatedEntities(
            String obraId,
            String programacaoId
    ) {
        List<Map<String, Object>> related =
                new java.util.ArrayList<>();

        if (obraId != null && !obraId.isBlank()) {
            Map<String, Object> obra = new LinkedHashMap<>();
            obra.put("tipo", "OBRA");
            obra.put("id", obraId);
            related.add(obra);
        }

        if (
                programacaoId != null
                && !programacaoId.isBlank()
        ) {
            Map<String, Object> programacao =
                    new LinkedHashMap<>();
            programacao.put(
                    "tipo",
                    "PROGRAMACAO_OPERACIONAL"
            );
            programacao.put("id", programacaoId);
            related.add(programacao);
        }

        return related;
    }

    private String primeiroNaoVazio(String... valores) {
        for (String valor : valores) {
            if (valor != null && !valor.isBlank()) {
                return valor.trim();
            }
        }

        return "";
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
                            TO_CHAR(
                                data_programacao,
                                'DD/MM/YYYY'
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
            String rdoId,
            String obraId,
            String programacaoId,
            String numeroRdo,
            String status
    ) {
        Map<String, Object> payload =
                new LinkedHashMap<>();

        payload.put("rdoId", rdoId);
        payload.put("obraId", obraId);
        payload.put("programacaoId", programacaoId);
        payload.put("numeroRdo", numeroRdo);
        payload.put("status", status);
        payload.put("schemaVersion", 1);

        return payload;
    }

    private Map<String, Object> payloadEdicao(
            String rdoId,
            String obraId,
            String programacaoId,
            String numeroRdo,
            String status,
            long versaoAnterior,
            long versaoNova,
            List<Map<String, Object>> alteracoes
    ) {
        Map<String, Object> payload =
                payloadBase(
                        rdoId,
                        obraId,
                        programacaoId,
                        numeroRdo,
                        status
                );

        payload.put("schemaVersion", 1);
        payload.put("versaoAnterior", versaoAnterior);
        payload.put("versaoNova", versaoNova);
        payload.put(
                "alteracoes",
                alteracoes == null ? List.of() : alteracoes
        );

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
