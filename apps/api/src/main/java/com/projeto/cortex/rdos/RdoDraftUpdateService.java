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
import java.util.List;
import java.util.UUID;

@Service
public class RdoDraftUpdateService {

    private final JdbcTemplate jdbcTemplate;
    private final RdoQueryService queryService;
    private final RdoMemoryPublisher memoryPublisher;
    private final RdoChangeAuditService auditService;
    private final RdoOperationalDetailService operationalDetailService;
    private final RdoAttachmentService attachmentService;
    private final RdoOperationalEventService operationalEventService;
    private final PrevisaoFinanceiraService previsaoFinanceiraService;

    public RdoDraftUpdateService(
            JdbcTemplate jdbcTemplate,
            RdoQueryService queryService,
            RdoMemoryPublisher memoryPublisher,
            RdoChangeAuditService auditService,
            RdoOperationalDetailService operationalDetailService,
            RdoAttachmentService attachmentService,
            RdoOperationalEventService operationalEventService,
            PrevisaoFinanceiraService previsaoFinanceiraService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.queryService = queryService;
        this.memoryPublisher = memoryPublisher;
        this.auditService = auditService;
        this.operationalDetailService = operationalDetailService;
        this.attachmentService = attachmentService;
        this.operationalEventService = operationalEventService;
        this.previsaoFinanceiraService = previsaoFinanceiraService;
    }

    @Transactional
    public RdoResponse atualizarRascunho(String rdoId, RdoCreateRequest request) {
        RdoChangeAuditService.RdoAuditSnapshot estadoAnterior =
                auditService.carregar(rdoId);

        validarRdoEditavel(estadoAnterior);

        if (request.obraId() == null || request.obraId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "obraId é obrigatório.");
        }

        if (request.dataRdo() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "dataRdo é obrigatório.");
        }

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

        jdbcTemplate.update(
                """
                UPDATE rdo
                SET
                    obra_id = ?,
                    programacao_id = ?,
                    numero_rdo = ?,
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
                    encarregado_obra = ?,
                    fiscalizacao_campo = ?,
                    versao_linha = versao_linha + 1
                WHERE id = ?
                  AND status = 'RASCUNHO'
                """,
                request.obraId(),
                programacao == null ? null : programacao.id(),
                request.numeroRdo(),
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
                nuloSeVazio(request.apontadorRdo()),
                nuloSeVazio(request.encarregadoObra()),
                nuloSeVazio(request.fiscalizacaoCampo()),
                rdoId
        );

        apagarItens(rdoId);

        inserirMaoObra(rdoId, request.maoObra());
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
                request.numeroRdo(),
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

    private void apagarItens(String rdoId) {
        jdbcTemplate.update("DELETE FROM rdo_controle_geometrico WHERE rdo_id = ?", rdoId);
        jdbcTemplate.update("DELETE FROM rdo_material WHERE rdo_id = ?", rdoId);
        jdbcTemplate.update("DELETE FROM rdo_equipamento WHERE rdo_id = ?", rdoId);
        jdbcTemplate.update("DELETE FROM rdo_mao_obra WHERE rdo_id = ?", rdoId);
    }

    private void inserirMaoObra(String rdoId, List<RdoCreateRequest.MaoObraItem> itens) {
        for (RdoCreateRequest.MaoObraItem item : listaSegura(itens)) {
            jdbcTemplate.update(
                    """
                    INSERT INTO rdo_mao_obra (
                        id, rdo_id, colaborador_id, nome_colaborador, cargo,
                        tipo_vinculo, quantidade, hora_inicio, hora_fim, observacoes
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID().toString(),
                    rdoId,
                    nuloSeVazio(item.colaboradorId()),
                    item.nomeColaborador(),
                    item.cargo(),
                    primeiroNaoVazio(item.tipoVinculo(), "CONTRATADO"),
                    valorOuUm(item.quantidade()),
                    item.horaInicio(),
                    item.horaFim(),
                    item.observacoes()
            );
        }
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
}
