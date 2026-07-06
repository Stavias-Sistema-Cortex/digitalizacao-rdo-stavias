package com.projeto.cortex.rdos;

import com.projeto.cortex.memory.CortexOperationalMemoryService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RdoOperationalDetailService {

    private static final String FONTE = "RDO_API";
    private static final BigDecimal MINUTOS_DIA_PADRAO =
            BigDecimal.valueOf(480);
    private static final Pattern CODIGO_SERVICO_PATTERN =
            Pattern.compile("^\\s*([0-9]+(?:\\.[0-9]+)*)\\s+-\\s+(.+?)\\s*$");

    private final JdbcTemplate jdbcTemplate;
    private final CortexOperationalMemoryService memoryService;

    public RdoOperationalDetailService(
            JdbcTemplate jdbcTemplate,
            CortexOperationalMemoryService memoryService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.memoryService = memoryService;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public RdoOperationalDetails substituirDetalhes(
            String rdoId,
            String obraId,
            String programacaoId,
            LocalDate dataRdo,
            String turnoRdo,
            List<RdoCreateRequest.ServicoExecutadoItem> servicosExecutados,
            List<RdoCreateRequest.AlocacaoColaboradorItem> alocacoesColaboradores
    ) {
        apagarDetalhes(rdoId);

        List<RdoResponse.ServicoExecutadoItem> servicos =
                inserirServicos(
                        rdoId,
                        obraId,
                        programacaoId,
                        dataRdo,
                        turnoRdo,
                        servicosExecutados
                );

        List<RdoResponse.AlocacaoColaboradorItem> alocacoes =
                inserirAlocacoes(
                        rdoId,
                        obraId,
                        programacaoId,
                        dataRdo,
                        turnoRdo,
                        alocacoesColaboradores
                );

        return new RdoOperationalDetails(servicos, alocacoes);
    }

    public List<RdoResponse.ServicoExecutadoItem> listarServicos(String rdoId) {
        return jdbcTemplate.query(
                """
                SELECT
                    id,
                    servico_nome,
                    item_contratual_id,
                    quantidade_executada,
                    unidade_medida,
                    trecho_inicial,
                    trecho_final,
                    localizacao,
                    turno,
                    status_validacao,
                    estado_receita,
                    receita_operacional_estimativa,
                    custo_realizado,
                    retrabalho,
                    producao_rejeitada,
                    observacoes
                FROM execucao_servico_rdo
                WHERE rdo_id = ?
                  AND cancelada = 0
                ORDER BY data_execucao, servico_nome, id
                """,
                (rs, rowNumber) -> new RdoResponse.ServicoExecutadoItem(
                        rs.getString("id"),
                        rs.getString("servico_nome"),
                        rs.getString("item_contratual_id"),
                        rs.getBigDecimal("quantidade_executada"),
                        rs.getString("unidade_medida"),
                        rs.getString("trecho_inicial"),
                        rs.getString("trecho_final"),
                        rs.getString("localizacao"),
                        rs.getString("turno"),
                        rs.getString("status_validacao"),
                        rs.getString("estado_receita"),
                        rs.getBigDecimal("receita_operacional_estimativa"),
                        rs.getBigDecimal("custo_realizado"),
                        rs.getBoolean("retrabalho"),
                        rs.getBoolean("producao_rejeitada"),
                        rs.getString("observacoes")
                ),
                rdoId
        );
    }

    public List<RdoResponse.AlocacaoColaboradorItem> listarAlocacoes(
            String rdoId
    ) {
        return jdbcTemplate.query(
                """
                SELECT
                    id,
                    colaborador_id,
                    equipe,
                    servico_nome,
                    hora_inicio,
                    hora_fim,
                    minutos,
                    percentual_dia,
                    turno,
                    funcao,
                    centro_custo,
                    tipo_alocacao,
                    fonte,
                    status,
                    custo_hora,
                    custo_total,
                    observacoes
                FROM alocacao_colaborador
                WHERE rdo_id = ?
                  AND status <> 'CANCELADA'
                ORDER BY data_alocacao, hora_inicio, colaborador_id, id
                """,
                (rs, rowNumber) -> new RdoResponse.AlocacaoColaboradorItem(
                        rs.getString("id"),
                        rs.getString("colaborador_id"),
                        rs.getString("equipe"),
                        rs.getString("servico_nome"),
                        toLocalTime(rs.getTime("hora_inicio")),
                        toLocalTime(rs.getTime("hora_fim")),
                        rs.getInt("minutos"),
                        rs.getBigDecimal("percentual_dia"),
                        rs.getString("turno"),
                        rs.getString("funcao"),
                        rs.getString("centro_custo"),
                        rs.getString("tipo_alocacao"),
                        rs.getString("fonte"),
                        rs.getString("status"),
                        rs.getBigDecimal("custo_hora"),
                        rs.getBigDecimal("custo_total"),
                        rs.getString("observacoes")
                ),
                rdoId
        );
    }

    private void apagarDetalhes(String rdoId) {
        jdbcTemplate.update(
                "DELETE FROM alocacao_colaborador WHERE rdo_id = ?",
                rdoId
        );
        jdbcTemplate.update(
                "DELETE FROM execucao_servico_rdo WHERE rdo_id = ?",
                rdoId
        );
    }

    private List<RdoResponse.ServicoExecutadoItem> inserirServicos(
            String rdoId,
            String obraId,
            String programacaoId,
            LocalDate dataRdo,
            String turnoRdo,
            List<RdoCreateRequest.ServicoExecutadoItem> itens
    ) {
        List<RdoResponse.ServicoExecutadoItem> response =
                new ArrayList<>();

        int index = 0;
        for (RdoCreateRequest.ServicoExecutadoItem item : listaSegura(itens)) {
            index++;

            if (item.servicoNome() == null || item.servicoNome().isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "servicoNome é obrigatório em serviços executados."
                );
            }

            if (item.quantidadeExecutada() == null
                    || item.quantidadeExecutada().compareTo(BigDecimal.ZERO) < 0) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "quantidadeExecutada deve ser maior ou igual a zero."
                );
            }

            ItemContratualDados itemContratual =
                    buscarItemContratualOpcional(
                            item.itemContratualId(),
                            obraId,
                            dataRdo
                    );

            String unidade =
                    unidadeServico(item.unidade(), itemContratual);
            BigDecimal receita =
                    calcularReceita(item.quantidadeExecutada(), itemContratual);
            String statusValidacao =
                    normalizarStatusValidacao(item.statusValidacao());
            String estadoReceita =
                    estadoReceita(statusValidacao, receita);
            boolean retrabalho =
                    Boolean.TRUE.equals(item.retrabalho());
            boolean producaoRejeitada =
                    Boolean.TRUE.equals(item.producaoRejeitada());
            BigDecimal custoRealizado =
                    dinheiro(item.custoRealizado());

            String id = UUID.randomUUID().toString();
            String chaveExecucao =
                    sha256(
                            String.join(
                                    "|",
                                    rdoId,
                                    String.valueOf(index),
                                    item.servicoNome(),
                                    nullToEmpty(item.itemContratualId()),
                                    item.quantidadeExecutada().toPlainString(),
                                    unidade,
                                    nullToEmpty(item.trechoInicial()),
                                    nullToEmpty(item.trechoFinal())
                            )
                    );

            jdbcTemplate.update(
                    """
                    INSERT INTO execucao_servico_rdo (
                        id,
                        rdo_id,
                        obra_id,
                        programacao_id,
                        servico_nome,
                        item_contratual_id,
                        quantidade_executada,
                        unidade_medida,
                        trecho_inicial,
                        trecho_final,
                        localizacao,
                        data_execucao,
                        turno,
                        status_validacao,
                        estado_receita,
                        receita_operacional_estimativa,
                        custo_realizado,
                        retrabalho,
                        producao_rejeitada,
                        fonte,
                        chave_execucao,
                        observacoes
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    id,
                    rdoId,
                    obraId,
                    programacaoId,
                    item.servicoNome().trim(),
                    itemContratual == null ? null : itemContratual.id(),
                    escala3(item.quantidadeExecutada()),
                    unidade,
                    nuloSeVazio(item.trechoInicial()),
                    nuloSeVazio(item.trechoFinal()),
                    nuloSeVazio(item.localizacao()),
                    dataRdo,
                    primeiroNaoVazio(item.turno(), turnoRdo),
                    statusValidacao,
                    estadoReceita,
                    receita,
                    custoRealizado,
                    retrabalho,
                    producaoRejeitada,
                    "RDO",
                    chaveExecucao,
                    item.observacoes()
            );

            registrarServicoNaMemoria(
                    id,
                    rdoId,
                    obraId,
                    programacaoId,
                    item.servicoNome(),
                    itemContratual,
                    statusValidacao,
                    receita,
                    custoRealizado
            );

            response.add(new RdoResponse.ServicoExecutadoItem(
                    id,
                    item.servicoNome().trim(),
                    itemContratual == null ? null : itemContratual.id(),
                    escala3(item.quantidadeExecutada()),
                    unidade,
                    nuloSeVazio(item.trechoInicial()),
                    nuloSeVazio(item.trechoFinal()),
                    nuloSeVazio(item.localizacao()),
                    primeiroNaoVazio(item.turno(), turnoRdo),
                    statusValidacao,
                    estadoReceita,
                    receita,
                    custoRealizado,
                    retrabalho,
                    producaoRejeitada,
                    item.observacoes()
            ));
        }

        return response;
    }

    private List<RdoResponse.AlocacaoColaboradorItem> inserirAlocacoes(
            String rdoId,
            String obraId,
            String programacaoId,
            LocalDate dataRdo,
            String turnoRdo,
            List<RdoCreateRequest.AlocacaoColaboradorItem> itens
    ) {
        List<RdoResponse.AlocacaoColaboradorItem> response =
                new ArrayList<>();
        Map<String, AcumuladorAlocacao> acumuladores =
                new LinkedHashMap<>();

        int index = 0;
        for (RdoCreateRequest.AlocacaoColaboradorItem item : listaSegura(itens)) {
            index++;

            ColaboradorDados colaborador =
                    buscarColaboradorAtivo(item.colaboradorId());
            IntervaloAlocacao intervalo =
                    intervalo(item.horaInicio(), item.horaFim(), item.percentualDia());
            BigDecimal percentual =
                    percentualDia(item.percentualDia(), intervalo.minutos());
            String tipoAlocacao =
                    normalizarTipoAlocacao(item.tipoAlocacao());
            String status =
                    normalizarStatusAlocacao(item.status());
            BigDecimal custoHora =
                    dinheiro4(item.custoHora());
            BigDecimal custoTotal =
                    custoTotal(custoHora, intervalo.minutos());

            validarSemSobreposicao(
                    rdoId,
                    colaborador.id(),
                    dataRdo,
                    intervalo
            );

            AcumuladorAlocacao acumulador =
                    acumuladores.computeIfAbsent(
                            colaborador.id(),
                            ignored -> acumuladorExistente(
                                    rdoId,
                                    colaborador.id(),
                                    dataRdo
                            )
                    );
            acumulador.adicionar(intervalo.minutos(), percentual);

            String id = UUID.randomUUID().toString();
            String chaveAlocacao =
                    sha256(
                            String.join(
                                    "|",
                                    rdoId,
                                    String.valueOf(index),
                                    colaborador.id(),
                                    dataRdo.toString(),
                                    nullToEmpty(intervalo.horaInicio()),
                                    nullToEmpty(intervalo.horaFim()),
                                    tipoAlocacao,
                                    nullToEmpty(item.servicoNome())
                            )
                    );

            jdbcTemplate.update(
                    """
                    INSERT INTO alocacao_colaborador (
                        id,
                        colaborador_id,
                        data_alocacao,
                        hora_inicio,
                        hora_fim,
                        minutos,
                        percentual_dia,
                        obra_id,
                        equipe,
                        servico_nome,
                        rdo_id,
                        programacao_id,
                        turno,
                        funcao,
                        centro_custo,
                        tipo_alocacao,
                        fonte,
                        status,
                        custo_hora,
                        custo_total,
                        observacoes,
                        chave_alocacao
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    id,
                    colaborador.id(),
                    dataRdo,
                    intervalo.horaInicio(),
                    intervalo.horaFim(),
                    intervalo.minutos(),
                    percentual,
                    obraId,
                    nuloSeVazio(item.equipe()),
                    nuloSeVazio(item.servicoNome()),
                    rdoId,
                    programacaoId,
                    primeiroNaoVazio(item.turno(), turnoRdo),
                    nuloSeVazio(item.funcao()),
                    nuloSeVazio(item.centroCusto()),
                    tipoAlocacao,
                    primeiroNaoVazio(item.fonte(), "RDO"),
                    status,
                    custoHora,
                    custoTotal,
                    item.observacoes(),
                    chaveAlocacao
            );

            registrarAlocacaoNaMemoria(
                    id,
                    colaborador,
                    obraId,
                    rdoId,
                    programacaoId,
                    item.equipe(),
                    item.servicoNome(),
                    item.funcao(),
                    tipoAlocacao,
                    status,
                    intervalo.minutos(),
                    custoTotal
            );

            response.add(new RdoResponse.AlocacaoColaboradorItem(
                    id,
                    colaborador.id(),
                    nuloSeVazio(item.equipe()),
                    nuloSeVazio(item.servicoNome()),
                    intervalo.horaInicio(),
                    intervalo.horaFim(),
                    intervalo.minutos(),
                    percentual,
                    primeiroNaoVazio(item.turno(), turnoRdo),
                    nuloSeVazio(item.funcao()),
                    nuloSeVazio(item.centroCusto()),
                    tipoAlocacao,
                    primeiroNaoVazio(item.fonte(), "RDO"),
                    status,
                    custoHora,
                    custoTotal,
                    item.observacoes()
            ));
        }

        return response;
    }

    private ItemContratualDados buscarItemContratualOpcional(
            String itemContratualId,
            String obraId,
            LocalDate dataExecucao
    ) {
        if (itemContratualId == null || itemContratualId.isBlank()) {
            return null;
        }

        List<ItemContratualDados> itens = jdbcTemplate.query(
                """
                SELECT
                    id,
                    obra_id,
                    codigo_item,
                    descricao,
                    unidade_medida,
                    preco_unitario,
                    vigencia_inicio,
                    vigencia_fim,
                    status
                FROM item_contratual
                WHERE id = ?
                  AND obra_id = ?
                """,
                (rs, rowNumber) -> new ItemContratualDados(
                        rs.getString("id"),
                        rs.getString("obra_id"),
                        rs.getString("codigo_item"),
                        rs.getString("descricao"),
                        rs.getString("unidade_medida"),
                        rs.getBigDecimal("preco_unitario"),
                        rs.getDate("vigencia_inicio") == null
                                ? null
                                : rs.getDate("vigencia_inicio").toLocalDate(),
                        rs.getDate("vigencia_fim") == null
                                ? null
                                : rs.getDate("vigencia_fim").toLocalDate(),
                        rs.getString("status")
                ),
                itemContratualId.trim(),
                obraId
        );

        if (itens.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Item contratual não encontrado para a obra."
            );
        }

        ItemContratualDados item = itens.getFirst();

        if (!"ATIVO".equals(item.status())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Item contratual informado não está ativo."
            );
        }

        if (item.vigenciaInicio() != null
                && dataExecucao.isBefore(item.vigenciaInicio())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Data do RDO está fora da vigência inicial do item contratual."
            );
        }

        if (item.vigenciaFim() != null
                && dataExecucao.isAfter(item.vigenciaFim())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Data do RDO está fora da vigência final do item contratual."
            );
        }

        return item;
    }

    private ColaboradorDados buscarColaboradorAtivo(String colaboradorId) {
        if (colaboradorId == null || colaboradorId.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "colaboradorId é obrigatório em alocações."
            );
        }

        List<ColaboradorDados> colaboradores = jdbcTemplate.query(
                """
                SELECT id, nome, ativo
                FROM colaborador
                WHERE id = ?
                  AND deletado_em IS NULL
                """,
                (rs, rowNumber) -> new ColaboradorDados(
                        rs.getString("id"),
                        rs.getString("nome"),
                        rs.getBoolean("ativo")
                ),
                colaboradorId.trim()
        );

        if (colaboradores.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Colaborador não encontrado."
            );
        }

        ColaboradorDados colaborador = colaboradores.getFirst();

        if (!colaborador.ativo()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Colaborador inativo não pode ser alocado."
            );
        }

        return colaborador;
    }

    private void validarSemSobreposicao(
            String rdoId,
            String colaboradorId,
            LocalDate dataRdo,
            IntervaloAlocacao intervalo
    ) {
        if (intervalo.horaInicio() == null || intervalo.horaFim() == null) {
            return;
        }

        Integer overlaps = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM alocacao_colaborador
                WHERE colaborador_id = ?
                  AND data_alocacao = ?
                  AND status <> 'CANCELADA'
                  AND (rdo_id IS NULL OR rdo_id <> ?)
                  AND hora_inicio IS NOT NULL
                  AND hora_fim IS NOT NULL
                  AND hora_inicio < ?
                  AND hora_fim > ?
                """,
                Integer.class,
                colaboradorId,
                dataRdo,
                rdoId,
                intervalo.horaFim(),
                intervalo.horaInicio()
        );

        if (overlaps != null && overlaps > 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Alocação sobreposta para o colaborador na mesma data."
            );
        }
    }

    private AcumuladorAlocacao acumuladorExistente(
            String rdoId,
            String colaboradorId,
            LocalDate dataRdo
    ) {
        return jdbcTemplate.queryForObject(
                """
                SELECT
                    COALESCE(SUM(minutos), 0) AS minutos,
                    COALESCE(SUM(percentual_dia), 0) AS percentual
                FROM alocacao_colaborador
                WHERE colaborador_id = ?
                  AND data_alocacao = ?
                  AND status <> 'CANCELADA'
                  AND (rdo_id IS NULL OR rdo_id <> ?)
                """,
                (rs, rowNumber) -> new AcumuladorAlocacao(
                        rs.getInt("minutos"),
                        rs.getBigDecimal("percentual")
                ),
                colaboradorId,
                dataRdo,
                rdoId
        );
    }

    private IntervaloAlocacao intervalo(
            LocalTime horaInicio,
            LocalTime horaFim,
            BigDecimal percentualDia
    ) {
        if (horaInicio != null || horaFim != null) {
            if (horaInicio == null || horaFim == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "horaInicio e horaFim devem ser informados juntos."
                );
            }

            long minutos = ChronoUnit.MINUTES.between(horaInicio, horaFim);

            if (minutos <= 0) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "horaFim deve ser maior que horaInicio."
                );
            }

            if (minutos > 1440) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Uma alocação não pode exceder 24 horas."
                );
            }

            return new IntervaloAlocacao(horaInicio, horaFim, (int) minutos);
        }

        if (percentualDia == null
                || percentualDia.compareTo(BigDecimal.ZERO) <= 0
                || percentualDia.compareTo(BigDecimal.ONE) > 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Informe horas ou percentualDia entre 0 e 1 para a alocação."
            );
        }

        int minutos = percentualDia
                .multiply(MINUTOS_DIA_PADRAO)
                .setScale(0, RoundingMode.HALF_UP)
                .intValue();

        return new IntervaloAlocacao(null, null, minutos);
    }

    private BigDecimal percentualDia(BigDecimal percentual, int minutos) {
        if (percentual != null) {
            return percentual.setScale(6, RoundingMode.HALF_UP);
        }

        return BigDecimal.valueOf(minutos)
                .divide(MINUTOS_DIA_PADRAO, 6, RoundingMode.HALF_UP);
    }

    private String unidadeServico(
            String unidadeInformada,
            ItemContratualDados itemContratual
    ) {
        if (itemContratual == null) {
            if (unidadeInformada == null || unidadeInformada.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "unidade é obrigatória quando não há item contratual."
                );
            }
            return unidadeInformada.trim();
        }

        if (unidadeInformada != null
                && !unidadeInformada.isBlank()
                && !unidadeInformada.trim().equalsIgnoreCase(
                        itemContratual.unidadeMedida()
                )) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unidade executada incompatível com o item contratual."
            );
        }

        return itemContratual.unidadeMedida();
    }

    private BigDecimal calcularReceita(
            BigDecimal quantidade,
            ItemContratualDados itemContratual
    ) {
        if (itemContratual == null) {
            return null;
        }

        return dinheiro(
                quantidade.multiply(itemContratual.precoUnitario())
        );
    }

    private String estadoReceita(
            String statusValidacao,
            BigDecimal receita
    ) {
        if (receita != null) {
            return "RECEITA_ESTIMADA";
        }

        if ("VALIDADA".equals(statusValidacao)) {
            return "PRODUCAO_VALIDADA";
        }

        return "PRODUCAO_REGISTRADA";
    }

    private String normalizarStatusValidacao(String value) {
        String status = primeiroNaoVazio(value, "REGISTRADA")
                .trim()
                .toUpperCase(Locale.ROOT);

        if (!List.of("REGISTRADA", "VALIDADA", "REJEITADA", "CANCELADA")
                .contains(status)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "statusValidacao inválido para serviço executado."
            );
        }

        return status;
    }

    private String normalizarTipoAlocacao(String value) {
        String tipo = primeiroNaoVazio(value, "TRABALHO")
                .trim()
                .toUpperCase(Locale.ROOT);

        if (!List.of(
                "TRABALHO",
                "DESLOCAMENTO",
                "TREINAMENTO",
                "MANUTENCAO",
                "APOIO",
                "ADMINISTRATIVO",
                "AFASTAMENTO",
                "OUTRO"
        ).contains(tipo)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "tipoAlocacao inválido."
            );
        }

        return tipo;
    }

    private String normalizarStatusAlocacao(String value) {
        String status = primeiroNaoVazio(value, "REGISTRADA")
                .trim()
                .toUpperCase(Locale.ROOT);

        if (!List.of("REGISTRADA", "VALIDADA", "CONFLITO", "CANCELADA")
                .contains(status)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "status inválido para alocação."
            );
        }

        return status;
    }

    private BigDecimal custoTotal(BigDecimal custoHora, int minutos) {
        if (custoHora == null) {
            return null;
        }

        return dinheiro(
                custoHora.multiply(BigDecimal.valueOf(minutos))
                        .divide(BigDecimal.valueOf(60), 6, RoundingMode.HALF_UP)
        );
    }

    private void registrarServicoNaMemoria(
            String execucaoId,
            String rdoId,
            String obraId,
            String programacaoId,
            String servicoNome,
            ItemContratualDados itemContratual,
            String statusValidacao,
            BigDecimal receita,
            BigDecimal custo
    ) {
        ServicoCatalogado servico = servicoCatalogado(servicoNome);

        memoryService.registrarObjeto(
                "EXECUCAO_SERVICO_RDO",
                execucaoId,
                execucaoId,
                servico.displayName(),
                statusValidacao,
                FONTE,
                "execucao_servico_rdo",
                execucaoServicoMetadata(rdoId, obraId, programacaoId, servico)
        );

        memoryService.registrarRelacaoAtiva(
                "EXECUCAO_SERVICO_RDO",
                execucaoId,
                "RDO",
                rdoId,
                "REGISTRADA_NO",
                FONTE,
                "Execução de serviço registrada no RDO."
        );
        memoryService.registrarRelacaoAtiva(
                "EXECUCAO_SERVICO_RDO",
                execucaoId,
                "OBRA",
                obraId,
                "OCORRE_EM",
                FONTE,
                "Execução de serviço vinculada à obra."
        );

        registrarServicoCatalogado(servico);
        memoryService.registrarRelacaoAtiva(
                "EXECUCAO_SERVICO_RDO",
                execucaoId,
                "SERVICO",
                servico.id(),
                "EXECUTA_SERVICO",
                FONTE,
                "Execução do RDO realiza tipo de serviço."
        );
        memoryService.registrarRelacaoAtiva(
                "RDO",
                rdoId,
                "SERVICO",
                servico.id(),
                "REGISTRA_SERVICO",
                FONTE,
                "RDO registra tipo de serviço executado."
        );
        memoryService.registrarRelacaoAtiva(
                "OBRA",
                obraId,
                "SERVICO",
                servico.id(),
                "TEM_SERVICO_EXECUTADO",
                FONTE,
                "Obra possui execução histórica deste tipo de serviço."
        );
        memoryService.registrarRelacaoAtiva(
                "EXECUCAO_SERVICO_RDO",
                execucaoId,
                "PROGRAMACAO_OPERACIONAL",
                programacaoId,
                "GERADA_A_PARTIR_DE",
                FONTE,
                "Execução de serviço associada à programação operacional."
        );

        if (itemContratual != null) {
            memoryService.registrarObjeto(
                    "ITEM_CONTRATUAL",
                    itemContratual.id(),
                    itemContratual.codigoItem(),
                    itemContratual.descricao(),
                    itemContratual.status(),
                    FONTE
            );
            memoryService.registrarRelacaoAtiva(
                    "EXECUCAO_SERVICO_RDO",
                    execucaoId,
                    "ITEM_CONTRATUAL",
                    itemContratual.id(),
                    "VALORA_POR",
                    FONTE,
                    "Execução valorada pelo item contratual."
            );
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("rdoId", rdoId);
        payload.put("obraId", obraId);
        payload.put("servico", servico.displayName());
        payload.put("servicoId", servico.id());
        payload.put("servicoCodigo", servico.codigo());
        payload.put("servicoNome", servico.nome());
        payload.put("statusValidacao", statusValidacao);
        payload.put("receitaOperacionalEstimativa", receita);
        payload.put("custoRealizado", custo);

        memoryService.registrarEvento(
                "EXECUCAO_SERVICO_RDO",
                execucaoId,
                "EXECUCAO_SERVICO_REGISTRADA",
                FONTE,
                payload
        );
    }

    private void registrarAlocacaoNaMemoria(
            String alocacaoId,
            ColaboradorDados colaborador,
            String obraId,
            String rdoId,
            String programacaoId,
            String equipe,
            String servicoNome,
            String funcao,
            String tipoAlocacao,
            String status,
            int minutos,
            BigDecimal custoTotal
    ) {
        memoryService.registrarObjeto(
                "COLABORADOR",
                colaborador.id(),
                colaborador.id(),
                colaborador.nome(),
                colaborador.ativo() ? "ATIVO" : "INATIVO",
                FONTE
        );
        memoryService.registrarObjeto(
                "ALOCACAO_COLABORADOR",
                alocacaoId,
                alocacaoId,
                "Alocação de " + colaborador.nome(),
                status,
                FONTE
        );
        memoryService.registrarRelacaoAtiva(
                "COLABORADOR",
                colaborador.id(),
                "ALOCACAO_COLABORADOR",
                alocacaoId,
                "POSSUI_ALOCACAO",
                FONTE,
                "Colaborador possui alocação temporal."
        );
        memoryService.registrarRelacaoAtiva(
                "ALOCACAO_COLABORADOR",
                alocacaoId,
                "OBRA",
                obraId,
                "OCORRE_EM",
                FONTE,
                "Alocação ocorre em uma obra."
        );
        memoryService.registrarRelacaoAtiva(
                "ALOCACAO_COLABORADOR",
                alocacaoId,
                "RDO",
                rdoId,
                "REGISTRADA_NO",
                FONTE,
                "Alocação registrada no RDO."
        );
        memoryService.registrarRelacaoAtiva(
                "ALOCACAO_COLABORADOR",
                alocacaoId,
                "PROGRAMACAO_OPERACIONAL",
                programacaoId,
                "GERADA_A_PARTIR_DE",
                FONTE,
                "Alocação relacionada à programação operacional."
        );

        if (servicoNome != null && !servicoNome.isBlank()) {
            ServicoCatalogado servico = servicoCatalogado(servicoNome);
            registrarServicoCatalogado(servico);
            memoryService.registrarRelacaoAtiva(
                    "ALOCACAO_COLABORADOR",
                    alocacaoId,
                    "SERVICO",
                    servico.id(),
                    "EXECUTA",
                    FONTE,
                    "Alocação executa serviço."
            );
            memoryService.registrarRelacaoAtiva(
                    "COLABORADOR",
                    colaborador.id(),
                    "SERVICO",
                    servico.id(),
                    "ATUOU_EM_SERVICO",
                    FONTE,
                    "Colaborador atuou historicamente neste tipo de serviço."
            );

            if (ehResponsavelOperacional(funcao)) {
                memoryService.registrarRelacaoAtiva(
                        "COLABORADOR",
                        colaborador.id(),
                        "SERVICO",
                        servico.id(),
                        "RESPONSAVEL_POR",
                        FONTE,
                        "Colaborador identificado como encarregado ou responsável pelo serviço."
                );
                memoryService.registrarRelacaoAtiva(
                        "ALOCACAO_COLABORADOR",
                        alocacaoId,
                        "SERVICO",
                        servico.id(),
                        "RESPONSAVEL_PELO_SERVICO",
                        FONTE,
                        "Alocação marca encarregado ou responsável pelo serviço."
                );
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("colaboradorId", colaborador.id());
        payload.put("obraId", obraId);
        payload.put("rdoId", rdoId);
        payload.put("programacaoId", programacaoId);
        payload.put("equipe", equipe);
        payload.put("servico", servicoNome);
        payload.put("funcao", funcao);
        payload.put("tipoAlocacao", tipoAlocacao);
        payload.put("status", status);
        payload.put("minutos", minutos);
        payload.put("custoTotal", custoTotal);

        memoryService.registrarEvento(
                "ALOCACAO_COLABORADOR",
                alocacaoId,
                "ALOCACAO_COLABORADOR_CRIADA",
                FONTE,
                payload
        );
    }

    private Map<String, Object> execucaoServicoMetadata(
            String rdoId,
            String obraId,
            String programacaoId,
            ServicoCatalogado servico
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("rdoId", rdoId);
        metadata.put("obraId", obraId);
        metadata.put("programacaoId", programacaoId);
        metadata.put("servicoId", servico.id());
        metadata.put("servicoCodigo", servico.codigo());
        metadata.put("servicoNome", servico.nome());
        metadata.put("servicoDisplay", servico.displayName());
        metadata.put("servicoCanonico", servico.canonicalName());
        return metadata;
    }

    private void registrarServicoCatalogado(
            ServicoCatalogado servico
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("schemaVersion", 1);
        metadata.put("codigoServico", servico.codigo());
        metadata.put("nomeServico", servico.nome());
        metadata.put("nomeCanonico", servico.canonicalName());
        metadata.put("valorOriginal", servico.rawName());

        memoryService.registrarObjeto(
                "SERVICO",
                servico.id(),
                servico.codigo(),
                servico.displayName(),
                "ATIVO",
                FONTE,
                "catalogo_tipo_servico",
                metadata
        );

        Map<String, Object> evidencias = new LinkedHashMap<>();
        evidencias.put("codigo_servico", servico.codigo());
        evidencias.put("nome_servico", servico.nome());
        evidencias.put("nome_canonico", servico.canonicalName());
        evidencias.put("display_name", servico.displayName());
        memoryService.registrarEvidencias(
                "SERVICO",
                servico.id(),
                FONTE,
                evidencias
        );
    }

    private ServicoCatalogado servicoCatalogado(
            String servicoNome
    ) {
        String rawName = primeiroNaoVazio(servicoNome, "Serviço sem nome");
        Matcher matcher = CODIGO_SERVICO_PATTERN.matcher(rawName);

        String codigo = null;
        String nome = rawName;
        if (matcher.matches()) {
            codigo = matcher.group(1).trim();
            nome = matcher.group(2).trim();
        }

        String canonicalName = canonicalText(nome);
        String stableKey = codigo == null
                ? "SERVICO|" + canonicalName
                : "SERVICO|" + codigo + "|" + canonicalName;
        String id = stableUuid(stableKey);
        String displayName = codigo == null
                ? nome
                : codigo + " - " + nome;

        return new ServicoCatalogado(
                id,
                codigo,
                nome,
                displayName,
                canonicalName,
                rawName
        );
    }

    private boolean ehResponsavelOperacional(String funcao) {
        String canonical = canonicalText(funcao);
        return canonical.contains("encarregado")
                || canonical.contains("responsavel")
                || canonical.contains("supervisor")
                || canonical.contains("lider");
    }

    private String canonicalText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        return value.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private BigDecimal escala3(BigDecimal value) {
        return value == null ? null : value.setScale(3, RoundingMode.HALF_UP);
    }

    private BigDecimal dinheiro(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal dinheiro4(BigDecimal value) {
        return value == null ? null : value.setScale(4, RoundingMode.HALF_UP);
    }

    private String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Não foi possível gerar hash operacional.",
                    exception
            );
        }
    }

    private String stableUuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    private <T> List<T> listaSegura(List<T> lista) {
        return lista == null ? List.of() : lista;
    }

    private String primeiroNaoVazio(String... valores) {
        for (String valor : valores) {
            if (valor != null && !valor.isBlank()) {
                return valor.trim();
            }
        }
        return null;
    }

    private String nuloSeVazio(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String nullToEmpty(LocalTime value) {
        return value == null ? "" : value.toString();
    }

    private LocalTime toLocalTime(java.sql.Time time) {
        return time == null ? null : time.toLocalTime();
    }

    public record RdoOperationalDetails(
            List<RdoResponse.ServicoExecutadoItem> servicosExecutados,
            List<RdoResponse.AlocacaoColaboradorItem> alocacoesColaboradores
    ) {
    }

    private record ItemContratualDados(
            String id,
            String obraId,
            String codigoItem,
            String descricao,
            String unidadeMedida,
            BigDecimal precoUnitario,
            LocalDate vigenciaInicio,
            LocalDate vigenciaFim,
            String status
    ) {
    }

    private record ColaboradorDados(
            String id,
            String nome,
            boolean ativo
    ) {
    }

    private record ServicoCatalogado(
            String id,
            String codigo,
            String nome,
            String displayName,
            String canonicalName,
            String rawName
    ) {
    }

    private record IntervaloAlocacao(
            LocalTime horaInicio,
            LocalTime horaFim,
            int minutos
    ) {
    }

    private static final class AcumuladorAlocacao {
        private int minutos;
        private BigDecimal percentual;

        private AcumuladorAlocacao(int minutos, BigDecimal percentual) {
            this.minutos = minutos;
            this.percentual = percentual == null ? BigDecimal.ZERO : percentual;
        }

        private void adicionar(int novosMinutos, BigDecimal novoPercentual) {
            this.minutos += novosMinutos;
            this.percentual = this.percentual.add(novoPercentual);

            if (this.minutos > 1440) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Total de horas do colaborador excede 24 horas no dia."
                );
            }

            if (this.percentual.compareTo(BigDecimal.ONE) > 0) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Percentual total do colaborador excede 100% no dia."
                );
            }
        }
    }
}
