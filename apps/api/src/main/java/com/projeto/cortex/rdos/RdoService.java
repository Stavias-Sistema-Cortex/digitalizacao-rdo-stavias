package com.projeto.cortex.rdos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.PrevisaoFinanceiraService;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class RdoService {

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserService currentUserService;
    private final RdoCreationPayloadHasher creationPayloadHasher;
    private final RdoAssetEligibilityService assetEligibilityService;
    private final RdoMemoryPublisher memoryPublisher;
    private final RdoOperationalDetailService operationalDetailService;
    private final RdoAttachmentService attachmentService;
    private final RdoOperationalEventService operationalEventService;
    private final PrevisaoFinanceiraService previsaoFinanceiraService;
    private final RdoQueryService queryService;

    public RdoService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            CurrentUserService currentUserService,
            RdoAssetEligibilityService assetEligibilityService,
            RdoMemoryPublisher memoryPublisher,
            RdoOperationalDetailService operationalDetailService,
            RdoAttachmentService attachmentService,
            RdoOperationalEventService operationalEventService,
            PrevisaoFinanceiraService previsaoFinanceiraService,
            RdoQueryService queryService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUserService = currentUserService;
        this.creationPayloadHasher = new RdoCreationPayloadHasher(objectMapper);
        this.assetEligibilityService = assetEligibilityService;
        this.memoryPublisher = memoryPublisher;
        this.operationalDetailService = operationalDetailService;
        this.attachmentService = attachmentService;
        this.operationalEventService = operationalEventService;
        this.previsaoFinanceiraService = previsaoFinanceiraService;
        this.queryService = queryService;
    }

    @Transactional
    public RdoResponse criarRascunho(RdoCreateRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "RDO é obrigatório.");
        }
        if (request.obraId() == null || request.obraId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "obraId é obrigatório.");
        }

        if (request.dataRdo() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "dataRdo é obrigatório.");
        }

        String obraId = request.obraId().strip();
        String rdoId = requireUuid(request.id(), "id");
        String clientMutationId = textoLimitadoObrigatorio(
                request.clientMutationId(), "clientMutationId", 120
        );
        String ownerId = requireUuid(currentUserService.requireUserId(), "usuário autenticado");
        String payloadHash = creationPayloadHasher.hash(request);
        bloquearCriacaoDeterministicamente(
                rdoId,
                ownerId + ":" + clientMutationId
        );
        RdoExistente replay = buscarReplay(clientMutationId, rdoId);
        if (replay != null) {
            if (!replay.id().equals(rdoId)
                    || !Objects.equals(replay.clientMutationId(), clientMutationId)
                    || !Objects.equals(replay.creationOwnerId(), ownerId)
                    || !Objects.equals(replay.creationPayloadHash(), payloadHash)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "A mutação de criação já foi aplicada com outro conteúdo, RDO ou obra."
                );
            }
            return queryService.buscarPorId(replay.id());
        }

        ObraDados obra = buscarObra(obraId);
        ProgramacaoDados programacao = buscarProgramacaoOpcional(request.programacaoId(), obraId);

        if (programacao != null && !programacao.dataProgramacao().equals(request.dataRdo())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A programação selecionada não pertence à data do RDO."
            );
        }

        validarProveniencia(request, obraId);
        String apontadorNome = validarEquipeEApontador(request, obraId);
        assetEligibilityService.requireEligible(obraId, request.equipamentos());
        NumberAllocation number = alocarNumero(obraId);
        String status = "RASCUNHO";
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

        jdbcTemplate.update(
                """
                INSERT INTO rdo (
                    id,
                    obra_id,
                    programacao_id,
                    numero_rdo,
                    numero_sequencial,
                    data_rdo,
                    previous_rdo_id,
                    creation_context_version,
                    client_mutation_id,
                    creation_owner_id,
                    creation_payload_hash,
                    apontador_colaborador_id,
                    dia_semana,
                    cliente,
                    contrato,
                    rodovia,
                    cidade,
                    uf,
                    km_inicial_programado,
                    km_final_programado,
                    km_inicial_interditado,
                    km_final_interditado,
                    turno,
                    hora_inicio,
                    hora_fim,
                    condicao_manha,
                    condicao_tarde,
                    condicao_noite,
                    pluviometria_mm,
                    status,
                    observacoes,
                    preenchido_por,
                    apontador_rdo,
                    encarregado_obra,
                    fiscalizacao_campo
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                rdoId,
                obraId,
                programacao == null ? null : programacao.id(),
                number.display(),
                number.value(),
                request.dataRdo(),
                nuloSeVazio(request.previousRdoId()),
                request.creationContextVersion(),
                clientMutationId,
                ownerId,
                payloadHash,
                nuloSeVazio(request.apontadorColaboradorId()),
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
                status,
                request.observacoes(),
                nuloSeVazio(request.preenchidoPor()),
                apontadorNome,
                nuloSeVazio(request.encarregadoObra()),
                nuloSeVazio(request.fiscalizacaoCampo())
        );

        List<RdoResponse.MaoObraItem> maoObra = inserirMaoObra(rdoId, request.maoObra());
        List<RdoResponse.EquipamentoItem> equipamentos = inserirEquipamentos(rdoId, request.equipamentos());
        List<RdoResponse.MaterialItem> materiais = inserirMateriais(rdoId, request.materiais());
        List<RdoResponse.ControleGeometricoItem> controles = inserirControlesGeometricos(
                rdoId,
                request.controlesGeometricos()
        );

        RdoOperationalDetailService.RdoOperationalDetails detalhes =
                operationalDetailService.substituirDetalhes(
                        rdoId,
                        obraId,
                        programacao == null ? null : programacao.id(),
                        request.dataRdo(),
                        request.turno(),
                        request.servicosExecutados(),
                        request.alocacoesColaboradores()
                );

        attachmentService.substituirAttachments(
                rdoId,
                obraId,
                request.attachments()
        );

        memoryPublisher.registrarRdoCriado(
                rdoId,
                obraId,
                programacao == null ? null : programacao.id(),
                number.display(),
                status
        );

        operationalEventService.registrarEventosCliente(
                rdoId,
                obraId,
                request.operationalEvents()
        );

        previsaoFinanceiraService.recalcularAposMudancaRdo(
                obraId,
                request.dataRdo(),
                null
        );

        return new RdoResponse(
                rdoId,
                obraId,
                programacao == null ? null : programacao.id(),
                number.display(),
                request.dataRdo(),
                nuloSeVazio(request.previousRdoId()),
                request.creationContextVersion(),
                clientMutationId,
                nuloSeVazio(request.apontadorColaboradorId()),
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
                status,
                request.observacoes(),
                nuloSeVazio(request.preenchidoPor()),
                apontadorNome,
                nuloSeVazio(request.encarregadoObra()),
                nuloSeVazio(request.fiscalizacaoCampo()),
                maoObra,
                equipamentos,
                materiais,
                controles,
                detalhes.servicosExecutados(),
                detalhes.alocacoesColaboradores(),
                attachmentService.listar(rdoId)
        );
    }

    private List<RdoResponse.MaoObraItem> inserirMaoObra(
            String rdoId,
            List<RdoCreateRequest.MaoObraItem> itens
    ) {
        List<RdoResponse.MaoObraItem> response = new ArrayList<>();

        for (RdoCreateRequest.MaoObraItem item : listaSegura(itens)) {
            String id = idOuNovo(item.id(), "maoObra.id");
            BigDecimal quantidade = valorOuUm(item.quantidade());
            String tipoVinculo = primeiroNaoVazio(item.tipoVinculo(), "CONTRATADO");
            String colaboradorId = nuloSeVazio(item.colaboradorId());

            jdbcTemplate.update(
                    """
                    INSERT INTO rdo_mao_obra (
                        id,
                        rdo_id,
                        colaborador_id,
                        nome_colaborador,
                        cargo,
                        tipo_vinculo,
                        quantidade,
                        hora_inicio,
                        hora_fim,
                        observacoes,
                        origem_item_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    id,
                    rdoId,
                    colaboradorId,
                    item.nomeColaborador(),
                    item.cargo(),
                    tipoVinculo,
                    quantidade,
                    item.horaInicio(),
                    item.horaFim(),
                    item.observacoes(),
                    nuloSeVazio(item.origemItemId())
            );

            response.add(new RdoResponse.MaoObraItem(
                    id,
                    colaboradorId,
                    item.nomeColaborador(),
                    item.cargo(),
                    tipoVinculo,
                    quantidade,
                    item.horaInicio(),
                    item.horaFim(),
                    item.observacoes(),
                    nuloSeVazio(item.origemItemId())
            ));
        }

        return response;
    }

    private List<RdoResponse.EquipamentoItem> inserirEquipamentos(
            String rdoId,
            List<RdoCreateRequest.EquipamentoItem> itens
    ) {
        List<RdoResponse.EquipamentoItem> response = new ArrayList<>();

        for (RdoCreateRequest.EquipamentoItem item : listaSegura(itens)) {
            String id = UUID.randomUUID().toString();
            BigDecimal quantidade = valorOuUm(item.quantidade());
            String tipoVinculo = primeiroNaoVazio(item.tipoVinculo(), "PROPRIO");
            String assetId = nuloSeVazio(item.assetId());

            jdbcTemplate.update(
                    """
                    INSERT INTO rdo_equipamento (
                        id,
                        rdo_id,
                        asset_id,
                        prefixo,
                        descricao,
                        tipo_equipamento,
                        tipo_vinculo,
                        quantidade,
                        hora_inicio,
                        hora_fim,
                        observacoes
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    id,
                    rdoId,
                    assetId,
                    item.prefixo(),
                    item.descricao(),
                    item.tipoEquipamento(),
                    tipoVinculo,
                    quantidade,
                    item.horaInicio(),
                    item.horaFim(),
                    item.observacoes()
            );

            response.add(new RdoResponse.EquipamentoItem(
                    id,
                    assetId,
                    item.prefixo(),
                    item.descricao(),
                    item.tipoEquipamento(),
                    tipoVinculo,
                    quantidade,
                    item.horaInicio(),
                    item.horaFim(),
                    item.observacoes()
            ));
        }

        return response;
    }

    private List<RdoResponse.MaterialItem> inserirMateriais(
            String rdoId,
            List<RdoCreateRequest.MaterialItem> itens
    ) {
        List<RdoResponse.MaterialItem> response = new ArrayList<>();

        for (RdoCreateRequest.MaterialItem item : listaSegura(itens)) {
            if (item.materialNome() == null || item.materialNome().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "materialNome é obrigatório.");
            }

            String id = UUID.randomUUID().toString();
            BigDecimal sobra = calcularSobra(
                    item.quantidadeUsinada(),
                    item.quantidadeAplicada(),
                    item.quantidadeSobra()
            );

            jdbcTemplate.update(
                    """
                    INSERT INTO rdo_material (
                        id,
                        rdo_id,
                        material_nome,
                        unidade,
                        quantidade_prevista,
                        quantidade_usinada,
                        quantidade_aplicada,
                        quantidade_sobra,
                        nota_fiscal,
                        fornecedor,
                        observacoes
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    id,
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

            response.add(new RdoResponse.MaterialItem(
                    id,
                    item.materialNome(),
                    item.unidade(),
                    item.quantidadePrevista(),
                    item.quantidadeUsinada(),
                    item.quantidadeAplicada(),
                    sobra,
                    item.notaFiscal(),
                    item.fornecedor(),
                    item.observacoes()
            ));
        }

        return response;
    }

    private List<RdoResponse.ControleGeometricoItem> inserirControlesGeometricos(
            String rdoId,
            List<RdoCreateRequest.ControleGeometricoItem> itens
    ) {
        List<RdoResponse.ControleGeometricoItem> response = new ArrayList<>();

        for (RdoCreateRequest.ControleGeometricoItem item : listaSegura(itens)) {
            String id = UUID.randomUUID().toString();

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
                        id,
                        rdo_id,
                        subtrecho,
                        numero,
                        estaca_inicial,
                        estaca_final,
                        km_inicial,
                        km_final,
                        pista,
                        faixa,
                        ordem_servico,
                        atividade_observacoes,
                        comprimento_m,
                        largura_m,
                        espessura_1_cm,
                        espessura_2_cm,
                        espessura_3_cm,
                        espessura_media_cm,
                        area_m2,
                        volume_m3,
                        densidade,
                        massa_tonelada,
                        observacoes
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    id,
                    rdoId,
                    item.subtrecho(),
                    item.numero(),
                    item.estacaInicial(),
                    item.estacaFinal(),
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

            response.add(new RdoResponse.ControleGeometricoItem(
                    id,
                    item.subtrecho(),
                    item.numero(),
                    item.estacaInicial(),
                    item.estacaFinal(),
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
            ));
        }

        return response;
    }


    private void bloquearCriacao(String key) {
        jdbcTemplate.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                rs -> null,
                key
        );
    }

    private void bloquearCriacaoDeterministicamente(
            String rdoId,
            String clientMutationId
    ) {
        if (rdoId.equals(clientMutationId)) {
            bloquearCriacao(rdoId);
            return;
        }
        String first = rdoId.compareTo(clientMutationId) < 0
                ? rdoId
                : clientMutationId;
        String second = first.equals(rdoId) ? clientMutationId : rdoId;
        bloquearCriacao(first);
        bloquearCriacao(second);
    }

    private RdoExistente buscarReplay(String clientMutationId, String rdoId) {
        return jdbcTemplate.query(
                """
                SELECT id, obra_id, data_rdo, previous_rdo_id,
                       creation_context_version, client_mutation_id,
                       creation_owner_id, creation_payload_hash
                FROM rdo
                WHERE id = ?
                   OR (? IS NOT NULL AND client_mutation_id = ?)
                ORDER BY CASE WHEN id = ? THEN 0 ELSE 1 END
                LIMIT 1
                """,
                rs -> rs.next()
                        ? new RdoExistente(
                                rs.getString("id"),
                                rs.getString("obra_id"),
                                rs.getDate("data_rdo").toLocalDate(),
                                rs.getString("previous_rdo_id"),
                                rs.getObject("creation_context_version", Long.class),
                                rs.getString("client_mutation_id"),
                                rs.getString("creation_owner_id"),
                                rs.getString("creation_payload_hash")
                        )
                        : null,
                rdoId,
                clientMutationId,
                clientMutationId,
                rdoId
        );
    }

    private void validarProveniencia(RdoCreateRequest request, String obraId) {
        if (request.creationContextVersion() == null
                || request.creationContextVersion() <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "creationContextVersion é obrigatório e deve ser positivo."
            );
        }
        String previousRdoId = nuloSeVazio(request.previousRdoId());
        Integer valid = jdbcTemplate.queryForObject(
                """
                SELECT CASE WHEN EXISTS (
                    SELECT 1
                    FROM rdo_creation_context_snapshot snapshot
                    WHERE snapshot.receipt_version = ?
                      AND snapshot.obra_id = ?
                      AND snapshot.selected_date = ?
                      AND snapshot.previous_rdo_id IS NOT DISTINCT FROM ?
                ) THEN 1 ELSE 0 END
                """,
                Integer.class,
                request.creationContextVersion(),
                obraId,
                request.dataRdo(),
                previousRdoId
        );
        if (valid == null || valid != 1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "O receipt de contexto não é válido para esta obra, data e RDO de origem."
            );
        }
    }

    private String validarEquipeEApontador(RdoCreateRequest request, String obraId) {
        Set<String> itemIds = new HashSet<>();
        Set<String> collaboratorIds = new HashSet<>();
        String previousRdoId = nuloSeVazio(request.previousRdoId());

        for (RdoCreateRequest.MaoObraItem item : listaSegura(request.maoObra())) {
            String itemId = idOuNovo(item.id(), "maoObra.id");
            if (!itemIds.add(itemId)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "maoObra contém IDs duplicados."
                );
            }
            String collaboratorId = requireText(item.colaboradorId(), "maoObra.colaboradorId");
            if (!collaboratorIds.add(collaboratorId)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "maoObra contém colaborador duplicado."
                );
            }
            validarColaboradorDaObra(collaboratorId, obraId);
            validarOrigemItem(
                    nuloSeVazio(item.origemItemId()),
                    previousRdoId,
                    collaboratorId,
                    obraId
            );
        }

        String apontadorId = nuloSeVazio(request.apontadorColaboradorId());
        if (apontadorId == null) {
            return null;
        }
        if (!collaboratorIds.contains(apontadorId)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "O apontador deve integrar a mão de obra selecionada."
            );
        }
        validarColaboradorDaObra(apontadorId, obraId);
        return jdbcTemplate.queryForObject(
                "SELECT nome FROM colaborador WHERE id = ?",
                String.class,
                apontadorId
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
        if (previousRdoId == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "origemItemId exige previousRdoId."
            );
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

    private NumberAllocation alocarNumero(String obraId) {
        Long initial = jdbcTemplate.queryForObject(
                """
                SELECT COALESCE(
                    MAX(substring(numero_rdo FROM '^RDO-([0-9]{1,18})$')::bigint) + 1,
                    1
                )
                FROM rdo
                WHERE obra_id = ?
                  AND numero_rdo ~ '^RDO-[0-9]{1,18}$'
                """,
                Long.class,
                obraId
        );
        jdbcTemplate.update(
                """
                INSERT INTO rdo_number_sequence (obra_id, next_value)
                VALUES (?, ?)
                ON CONFLICT (obra_id) DO UPDATE
                SET next_value = GREATEST(
                        rdo_number_sequence.next_value,
                        EXCLUDED.next_value
                    ),
                    updated_at = now()
                """,
                obraId,
                initial == null ? 1L : initial
        );
        Long allocated = jdbcTemplate.queryForObject(
                """
                UPDATE rdo_number_sequence
                SET next_value = next_value + 1,
                    updated_at = now()
                WHERE obra_id = ?
                RETURNING next_value - 1
                """,
                Long.class,
                obraId
        );
        long value = allocated == null ? 1L : allocated;
        return new NumberAllocation(value, RdoContextService.formatNumber(value));
    }

    private String idOuNovo(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            return UUID.randomUUID().toString();
        }
        try {
            return UUID.fromString(value.strip()).toString();
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    fieldName + " deve ser UUID."
            );
        }
    }

    private String requireUuid(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    fieldName + " é obrigatório e deve ser UUID."
            );
        }
        try {
            return UUID.fromString(value.strip()).toString();
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

    private String textoLimitadoOpcional(String value, String fieldName, int maxLength) {
        String normalized = nuloSeVazio(value);
        if (normalized != null && normalized.length() > maxLength) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    fieldName + " excede " + maxLength + " caracteres."
            );
        }
        return normalized;
    }

    private String textoLimitadoObrigatorio(String value, String fieldName, int maxLength) {
        String normalized = textoLimitadoOpcional(value, fieldName, maxLength);
        if (normalized == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    fieldName + " é obrigatório."
            );
        }
        return normalized;
    }

    private ObraDados buscarObra(String obraId) {
        try {
            return jdbcTemplate.queryForObject(
                    """
                    SELECT
                        id,
                        codigo_contrato,
                        cliente,
                        cidade,
                        uf,
                        rodovia
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
                    SELECT
                        id,
                        obra_id,
                        data_programacao,
                        cliente,
                        cidade,
                        uf,
                        rodovia,
                        km_inicial,
                        km_final
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

    private BigDecimal calcularSobra(
            BigDecimal quantidadeUsinada,
            BigDecimal quantidadeAplicada,
            BigDecimal quantidadeSobraInformada
    ) {
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

    private String diaSemanaPt(LocalDate data) {
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
            LocalDate dataProgramacao,
            String cliente,
            String cidade,
            String uf,
            String rodovia,
            String kmInicial,
            String kmFinal
    ) {
    }

    private record NumberAllocation(long value, String display) {
    }

    private record RdoExistente(
            String id,
            String obraId,
            LocalDate dataRdo,
            String previousRdoId,
            Long creationContextVersion,
            String clientMutationId,
            String creationOwnerId,
            String creationPayloadHash
    ) {
    }
}
