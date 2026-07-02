package com.projeto.cortex.rdos;

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
import java.util.List;
import java.util.UUID;

@Service
public class RdoService {

    private final JdbcTemplate jdbcTemplate;
    private final RdoMemoryPublisher memoryPublisher;
    private final RdoOperationalDetailService operationalDetailService;
    private final PrevisaoFinanceiraService previsaoFinanceiraService;

    public RdoService(
            JdbcTemplate jdbcTemplate,
            RdoMemoryPublisher memoryPublisher,
            RdoOperationalDetailService operationalDetailService,
            PrevisaoFinanceiraService previsaoFinanceiraService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.memoryPublisher = memoryPublisher;
        this.operationalDetailService = operationalDetailService;
        this.previsaoFinanceiraService = previsaoFinanceiraService;
    }

    @Transactional
    public RdoResponse criarRascunho(RdoCreateRequest request) {
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

        String rdoId = primeiroNaoVazio(request.id(), UUID.randomUUID().toString());

        if (rdoExiste(rdoId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Já existe um RDO com este id: " + rdoId
            );
        }
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
                    data_rdo,
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
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                rdoId,
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
                status,
                request.observacoes(),
                nuloSeVazio(request.preenchidoPor()),
                nuloSeVazio(request.apontadorRdo()),
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

        memoryPublisher.registrarRdoCriado(
                rdoId,
                request.obraId(),
                programacao == null ? null : programacao.id(),
                request.numeroRdo(),
                status
        );

        RdoOperationalDetailService.RdoOperationalDetails detalhes =
                operationalDetailService.substituirDetalhes(
                        rdoId,
                        request.obraId(),
                        programacao == null ? null : programacao.id(),
                        request.dataRdo(),
                        request.turno(),
                        request.servicosExecutados(),
                        request.alocacoesColaboradores()
                );

        previsaoFinanceiraService.recalcularAposMudancaRdo(
                request.obraId(),
                request.dataRdo(),
                null
        );

        return new RdoResponse(
                rdoId,
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
                status,
                request.observacoes(),
                nuloSeVazio(request.preenchidoPor()),
                nuloSeVazio(request.apontadorRdo()),
                nuloSeVazio(request.encarregadoObra()),
                nuloSeVazio(request.fiscalizacaoCampo()),
                maoObra,
                equipamentos,
                materiais,
                controles,
                detalhes.servicosExecutados(),
                detalhes.alocacoesColaboradores()
        );
    }

    private List<RdoResponse.MaoObraItem> inserirMaoObra(
            String rdoId,
            List<RdoCreateRequest.MaoObraItem> itens
    ) {
        List<RdoResponse.MaoObraItem> response = new ArrayList<>();

        for (RdoCreateRequest.MaoObraItem item : listaSegura(itens)) {
            String id = UUID.randomUUID().toString();
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
                        observacoes
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                    item.observacoes()
            );

            response.add(new RdoResponse.MaoObraItem(
                    id,
                    colaboradorId,
                    item.nomeColaborador(),
                    item.cargo(),
                    tipoVinculo,
                    quantidade
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
                    quantidade
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


    private boolean rdoExiste(String rdoId) {
        Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rdo WHERE id = ?",
                Integer.class,
                rdoId
        );

        return total != null && total > 0;
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
}
