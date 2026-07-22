package com.projeto.cortex.rdos;

import com.projeto.cortex.financeiro.PrevisaoFinanceiraService;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class RdoDraftUpdateService {

    private final JdbcTemplate jdbcTemplate;
    private final RdoQueryService queryService;
    private final RdoAssetEligibilityService assetEligibilityService;
    private final RdoMemoryPublisher memoryPublisher;
    private final RdoChangeAuditService auditService;
    private final RdoOperationalDetailService operationalDetailService;
    private final RdoAttachmentService attachmentService;
    private final RdoOperationalEventService operationalEventService;
    private final PrevisaoFinanceiraService previsaoFinanceiraService;

    public RdoDraftUpdateService(
            JdbcTemplate jdbcTemplate,
            RdoQueryService queryService,
            RdoAssetEligibilityService assetEligibilityService,
            RdoMemoryPublisher memoryPublisher,
            RdoChangeAuditService auditService,
            RdoOperationalDetailService operationalDetailService,
            RdoAttachmentService attachmentService,
            RdoOperationalEventService operationalEventService,
            PrevisaoFinanceiraService previsaoFinanceiraService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.queryService = queryService;
        this.assetEligibilityService = assetEligibilityService;
        this.memoryPublisher = memoryPublisher;
        this.auditService = auditService;
        this.operationalDetailService = operationalDetailService;
        this.attachmentService = attachmentService;
        this.operationalEventService = operationalEventService;
        this.previsaoFinanceiraService = previsaoFinanceiraService;
    }

    @Transactional
    public RdoResponse atualizarRascunho(String rdoId, RdoCreateRequest request) {
        return atualizarRascunho(rdoId, request, null);
    }

    /**
     * Atualiza um rascunho e, quando vem do outbox, confere a versão no mesmo
     * UPDATE que grava o RDO. Assim nenhuma alteração de outro dispositivo pode
     * passar entre a pré-validação do sync e a substituição das coleções-filhas.
     */
    @Transactional
    public RdoResponse atualizarRascunho(
            String rdoId,
            RdoCreateRequest request,
            Long expectedEntityVersion
    ) {
        RdoChangeAuditService.RdoAuditSnapshot estadoAnterior =
                auditService.carregar(rdoId);

        validarRdoEditavel(estadoAnterior);

        if (request.obraId() == null || request.obraId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "obraId é obrigatório.");
        }

        if (request.dataRdo() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "dataRdo é obrigatório.");
        }

        if (!estadoAnterior.obraId().equals(request.obraId().strip())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A obra de um RDO existente não pode ser alterada."
            );
        }
        validarProvenienciaImutavelEData(
                rdoId,
                request,
                estadoAnterior.obraId()
        );

        ObraDados obra = buscarObra(request.obraId());
        ProgramacaoDados programacao = buscarProgramacaoOpcional(request.programacaoId(), request.obraId());

        if (programacao != null && !programacao.dataProgramacao().equals(request.dataRdo())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A programação selecionada não pertence à data do RDO."
            );
        }

        String diaSemana = diaSemanaPt(request.dataRdo());

        String cliente = primeiroNaoVazio(request.cliente(), programacao == null ? null : programacao.cliente(), obra.cliente());
        String contrato = primeiroNaoVazio(request.contrato(), obra.codigoContrato());
        String rodovia = primeiroNaoVazio(request.rodovia(), programacao == null ? null : programacao.rodovia(), obra.rodovia());
        String cidade = primeiroNaoVazio(request.cidade(), programacao == null ? null : programacao.cidade(), obra.cidade());
        String uf = primeiroNaoVazio(request.uf(), programacao == null ? null : programacao.uf(), obra.uf());

        String kmInicialProgramado = primeiroNaoVazio(
                request.kmInicialProgramado(),
                programacao == null ? null : programacao.kmInicial()
        );

        String kmFinalProgramado = primeiroNaoVazio(
                request.kmFinalProgramado(),
                programacao == null ? null : programacao.kmFinal()
        );

        String apontadorNome = validarEquipeEApontador(
                request.obraId(),
                request.maoObra(),
                request.apontadorColaboradorId()
        );
        assetEligibilityService.requireEligible(
                request.obraId(),
                request.equipamentos()
        );

        String updateSql = """
                UPDATE rdo
                SET
                    programacao_id = ?,
                    data_rdo = ?,
                    dia_semana = ?,
                    cliente = ?,
                    contrato = ?,
                    rodovia = ?,
                    cidade = ?,
                    uf = ?,
                    km_inicial_programado = ?,
                    km_final_programado = ?,
                    km_inicial_interditado = ?,
                    km_final_interditado = ?,
                    turno = ?,
                    hora_inicio = ?,
                    hora_fim = ?,
                    condicao_manha = ?,
                    condicao_tarde = ?,
                    condicao_noite = ?,
                    pluviometria_mm = ?,
                    observacoes = ?,
                    preenchido_por = ?,
                    apontador_rdo = ?,
                    apontador_colaborador_id = ?,
                    encarregado_obra = ?,
                    fiscalizacao_campo = ?,
                    versao_linha = versao_linha + 1
                WHERE id = ?
                  AND status = 'RASCUNHO'
                """;
        if (expectedEntityVersion != null) {
            updateSql += """
                    AND EXISTS (
                        SELECT 1
                        FROM cortex_estado_entidade sync_state
                        WHERE sync_state.tipo_entidade = 'RDO'
                          AND sync_state.entidade_id = rdo.id
                          AND sync_state.versao_entidade = ?
                    )
                    """;
        }

        Object[] updateParameters = {
                programacao == null ? null : programacao.id(),
                request.dataRdo(),
                diaSemana,
                cliente,
                contrato,
                rodovia,
                cidade,
                uf,
                kmInicialProgramado,
                kmFinalProgramado,
                request.kmInicialInterditado(),
                request.kmFinalInterditado(),
                request.turno(),
                request.horaInicio(),
                request.horaFim(),
                request.condicaoManha(),
                request.condicaoTarde(),
                request.condicaoNoite(),
                request.pluviometriaMm(),
                request.observacoes(),
                nuloSeVazio(request.preenchidoPor()),
                apontadorNome,
                nuloSeVazio(request.apontadorColaboradorId()),
                nuloSeVazio(request.encarregadoObra()),
                nuloSeVazio(request.fiscalizacaoCampo()),
                rdoId
        };
        if (expectedEntityVersion != null) {
            updateParameters = Arrays.copyOf(
                    updateParameters,
                    updateParameters.length + 1
            );
            updateParameters[updateParameters.length - 1] = expectedEntityVersion;
        }

        int updated = jdbcTemplate.update(updateSql, updateParameters);
        if (updated != 1) {
            if (expectedEntityVersion != null) {
                throw new RdoDraftVersionConflictException(
                        expectedEntityVersion,
                        versaoEntidadeAtual(rdoId)
                );
            }
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "O RDO não está mais disponível para edição."
            );
        }

        apagarItensExcetoMaoObra(rdoId);

        reconciliarMaoObra(rdoId, request.obraId(), request.maoObra());
        inserirEquipamentos(rdoId, request.equipamentos());
        inserirMateriais(rdoId, request.materiais());
        inserirControlesGeometricos(rdoId, request.controlesGeometricos());
        operationalDetailService.substituirDetalhes(
                rdoId,
                request.obraId(),
                programacao == null ? null : programacao.id(),
                request.dataRdo(),
                request.turno(),
                request.servicosExecutados(),
                request.alocacoesColaboradores()
        );

        attachmentService.substituirAttachments(
                rdoId,
                request.obraId(),
                request.attachments()
        );

        RdoChangeAuditService.RdoAuditSnapshot estadoNovo =
                auditService.carregar(rdoId);

        memoryPublisher.registrarRdoEditado(
                rdoId,
                request.obraId(),
                programacao == null ? null : programacao.id(),
                estadoAnterior.numeroRdo(),
                "RASCUNHO",
                estadoAnterior.versaoLinha(),
                estadoNovo.versaoLinha(),
                auditService.calcularAlteracoes(
                        estadoAnterior,
                        estadoNovo
                        )
        );

        operationalEventService.registrarEventosCliente(
                rdoId,
                request.obraId(),
                request.operationalEvents()
        );

        previsaoFinanceiraService.recalcularAposMudancaRdo(
                request.obraId(),
                request.dataRdo(),
                null
        );

        return queryService.buscarPorId(rdoId);
    }

    private void validarRdoEditavel(
            RdoChangeAuditService.RdoAuditSnapshot snapshot
    ) {
        if (!"RASCUNHO".equals(snapshot.status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Apenas RDO em RASCUNHO pode ser editado."
            );
        }
    }

    private void validarProvenienciaImutavelEData(
            String rdoId,
            RdoCreateRequest request,
            String obraId
    ) {
        CreationProvenance persisted = jdbcTemplate.queryForObject(
                """
                SELECT current_rdo.previous_rdo_id,
                       current_rdo.creation_context_version,
                       current_rdo.client_mutation_id,
                       previous_rdo.data_rdo AS previous_rdo_date
                FROM rdo current_rdo
                LEFT JOIN rdo previous_rdo
                  ON previous_rdo.id = current_rdo.previous_rdo_id
                 AND previous_rdo.obra_id = current_rdo.obra_id
                WHERE current_rdo.id = ?
                  AND current_rdo.obra_id = ?
                """,
                (rs, rowNum) -> new CreationProvenance(
                        rs.getString("previous_rdo_id"),
                        rs.getObject("creation_context_version", Long.class),
                        rs.getString("client_mutation_id"),
                        rs.getObject("previous_rdo_date", java.time.LocalDate.class)
                ),
                rdoId,
                obraId
        );
        if (!Objects.equals(
                persisted.previousRdoId(),
                nuloSeVazio(request.previousRdoId())
        ) || !Objects.equals(
                persisted.creationContextVersion(),
                request.creationContextVersion()
        ) || !Objects.equals(
                persisted.clientMutationId(),
                nuloSeVazio(request.clientMutationId())
        )) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A proveniência de criação do RDO não pode ser alterada."
            );
        }
        if (persisted.previousRdoDate() != null
                && !request.dataRdo().isAfter(persisted.previousRdoDate())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A data do RDO deve ser posterior ao RDO de origem."
            );
        }
    }

    private long versaoEntidadeAtual(String rdoId) {
        Long version = jdbcTemplate.queryForObject(
                """
                SELECT versao_entidade
                FROM cortex_estado_entidade
                WHERE tipo_entidade = 'RDO'
                  AND entidade_id = ?
                """,
                Long.class,
                rdoId
        );
        return version == null ? 0 : version;
    }

    private void apagarItensExcetoMaoObra(String rdoId) {
        jdbcTemplate.update("DELETE FROM rdo_controle_geometrico WHERE rdo_id = ?", rdoId);
        jdbcTemplate.update("DELETE FROM rdo_material WHERE rdo_id = ?", rdoId);
        jdbcTemplate.update("DELETE FROM rdo_equipamento WHERE rdo_id = ?", rdoId);
    }

    private void reconciliarMaoObra(
            String rdoId,
            String obraId,
            List<RdoCreateRequest.MaoObraItem> itens
    ) {
        List<RdoCreateRequest.MaoObraItem> requested = listaSegura(itens);
        Set<String> requestedIds = new HashSet<>();
        for (RdoCreateRequest.MaoObraItem item : requested) {
            String itemId = requireUuid(item.id(), "maoObra.id");
            if (!requestedIds.add(itemId)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "maoObra contém IDs duplicados."
                );
            }
        }

        List<String> existingIds = jdbcTemplate.queryForList(
                "SELECT id FROM rdo_mao_obra WHERE rdo_id = ?",
                String.class,
                rdoId
        );
        List<String> removed = existingIds.stream()
                .filter(id -> !requestedIds.contains(id))
                .toList();
        if (!removed.isEmpty()) {
            String placeholders = String.join(",", java.util.Collections.nCopies(removed.size(), "?"));
            Object[] references = removed.toArray();
            Integer usedAsProvenance = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM rdo_mao_obra WHERE origem_item_id IN (" + placeholders + ")",
                    Integer.class,
                    references
            );
            if (usedAsProvenance != null && usedAsProvenance > 0) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "A mão de obra já é origem de outro RDO e não pode ser removida."
                );
            }
            Object[] deleteParameters = new Object[removed.size() + 1];
            deleteParameters[0] = rdoId;
            for (int index = 0; index < removed.size(); index++) {
                deleteParameters[index + 1] = removed.get(index);
            }
            jdbcTemplate.update(
                    "DELETE FROM rdo_mao_obra WHERE rdo_id = ? AND id IN (" + placeholders + ")",
                    deleteParameters
            );
        }

        String previousRdoId = jdbcTemplate.queryForObject(
                "SELECT previous_rdo_id FROM rdo WHERE id = ?",
                String.class,
                rdoId
        );
        for (RdoCreateRequest.MaoObraItem item : requested) {
            String itemId = requireUuid(item.id(), "maoObra.id");
            String collaboratorId = requireText(
                    item.colaboradorId(), "maoObra.colaboradorId"
            );
            validarColaboradorDaObra(collaboratorId, obraId);
            String requestedOrigin = nuloSeVazio(item.origemItemId());
            ExistingWorkforceItem persisted = jdbcTemplate.query(
                    "SELECT origem_item_id FROM rdo_mao_obra WHERE id = ? AND rdo_id = ?",
                    rs -> rs.next()
                            ? new ExistingWorkforceItem(rs.getString("origem_item_id"))
                            : null,
                    itemId,
                    rdoId
            );
            if (persisted != null
                    && !Objects.equals(persisted.originItemId(), requestedOrigin)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "A origem de um item de mão de obra não pode ser alterada."
                );
            }
            String origin = persisted == null
                    ? requestedOrigin
                    : persisted.originItemId();
            validarOrigemItem(origin, previousRdoId, collaboratorId, obraId);

            int updated = jdbcTemplate.update(
                    """
                    UPDATE rdo_mao_obra
                    SET colaborador_id = ?,
                        nome_colaborador = ?,
                        cargo = ?,
                        tipo_vinculo = ?,
                        quantidade = ?,
                        hora_inicio = ?,
                        hora_fim = ?,
                        observacoes = ?,
                        origem_item_id = ?
                    WHERE id = ?
                      AND rdo_id = ?
                    """,
                    collaboratorId,
                    item.nomeColaborador(),
                    item.cargo(),
                    primeiroNaoVazio(item.tipoVinculo(), "CONTRATADO"),
                    valorOuUm(item.quantidade()),
                    item.horaInicio(),
                    item.horaFim(),
                    item.observacoes(),
                    origin,
                    itemId,
                    rdoId
            );
            if (updated == 0) {
                Integer collision = jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM rdo_mao_obra WHERE id = ?",
                        Integer.class,
                        itemId
                );
                if (collision != null && collision > 0) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "O ID de mão de obra já pertence a outro RDO."
                    );
                }
                jdbcTemplate.update(
                        """
                        INSERT INTO rdo_mao_obra (
                            id, rdo_id, colaborador_id, nome_colaborador, cargo,
                            tipo_vinculo, quantidade, hora_inicio, hora_fim,
                            observacoes, origem_item_id
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        itemId,
                        rdoId,
                        collaboratorId,
                        item.nomeColaborador(),
                        item.cargo(),
                        primeiroNaoVazio(item.tipoVinculo(), "CONTRATADO"),
                        valorOuUm(item.quantidade()),
                        item.horaInicio(),
                        item.horaFim(),
                        item.observacoes(),
                        origin
                );
            }
        }
    }

    private String validarEquipeEApontador(
            String obraId,
            List<RdoCreateRequest.MaoObraItem> itens,
            String apontadorId
    ) {
        Set<String> collaborators = new HashSet<>();
        for (RdoCreateRequest.MaoObraItem item : listaSegura(itens)) {
            requireUuid(item.id(), "maoObra.id");
            String collaboratorId = requireText(
                    item.colaboradorId(), "maoObra.colaboradorId"
            );
            if (!collaborators.add(collaboratorId)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "maoObra contém colaborador duplicado."
                );
            }
            validarColaboradorDaObra(collaboratorId, obraId);
        }
        String normalizedApontador = nuloSeVazio(apontadorId);
        if (normalizedApontador == null) {
            return null;
        }
        if (!collaborators.contains(normalizedApontador)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "O apontador deve integrar a mão de obra selecionada."
            );
        }
        validarColaboradorDaObra(normalizedApontador, obraId);
        return jdbcTemplate.queryForObject(
                "SELECT nome FROM colaborador WHERE id = ?",
                String.class,
                normalizedApontador
        );
    }

    private void validarColaboradorDaObra(String collaboratorId, String obraId) {
        Integer valid = jdbcTemplate.queryForObject(
                """
                SELECT CASE WHEN EXISTS (
                    SELECT 1
                    FROM colaborador collaborator
                    JOIN vinculo_colaborador_obra link
                      ON link.colaborador_id = collaborator.id
                     AND link.obra_id = ?
                     AND link.status = 'ATIVO'
                    WHERE collaborator.id = ?
                      AND collaborator.ativo = TRUE
                      AND collaborator.deletado_em IS NULL
                ) THEN 1 ELSE 0 END
                """,
                Integer.class,
                obraId,
                collaboratorId
        );
        if (valid == null || valid != 1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Colaborador não está ativo e vinculado à obra do RDO."
            );
        }
    }

    private void validarOrigemItem(
            String originItemId,
            String previousRdoId,
            String collaboratorId,
            String obraId
    ) {
        if (originItemId == null) {
            return;
        }
        Integer valid = jdbcTemplate.queryForObject(
                """
                SELECT CASE WHEN EXISTS (
                    SELECT 1
                    FROM rdo_mao_obra source_item
                    JOIN rdo source_rdo ON source_rdo.id = source_item.rdo_id
                    WHERE source_item.id = ?
                      AND source_item.rdo_id = ?
                      AND source_item.colaborador_id = ?
                      AND source_rdo.obra_id = ?
                ) THEN 1 ELSE 0 END
                """,
                Integer.class,
                originItemId,
                previousRdoId,
                collaboratorId,
                obraId
        );
        if (valid == null || valid != 1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "O item de origem não pertence ao RDO anterior, colaborador e obra informados."
            );
        }
    }

    private String requireUuid(String value, String fieldName) {
        String normalized = requireText(value, fieldName);
        try {
            return UUID.fromString(normalized).toString();
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    fieldName + " deve ser UUID."
            );
        }
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    fieldName + " é obrigatório."
            );
        }
        return value.strip();
    }

    private void inserirEquipamentos(String rdoId, List<RdoCreateRequest.EquipamentoItem> itens) {
        for (RdoCreateRequest.EquipamentoItem item : listaSegura(itens)) {
            jdbcTemplate.update(
                    """
                    INSERT INTO rdo_equipamento (
                        id, rdo_id, asset_id, prefixo, descricao, tipo_equipamento,
                        tipo_vinculo, quantidade, hora_inicio, hora_fim, observacoes
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID().toString(),
                    rdoId,
                    nuloSeVazio(item.assetId()),
                    item.prefixo(),
                    item.descricao(),
                    item.tipoEquipamento(),
                    primeiroNaoVazio(item.tipoVinculo(), "PROPRIO"),
                    valorOuUm(item.quantidade()),
                    item.horaInicio(),
                    item.horaFim(),
                    item.observacoes()
            );
        }
    }

    private void inserirMateriais(String rdoId, List<RdoCreateRequest.MaterialItem> itens) {
        for (RdoCreateRequest.MaterialItem item : listaSegura(itens)) {
            if (item.materialNome() == null || item.materialNome().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "materialNome é obrigatório.");
            }

            BigDecimal sobra = calcularSobra(
                    item.quantidadeUsinada(),
                    item.quantidadeAplicada(),
                    item.quantidadeSobra()
            );

            jdbcTemplate.update(
                    """
                    INSERT INTO rdo_material (
                        id, rdo_id, material_nome, unidade, quantidade_prevista,
                        quantidade_usinada, quantidade_aplicada, quantidade_sobra,
                        nota_fiscal, fornecedor, observacoes
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID().toString(),
                    rdoId,
                    item.materialNome(),
                    item.unidade(),
                    item.quantidadePrevista(),
                    item.quantidadeUsinada(),
                    item.quantidadeAplicada(),
                    sobra,
                    item.notaFiscal(),
                    item.fornecedor(),
                    item.observacoes()
            );
        }
    }

    private void inserirControlesGeometricos(String rdoId, List<RdoCreateRequest.ControleGeometricoItem> itens) {
        for (RdoCreateRequest.ControleGeometricoItem item : listaSegura(itens)) {
            BigDecimal espessuraMediaCm = calcularMedia(
                    item.espessura1Cm(),
                    item.espessura2Cm(),
                    item.espessura3Cm()
            );

            BigDecimal areaM2 = calcularArea(item.comprimentoM(), item.larguraM());
            BigDecimal volumeM3 = calcularVolume(areaM2, espessuraMediaCm);
            BigDecimal massaTonelada = calcularMassa(volumeM3, item.densidade());

            jdbcTemplate.update(
                    """
                    INSERT INTO rdo_controle_geometrico (
                        id, rdo_id, subtrecho, estaca_inicial, estaca_final,
                        numero, km_inicial, km_final, pista, faixa,
                        ordem_servico, atividade_observacoes, comprimento_m, largura_m,
                        espessura_1_cm, espessura_2_cm, espessura_3_cm,
                        espessura_media_cm, area_m2, volume_m3,
                        densidade, massa_tonelada, observacoes
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID().toString(),
                    rdoId,
                    item.subtrecho(),
                    item.estacaInicial(),
                    item.estacaFinal(),
                    item.numero(),
                    item.kmInicial(),
                    item.kmFinal(),
                    item.pista(),
                    item.faixa(),
                    item.ordemServico(),
                    item.atividadeObservacoes(),
                    item.comprimentoM(),
                    item.larguraM(),
                    item.espessura1Cm(),
                    item.espessura2Cm(),
                    item.espessura3Cm(),
                    espessuraMediaCm,
                    areaM2,
                    volumeM3,
                    item.densidade(),
                    massaTonelada,
                    item.observacoes()
            );
        }
    }

    private ObraDados buscarObra(String obraId) {
        try {
            return jdbcTemplate.queryForObject(
                    """
                    SELECT id, codigo_contrato, cliente, cidade, uf, rodovia
                    FROM obra
                    WHERE id = ?
                      AND arquivado_em IS NULL
                    """,
                    (rs, rowNum) -> new ObraDados(
                            rs.getString("id"),
                            rs.getString("codigo_contrato"),
                            rs.getString("cliente"),
                            rs.getString("cidade"),
                            rs.getString("uf"),
                            rs.getString("rodovia")
                    ),
                    obraId
            );
        } catch (DataAccessException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Obra não encontrada: " + obraId);
        }
    }

    private ProgramacaoDados buscarProgramacaoOpcional(String programacaoId, String obraId) {
        if (programacaoId == null || programacaoId.isBlank()) {
            return null;
        }

        try {
            return jdbcTemplate.queryForObject(
                    """
                    SELECT id, obra_id, data_programacao, cliente, cidade, uf, rodovia, km_inicial, km_final
                    FROM programacao_operacional
                    WHERE id = ?
                      AND obra_id = ?
                      AND cancelado_em IS NULL
                    """,
                    (rs, rowNum) -> new ProgramacaoDados(
                            rs.getString("id"),
                            rs.getString("obra_id"),
                            rs.getDate("data_programacao").toLocalDate(),
                            rs.getString("cliente"),
                            rs.getString("cidade"),
                            rs.getString("uf"),
                            rs.getString("rodovia"),
                            rs.getString("km_inicial"),
                            rs.getString("km_final")
                    ),
                    programacaoId,
                    obraId
            );
        } catch (DataAccessException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Programação não encontrada para a obra informada."
            );
        }
    }

    private BigDecimal calcularSobra(BigDecimal quantidadeUsinada, BigDecimal quantidadeAplicada, BigDecimal quantidadeSobraInformada) {
        if (quantidadeSobraInformada != null) {
            return arredondar(quantidadeSobraInformada);
        }

        if (quantidadeUsinada == null || quantidadeAplicada == null) {
            return null;
        }

        return arredondar(quantidadeUsinada.subtract(quantidadeAplicada));
    }

    private BigDecimal calcularMedia(BigDecimal... valores) {
        BigDecimal soma = BigDecimal.ZERO;
        int quantidade = 0;

        for (BigDecimal valor : valores) {
            if (valor != null) {
                soma = soma.add(valor);
                quantidade++;
            }
        }

        if (quantidade == 0) {
            return null;
        }

        return soma.divide(BigDecimal.valueOf(quantidade), 3, RoundingMode.HALF_UP);
    }

    private BigDecimal calcularArea(BigDecimal comprimentoM, BigDecimal larguraM) {
        if (comprimentoM == null || larguraM == null) {
            return null;
        }

        return arredondar(comprimentoM.multiply(larguraM));
    }

    private BigDecimal calcularVolume(BigDecimal areaM2, BigDecimal espessuraMediaCm) {
        if (areaM2 == null || espessuraMediaCm == null) {
            return null;
        }

        BigDecimal espessuraM = espessuraMediaCm.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
        return arredondar(areaM2.multiply(espessuraM));
    }

    private BigDecimal calcularMassa(BigDecimal volumeM3, BigDecimal densidade) {
        if (volumeM3 == null || densidade == null) {
            return null;
        }

        return arredondar(volumeM3.multiply(densidade));
    }

    private BigDecimal arredondar(BigDecimal valor) {
        if (valor == null) {
            return null;
        }

        return valor.setScale(3, RoundingMode.HALF_UP);
    }

    private BigDecimal valorOuUm(BigDecimal valor) {
        if (valor == null) {
            return BigDecimal.ONE;
        }

        return valor;
    }

    private <T> List<T> listaSegura(List<T> lista) {
        if (lista == null) {
            return List.of();
        }

        return lista;
    }

    private String primeiroNaoVazio(String... valores) {
        for (String valor : valores) {
            if (valor != null && !valor.isBlank()) {
                return valor;
            }
        }

        return null;
    }

    private String nuloSeVazio(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }

        return valor;
    }

    private String diaSemanaPt(java.time.LocalDate data) {
        DayOfWeek dia = data.getDayOfWeek();

        return switch (dia) {
            case MONDAY -> "SEGUNDA-FEIRA";
            case TUESDAY -> "TERÇA-FEIRA";
            case WEDNESDAY -> "QUARTA-FEIRA";
            case THURSDAY -> "QUINTA-FEIRA";
            case FRIDAY -> "SEXTA-FEIRA";
            case SATURDAY -> "SÁBADO";
            case SUNDAY -> "DOMINGO";
        };
    }

    private record ObraDados(
            String id,
            String codigoContrato,
            String cliente,
            String cidade,
            String uf,
            String rodovia
    ) {
    }

    private record ProgramacaoDados(
            String id,
            String obraId,
            java.time.LocalDate dataProgramacao,
            String cliente,
            String cidade,
            String uf,
            String rodovia,
            String kmInicial,
            String kmFinal
    ) {
    }

    private record ExistingWorkforceItem(String originItemId) {
    }

    private record CreationProvenance(
            String previousRdoId,
            Long creationContextVersion,
            String clientMutationId,
            java.time.LocalDate previousRdoDate
    ) {
    }
}
