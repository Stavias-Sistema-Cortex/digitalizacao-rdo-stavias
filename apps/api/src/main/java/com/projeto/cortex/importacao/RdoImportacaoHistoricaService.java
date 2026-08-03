package com.projeto.cortex.importacao;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.financeiro.PrevisaoFinanceiraService;
import com.projeto.cortex.rdos.RdoHistoricalImportServiceCommand;
import com.projeto.cortex.rdos.RdoMemoryPublisher;
import com.projeto.cortex.rdos.RdoOperationalDetailService;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class RdoImportacaoHistoricaService {

    private static final String VERSAO_MAPEAMENTO = "RDO_IMPORT_V1";
    private static final DateTimeFormatter DATE_BR =
            DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RdoMemoryPublisher rdoMemoryPublisher;
    private final RdoOperationalDetailService operationalDetailService;
    private final PrevisaoFinanceiraService previsaoFinanceiraService;

    public RdoImportacaoHistoricaService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            RdoMemoryPublisher rdoMemoryPublisher,
            RdoOperationalDetailService operationalDetailService,
            PrevisaoFinanceiraService previsaoFinanceiraService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.rdoMemoryPublisher = rdoMemoryPublisher;
        this.operationalDetailService = operationalDetailService;
        this.previsaoFinanceiraService = previsaoFinanceiraService;
    }

    @Transactional
    public RdoImportacaoResponse analisar(
            MultipartFile file,
            String usuarioId
    ) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Selecione uma planilha para importação."
            );
        }

        try {
            byte[] bytes = file.getBytes();
            String hash = sha256(bytes);
            String importacaoId = UUID.randomUUID().toString();
            List<SheetRows> sheets =
                    readSheets(file.getOriginalFilename(), bytes);

            jdbcTemplate.update(
                    """
                    INSERT INTO importacao_rdo (
                        id,
                        nome_arquivo,
                        hash_arquivo,
                        tamanho_bytes,
                        content_type,
                        layout_detectado,
                        status,
                        estrategia_duplicidade,
                        versao_mapeamento,
                        usuario_id,
                        arquivo_bytes
                    ) VALUES (?, ?, ?, ?, ?, ?, 'ANALISADA', 'IGNORAR_DUPLICADO', ?, ?, ?)
                    """,
                    importacaoId,
                    filename(file),
                    hash,
                    bytes.length,
                    file.getContentType(),
                    sheets.isEmpty() ? "VAZIO" : "RDO_TABULAR",
                    VERSAO_MAPEAMENTO,
                    usuarioId,
                    bytes
            );

            int processed = 0;
            int rejected = 0;

            for (SheetRows sheet : sheets) {
                for (Map.Entry<String, String> mapping : sheet.mapping().entrySet()) {
                    jdbcTemplate.update(
                            """
                            INSERT INTO importacao_rdo_mapeamento (
                                id,
                                importacao_id,
                                versao_mapeamento,
                                layout_detectado,
                                coluna_origem,
                                campo_destino,
                                obrigatorio
                            ) VALUES (?, ?, ?, ?, ?, ?, ?)
                            """,
                            UUID.randomUUID().toString(),
                            importacaoId,
                            VERSAO_MAPEAMENTO,
                            "RDO_TABULAR",
                            mapping.getKey(),
                            mapping.getValue(),
                            isRequired(mapping.getValue())
                    );
                }

                for (RawRow row : sheet.rows()) {
                    processed++;
                    LineValidation validation =
                            validateLine(sheet.name(), row);

                    if (!validation.errors().isEmpty()) {
                        rejected++;
                    }

                    inserirLinha(
                            importacaoId,
                            sheet.name(),
                            row,
                            validation
                    );
                }
            }

            String status = rejected == 0 ? "VALIDADA" : "AGUARDANDO_CORRECAO";
            jdbcTemplate.update(
                    """
                    UPDATE importacao_rdo
                    SET
                        quantidade_processada = ?,
                        quantidade_rejeitada = ?,
                        status = ?
                    WHERE id = ?
                    """,
                    processed,
                    rejected,
                    status,
                    importacaoId
            );

            return buscar(importacaoId, usuarioId, false);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Falha ao analisar a planilha de RDO: " + exception.getMessage(),
                    exception
            );
        }
    }

    public RdoImportacaoResponse buscar(
            String importacaoId,
            String usuarioAutenticadoId,
            boolean admin
    ) {
        ImportacaoCabecalho cabecalho =
                buscarCabecalho(importacaoId);
        validarAcessoImportacao(cabecalho, usuarioAutenticadoId, admin);

        List<RdoImportacaoLinhaResponse> linhas =
                jdbcTemplate.query(
                        """
                        SELECT
                            id,
                            aba,
                            numero_linha,
                            status,
                            obra_id,
                            numero_rdo,
                            data_rdo,
                            rdo_id,
                            payload_original_json,
                            payload_normalizado_json
                        FROM importacao_rdo_linha
                        WHERE importacao_id = ?
                        ORDER BY aba, numero_linha
                        LIMIT 200
                        """,
                        (rs, rowNumber) -> new RdoImportacaoLinhaResponse(
                                rs.getString("id"),
                                rs.getString("aba"),
                                rs.getInt("numero_linha"),
                                rs.getString("status"),
                                rs.getString("obra_id"),
                                rs.getString("numero_rdo"),
                                rs.getDate("data_rdo") == null
                                        ? null
                                        : rs.getDate("data_rdo").toLocalDate(),
                                rs.getString("rdo_id"),
                                parseJson(rs.getString("payload_original_json")),
                                parseJson(rs.getString("payload_normalizado_json")),
                                buscarErros(rs.getString("id"))
                        ),
                        importacaoId
                );

        return new RdoImportacaoResponse(
                cabecalho.id(),
                cabecalho.nomeArquivo(),
                cabecalho.hashArquivo(),
                cabecalho.tamanhoBytes(),
                cabecalho.layoutDetectado(),
                cabecalho.status(),
                cabecalho.estrategiaDuplicidade(),
                cabecalho.versaoMapeamento(),
                cabecalho.quantidadeProcessada(),
                cabecalho.quantidadeImportada(),
                cabecalho.quantidadeRejeitada(),
                cabecalho.quantidadeDuplicada(),
                cabecalho.criadoEm(),
                cabecalho.confirmadoEm(),
                linhas
        );
    }

    @Transactional
    public RdoImportacaoResponse confirmar(
            String importacaoId,
            RdoImportacaoConfirmRequest request,
            boolean admin
    ) {
        ImportacaoCabecalho cabecalho =
                buscarCabecalho(importacaoId);
        String estrategia =
                normalizarEstrategia(
                        request == null ? null : request.estrategiaDuplicidade()
                );
        boolean dryRun = request != null && request.dryRun();
        String usuarioId =
                request == null ? null : request.usuarioId();
        validarAcessoImportacao(cabecalho, usuarioId, admin);

        List<ImportLine> linhas =
                linhasValidas(importacaoId);

        int imported = 0;
        int duplicated = 0;
        int rejected = 0;

        for (ImportLine linha : linhas) {
            JsonNode payload = linha.payloadNormalizado();
            String duplicateRdoId =
                    buscarRdoDuplicado(
                            linha.obraId(),
                            linha.numeroRdo(),
                            linha.dataRdo()
                    );

            if (duplicateRdoId != null) {
                ResultadoDuplicidade resultado =
                        tratarDuplicidade(
                                estrategia,
                                dryRun,
                                cabecalho,
                                linha,
                                duplicateRdoId,
                                usuarioId
                        );
                imported += resultado.imported();
                duplicated += resultado.duplicated();
                rejected += resultado.rejected();
                continue;
            }

            if (!dryRun) {
                String rdoId =
                        criarRdoImportado(
                                cabecalho,
                                linha,
                                payload,
                                usuarioId
                        );
                marcarLinhaImportada(linha.id(), rdoId, "IMPORTADA");
                previsaoFinanceiraService.recalcularAposMudancaRdo(
                        linha.obraId(),
                        linha.dataRdo(),
                        null
                );
            }
            imported++;
        }

        String status =
                dryRun
                        ? "SIMULADA"
                        : rejected == 0
                                ? "CONFIRMADA"
                                : "CONFIRMADA_COM_ERROS";

        jdbcTemplate.update(
                """
                UPDATE importacao_rdo
                SET
                    estrategia_duplicidade = ?,
                    status = ?,
                    quantidade_importada = ?,
                    quantidade_duplicada = ?,
                    quantidade_rejeitada = quantidade_rejeitada + ?,
                    confirmado_em = CASE WHEN ? = 1 THEN confirmado_em ELSE CURRENT_TIMESTAMP(6) END
                WHERE id = ?
                """,
                estrategia,
                status,
                imported,
                duplicated,
                rejected,
                dryRun ? 1 : 0,
                importacaoId
        );

        return buscar(importacaoId, usuarioId, admin);
    }

    private ResultadoDuplicidade tratarDuplicidade(
            String estrategia,
            boolean dryRun,
            ImportacaoCabecalho cabecalho,
            ImportLine linha,
            String duplicateRdoId,
            String usuarioId
    ) {
        if ("REJEITAR".equals(estrategia)) {
            if (!dryRun) {
                registrarErro(
                        cabecalho.id(),
                        linha.id(),
                        linha.aba(),
                        linha.numeroLinha(),
                        "duplicidade",
                        duplicateRdoId,
                        "RDO não importado anteriormente",
                        "RDO duplicado para obra, número e data.",
                        "Escolha IGNORAR_DUPLICADO, ATUALIZAR_EXISTENTE ou CRIAR_NOVA_VERSAO.",
                        "ERRO"
                );
                marcarLinhaStatus(linha.id(), "REJEITADA");
            }
            return new ResultadoDuplicidade(0, 0, 1);
        }

        if ("IGNORAR_DUPLICADO".equals(estrategia)) {
            if (!dryRun) {
                marcarLinhaImportada(linha.id(), duplicateRdoId, "DUPLICADA");
            }
            return new ResultadoDuplicidade(0, 1, 0);
        }

        if ("ATUALIZAR_EXISTENTE".equals(estrategia)) {
            if (!dryRun) {
                atualizarRdoExistente(
                        cabecalho,
                        linha,
                        duplicateRdoId,
                        usuarioId
                );
                marcarLinhaImportada(linha.id(), duplicateRdoId, "ATUALIZADA");
            }
            return new ResultadoDuplicidade(1, 0, 0);
        }

        if (!dryRun) {
            String rdoId =
                    criarRdoImportado(
                            cabecalho,
                            linha,
                            linha.payloadNormalizado(),
                            usuarioId
                    );
            marcarLinhaImportada(linha.id(), rdoId, "IMPORTADA");
        }
        return new ResultadoDuplicidade(1, 0, 0);
    }

    private String criarRdoImportado(
            ImportacaoCabecalho cabecalho,
            ImportLine linha,
            JsonNode payload,
            String usuarioId
    ) {
        String rdoId = UUID.randomUUID().toString();
        String programacaoId =
                buscarProgramacao(linha.obraId(), linha.dataRdo());
        String turno = text(payload, "turno");

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
                    fonte_criacao,
                    importacao_rdo_id,
                    fonte_arquivo,
                    aba_origem,
                    linha_origem,
                    data_original,
                    data_importacao,
                    usuario_importacao,
                    preenchido_por,
                    apontador_rdo,
                    encarregado_obra,
                    fiscalizacao_campo,
                    versao_mapeamento,
                    chave_negocio_importacao,
                    observacoes
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ENVIADO', 'IMPORTACAO_HISTORICA', ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6), ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                rdoId,
                linha.obraId(),
                programacaoId,
                linha.numeroRdo(),
                linha.dataRdo(),
                diaSemanaPt(linha.dataRdo()),
                text(payload, "cliente"),
                text(payload, "contrato"),
                text(payload, "rodovia"),
                text(payload, "cidade"),
                text(payload, "uf"),
                text(payload, "kmInicialProgramado"),
                text(payload, "kmFinalProgramado"),
                text(payload, "kmInicialInterditado"),
                text(payload, "kmFinalInterditado"),
                turno,
                parseTime(text(payload, "horaInicio")),
                parseTime(text(payload, "horaFim")),
                text(payload, "condicaoManha"),
                text(payload, "condicaoTarde"),
                text(payload, "condicaoNoite"),
                decimal(payload, "pluviometriaMm"),
                cabecalho.id(),
                cabecalho.nomeArquivo(),
                linha.aba(),
                linha.numeroLinha(),
                linha.dataRdo(),
                usuarioId,
                text(payload, "preenchidoPor"),
                text(payload, "apontadorRdo"),
                text(payload, "encarregadoObra"),
                text(payload, "fiscalizacaoCampo"),
                cabecalho.versaoMapeamento(),
                linha.chaveNegocio(),
                text(payload, "observacoes")
        );

        rdoMemoryPublisher.registrarRdoImportado(
                rdoId,
                linha.obraId(),
                programacaoId,
                linha.numeroRdo(),
                cabecalho.id(),
                cabecalho.nomeArquivo(),
                linha.aba(),
                linha.numeroLinha(),
                "ENVIADO"
        );

        inserirServicoImportado(
                rdoId,
                linha.obraId(),
                cabecalho.id(),
                cabecalho.nomeArquivo(),
                linha.aba(),
                linha.numeroLinha(),
                turno,
                payload
        );
        inserirColecoesImportadas(rdoId, payload);

        return rdoId;
    }

    private void atualizarRdoExistente(
            ImportacaoCabecalho cabecalho,
            ImportLine linha,
            String rdoId,
            String usuarioId
    ) {
        String programacaoId =
                buscarProgramacao(linha.obraId(), linha.dataRdo());

        jdbcTemplate.update(
                """
                UPDATE rdo
                SET
                    fonte_criacao = 'IMPORTACAO_HISTORICA',
                    importacao_rdo_id = ?,
                    fonte_arquivo = ?,
                    aba_origem = ?,
                    linha_origem = ?,
                    data_original = ?,
                    data_importacao = CURRENT_TIMESTAMP(6),
                    usuario_importacao = ?,
                    preenchido_por = ?,
                    apontador_rdo = ?,
                    encarregado_obra = ?,
                    fiscalizacao_campo = ?,
                    versao_mapeamento = ?,
                    chave_negocio_importacao = ?,
                    versao_linha = versao_linha + 1
                WHERE id = ?
                """,
                cabecalho.id(),
                cabecalho.nomeArquivo(),
                linha.aba(),
                linha.numeroLinha(),
                linha.dataRdo(),
                usuarioId,
                text(linha.payloadNormalizado(), "preenchidoPor"),
                text(linha.payloadNormalizado(), "apontadorRdo"),
                text(linha.payloadNormalizado(), "encarregadoObra"),
                text(linha.payloadNormalizado(), "fiscalizacaoCampo"),
                cabecalho.versaoMapeamento(),
                linha.chaveNegocio(),
                rdoId
        );

        apagarColecoesImportadas(rdoId);
        inserirServicoImportado(
                rdoId,
                linha.obraId(),
                cabecalho.id(),
                cabecalho.nomeArquivo(),
                linha.aba(),
                linha.numeroLinha(),
                text(linha.payloadNormalizado(), "turno"),
                linha.payloadNormalizado()
        );
        inserirColecoesImportadas(rdoId, linha.payloadNormalizado());

        rdoMemoryPublisher.registrarRdoImportado(
                rdoId,
                linha.obraId(),
                programacaoId,
                linha.numeroRdo(),
                cabecalho.id(),
                cabecalho.nomeArquivo(),
                linha.aba(),
                linha.numeroLinha(),
                "ENVIADO"
        );
    }

    private void inserirServicoImportado(
            String rdoId,
            String obraId,
            String importacaoId,
            String nomeArquivo,
            String aba,
            Integer numeroLinha,
            String turno,
            JsonNode payload
    ) {
        String servico = text(payload, "servicoNome");
        BigDecimal quantidade = decimal(payload, "quantidadeExecutada");

        if (servico == null || quantidade == null) {
            return;
        }

        operationalDetailService.substituirServicoImportadoHistorico(
                new RdoHistoricalImportServiceCommand(
                        rdoId, obraId, importacaoId, nomeArquivo, aba,
                        numeroLinha, null, servico,
                        text(payload, "itemContratualId"), quantidade,
                        text(payload, "unidade"),
                        text(payload, "trechoInicial"),
                        text(payload, "trechoFinal"),
                        text(payload, "localizacao"), turno,
                        text(payload, "observacoes")
                )
        );
    }

    private void inserirColecoesImportadas(String rdoId, JsonNode payload) {
        inserirMaoObraImportada(rdoId, payload.get("maoObra"));
        inserirEquipamentosImportados(rdoId, payload.get("equipamentos"));
        inserirMateriaisImportados(rdoId, payload.get("materiais"));
        inserirControlesImportados(rdoId, payload.get("controlesGeometricos"));
    }

    private void apagarColecoesImportadas(String rdoId) {
        jdbcTemplate.update("DELETE FROM rdo_controle_geometrico WHERE rdo_id = ?", rdoId);
        jdbcTemplate.update("DELETE FROM rdo_material WHERE rdo_id = ?", rdoId);
        jdbcTemplate.update("DELETE FROM rdo_equipamento WHERE rdo_id = ?", rdoId);
        jdbcTemplate.update("DELETE FROM rdo_mao_obra WHERE rdo_id = ?", rdoId);
    }

    private void inserirMaoObraImportada(String rdoId, JsonNode itens) {
        if (itens == null || !itens.isArray()) {
            return;
        }

        for (JsonNode item : itens) {
            String nome = text(item, "nomeColaborador");
            String cargo = text(item, "cargo");
            BigDecimal quantidade = valorOuUm(decimal(item, "quantidade"));

            if (isBlank(nome) && isBlank(cargo)) {
                continue;
            }

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
                    UUID.randomUUID().toString(),
                    rdoId,
                    nuloSeVazio(text(item, "colaboradorId")),
                    nome,
                    cargo,
                    primeiroNaoVazio(text(item, "tipoVinculo"), "CONTRATADO"),
                    quantidade,
                    parseTime(text(item, "horaInicio")),
                    parseTime(text(item, "horaFim")),
                    text(item, "observacoes")
            );
        }
    }

    private void inserirEquipamentosImportados(String rdoId, JsonNode itens) {
        if (itens == null || !itens.isArray()) {
            return;
        }

        for (JsonNode item : itens) {
            String prefixo = text(item, "prefixo");
            String descricao = text(item, "descricao");
            String tipoEquipamento = text(item, "tipoEquipamento");
            String assetId = nuloSeVazio(text(item, "assetId"));
            BigDecimal quantidade = valorOuUm(decimal(item, "quantidade"));

            if (isBlank(prefixo) && isBlank(descricao) && isBlank(tipoEquipamento)) {
                continue;
            }

            if (assetId != null) {
                jdbcTemplate.update(
                        """
                        INSERT INTO asset_obra_eligibilidade (
                            asset_id, obra_id, status, origem
                        )
                        SELECT ?, obra_id, 'ATIVO', 'IMPORTACAO_HISTORICA'
                        FROM rdo
                        WHERE id = ?
                        ON CONFLICT (asset_id, obra_id) DO NOTHING
                        """,
                        assetId,
                        rdoId
                );
            }

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
                    UUID.randomUUID().toString(),
                    rdoId,
                    assetId,
                    prefixo,
                    descricao,
                    tipoEquipamento,
                    primeiroNaoVazio(text(item, "tipoVinculo"), "PROPRIO"),
                    quantidade,
                    parseTime(text(item, "horaInicio")),
                    parseTime(text(item, "horaFim")),
                    text(item, "observacoes")
            );
        }
    }

    private void inserirMateriaisImportados(String rdoId, JsonNode itens) {
        if (itens == null || !itens.isArray()) {
            return;
        }

        for (JsonNode item : itens) {
            String materialNome = text(item, "materialNome");
            if (isBlank(materialNome)) {
                continue;
            }

            BigDecimal sobra = calcularSobra(
                    decimal(item, "quantidadeUsinada"),
                    decimal(item, "quantidadeAplicada"),
                    decimal(item, "quantidadeSobra")
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
                    UUID.randomUUID().toString(),
                    rdoId,
                    materialNome,
                    text(item, "unidade"),
                    decimal(item, "quantidadePrevista"),
                    decimal(item, "quantidadeUsinada"),
                    decimal(item, "quantidadeAplicada"),
                    sobra,
                    text(item, "notaFiscal"),
                    text(item, "fornecedor"),
                    text(item, "observacoes")
            );
        }
    }

    private void inserirControlesImportados(String rdoId, JsonNode itens) {
        if (itens == null || !itens.isArray()) {
            return;
        }

        for (JsonNode item : itens) {
            if (isBlank(text(item, "subtrecho"))
                    && isBlank(text(item, "kmInicial"))
                    && isBlank(text(item, "kmFinal"))
                    && decimal(item, "comprimentoM") == null
                    && decimal(item, "larguraM") == null) {
                continue;
            }

            BigDecimal espessuraMediaCm = calcularMedia(
                    decimal(item, "espessura1Cm"),
                    decimal(item, "espessura2Cm"),
                    decimal(item, "espessura3Cm")
            );
            BigDecimal areaM2 =
                    calcularArea(decimal(item, "comprimentoM"), decimal(item, "larguraM"));
            BigDecimal volumeM3 = calcularVolume(areaM2, espessuraMediaCm);
            BigDecimal massaTonelada =
                    calcularMassa(volumeM3, decimal(item, "densidade"));

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
                    UUID.randomUUID().toString(),
                    rdoId,
                    text(item, "subtrecho"),
                    text(item, "numero"),
                    text(item, "estacaInicial"),
                    text(item, "estacaFinal"),
                    text(item, "kmInicial"),
                    text(item, "kmFinal"),
                    text(item, "pista"),
                    text(item, "faixa"),
                    text(item, "ordemServico"),
                    text(item, "atividadeObservacoes"),
                    decimal(item, "comprimentoM"),
                    decimal(item, "larguraM"),
                    decimal(item, "espessura1Cm"),
                    decimal(item, "espessura2Cm"),
                    decimal(item, "espessura3Cm"),
                    espessuraMediaCm,
                    areaM2,
                    volumeM3,
                    decimal(item, "densidade"),
                    massaTonelada,
                    text(item, "observacoes")
            );
        }
    }

    private void inserirLinha(
            String importacaoId,
            String aba,
            RawRow row,
            LineValidation validation
    ) {
        String lineId = UUID.randomUUID().toString();
        String status = validation.errors().isEmpty()
                ? "VALIDA"
                : "INVALIDA";

        jdbcTemplate.update(
                """
                INSERT INTO importacao_rdo_linha (
                    id,
                    importacao_id,
                    aba,
                    numero_linha,
                    payload_original_json,
                    payload_normalizado_json,
                    layout_detectado,
                    chave_negocio,
                    status,
                    obra_id,
                    numero_rdo,
                    data_rdo,
                    quantidade_erros,
                    quantidade_warnings
                ) VALUES (?, ?, ?, ?, CAST(? AS JSONB), CAST(? AS JSONB),
                          'RDO_TABULAR', ?, ?, ?, ?, ?, ?, ?)
                """,
                lineId,
                importacaoId,
                aba,
                row.number(),
                toJson(row.original()),
                toJson(validation.normalized()),
                validation.businessKey(),
                status,
                validation.obraId(),
                validation.numeroRdo(),
                validation.dataRdo(),
                validation.errors().size(),
                validation.warnings().size()
        );

        validation.errors().forEach(error ->
                registrarErro(
                        importacaoId,
                        lineId,
                        aba,
                        row.number(),
                        error.campo(),
                        error.valorRecebido(),
                        error.valorEsperado(),
                        error.mensagem(),
                        error.sugestao(),
                        "ERRO"
                ));
        validation.warnings().forEach(error ->
                registrarErro(
                        importacaoId,
                        lineId,
                        aba,
                        row.number(),
                        error.campo(),
                        error.valorRecebido(),
                        error.valorEsperado(),
                        error.mensagem(),
                        error.sugestao(),
                        "WARNING"
                ));
    }

    private LineValidation validateLine(String aba, RawRow row) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        List<LineError> errors = new ArrayList<>();
        List<LineError> warnings = new ArrayList<>();

        String obraRef = first(row.values(), "obra", "obraId", "codigoObra");
        String obraId = null;
        if (isBlank(obraRef)) {
            errors.add(error("obra", obraRef, "Obra existente", "Obra é obrigatória.", "Informe id, código CW ou contrato da obra."));
        } else {
            obraId = buscarObraId(obraRef);
            if (obraId == null) {
                errors.add(error("obra", obraRef, "Obra existente", "Obra não encontrada.", "Cadastre a obra antes de importar o RDO."));
            }
        }

        String numeroRdo = first(row.values(), "numeroRdo");
        if (isBlank(numeroRdo)) {
            errors.add(error("numeroRdo", numeroRdo, "Número do RDO", "Número do RDO é obrigatório.", "Preencha a coluna número do RDO."));
        }

        LocalDate dataRdo = parseDate(first(row.values(), "dataRdo"));
        if (dataRdo == null) {
            errors.add(error("dataRdo", first(row.values(), "dataRdo"), "Data válida", "Data do RDO é obrigatória.", "Use AAAA-MM-DD ou DD/MM/AAAA."));
        }

        String itemContratualId = first(row.values(), "itemContratualId");
        String codigoItem = first(row.values(), "codigoItem");
        if (isBlank(itemContratualId) && !isBlank(codigoItem) && obraId != null) {
            itemContratualId = buscarItemContratualPorCodigo(obraId, codigoItem);
            if (itemContratualId == null) {
                warnings.add(error("codigoItem", codigoItem, "Item contratual ativo", "Item contratual não localizado; receita não será estimada para a linha.", "Cadastre ou mapeie o item contratual antes de confirmar."));
            }
        }

        String servico = first(row.values(), "servicoNome");
        BigDecimal quantidade = decimal(first(row.values(), "quantidadeExecutada"));
        String unidade = first(row.values(), "unidade");
        if (!isBlank(servico) || quantidade != null || !isBlank(itemContratualId)) {
            if (isBlank(servico)) {
                errors.add(error("servicoNome", servico, "Serviço executado", "Serviço é obrigatório quando há quantidade.", "Mapeie a coluna de serviço."));
            }
            if (quantidade == null || quantidade.compareTo(BigDecimal.ZERO) < 0) {
                errors.add(error("quantidadeExecutada", first(row.values(), "quantidadeExecutada"), "Número maior ou igual a zero", "Quantidade executada inválida.", "Corrija a quantidade executada."));
            }
            if (isBlank(unidade) && isBlank(itemContratualId)) {
                errors.add(error("unidade", unidade, "Unidade de medida", "Unidade é obrigatória quando não há item contratual.", "Informe m², m³, t, h ou outra unidade contratual."));
            }
        }

        normalized.put("obraId", obraId);
        normalized.put("numeroRdo", trim(numeroRdo));
        normalized.put("dataRdo", dataRdo == null ? null : dataRdo.toString());
        normalized.put("cliente", trim(first(row.values(), "cliente")));
        normalized.put("contrato", trim(first(row.values(), "contrato")));
        normalized.put("rodovia", trim(first(row.values(), "rodovia")));
        normalized.put("cidade", trim(first(row.values(), "cidade")));
        normalized.put("uf", trim(first(row.values(), "uf")));
        normalized.put("kmInicialProgramado", trim(first(row.values(), "kmInicialProgramado")));
        normalized.put("kmFinalProgramado", trim(first(row.values(), "kmFinalProgramado")));
        normalized.put("kmInicialInterditado", trim(first(row.values(), "kmInicialInterditado")));
        normalized.put("kmFinalInterditado", trim(first(row.values(), "kmFinalInterditado")));
        normalized.put("turno", trim(first(row.values(), "turno")));
        normalized.put("horaInicio", trim(first(row.values(), "horaInicio")));
        normalized.put("horaFim", trim(first(row.values(), "horaFim")));
        normalized.put("condicaoManha", trim(first(row.values(), "condicaoManha")));
        normalized.put("condicaoTarde", trim(first(row.values(), "condicaoTarde")));
        normalized.put("condicaoNoite", trim(first(row.values(), "condicaoNoite")));
        normalized.put("pluviometriaMm", decimal(first(row.values(), "pluviometriaMm")));
        normalized.put("observacoes", trim(first(row.values(), "observacoes")));
        normalized.put("preenchidoPor", trim(first(row.values(), "preenchidoPor")));
        normalized.put("apontadorRdo", trim(first(row.values(), "apontadorRdo")));
        normalized.put("encarregadoObra", trim(first(row.values(), "encarregadoObra")));
        normalized.put("fiscalizacaoCampo", trim(first(row.values(), "fiscalizacaoCampo")));
        normalized.put("servicoNome", trim(servico));
        normalized.put("itemContratualId", trim(itemContratualId));
        normalized.put("codigoItem", trim(codigoItem));
        normalized.put("quantidadeExecutada", quantidade);
        normalized.put("unidade", trim(unidade));
        normalized.put("trechoInicial", trim(first(row.values(), "trechoInicial")));
        normalized.put("trechoFinal", trim(first(row.values(), "trechoFinal")));
        normalized.put("localizacao", trim(first(row.values(), "localizacao")));
        normalized.put("custoRealizado", decimal(first(row.values(), "custoRealizado")));
        copyOriginalCollection(row.original(), normalized, "maoObra");
        copyOriginalCollection(row.original(), normalized, "equipamentos");
        copyOriginalCollection(row.original(), normalized, "materiais");
        copyOriginalCollection(row.original(), normalized, "controlesGeometricos");

        String key = obraId == null || dataRdo == null || isBlank(numeroRdo)
                ? null
                : sha256((obraId + "|" + numeroRdo.trim() + "|" + dataRdo).getBytes(StandardCharsets.UTF_8));

        return new LineValidation(
                normalized,
                errors,
                warnings,
                obraId,
                trim(numeroRdo),
                dataRdo,
                key
        );
    }

    private List<SheetRows> readSheets(
            String filename,
            byte[] bytes
    ) throws Exception {
        String lower = filename == null
                ? ""
                : filename.toLowerCase(Locale.ROOT);

        if (lower.endsWith(".csv")) {
            return List.of(readCsv(bytes));
        }

        if (lower.endsWith(".xlsx") || lower.endsWith(".xlsm") || lower.endsWith(".xls")) {
            return readWorkbook(filename, bytes);
        }

        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Formato não suportado. Use .xlsx, .xlsm, .xls ou .csv."
        );
    }

    private SheetRows readCsv(byte[] bytes) {
        String content = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = content.split("\\R");

        if (lines.length == 0) {
            return new SheetRows("CSV", Map.of(), List.of());
        }

        char separator = lines[0].contains(";") ? ';' : ',';
        List<String> headers = parseDelimited(lines[0], separator);
        Map<String, String> mapping = mapHeaders(headers);
        List<RawRow> rows = new ArrayList<>();

        for (int index = 1; index < lines.length; index++) {
            if (lines[index] == null || lines[index].isBlank()) {
                continue;
            }
            List<String> values = parseDelimited(lines[index], separator);
            rows.add(new RawRow(
                    index + 1,
                    originalMap(headers, values),
                    normalizedMap(headers, values, mapping)
            ));
        }

        return new SheetRows("CSV", mapping, rows);
    }

    private List<SheetRows> readWorkbook(String filename, byte[] bytes) throws Exception {
        List<SheetRows> result = new ArrayList<>();
        DataFormatter formatter = new DataFormatter(Locale.forLanguageTag("pt-BR"));

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            SheetRows legacyRdo = readLegacyRdoWorkbook(filename, workbook, formatter);
            if (legacyRdo != null) {
                return List.of(legacyRdo);
            }

            for (Sheet sheet : workbook) {
                Row headerRow = firstNonEmptyRow(sheet);
                if (headerRow == null) {
                    continue;
                }

                List<String> headers = new ArrayList<>();
                for (Cell cell : headerRow) {
                    headers.add(formatter.formatCellValue(cell));
                }

                Map<String, String> mapping = mapHeaders(headers);
                List<RawRow> rows = new ArrayList<>();

                for (int rowIndex = headerRow.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                    Row sheetRow = sheet.getRow(rowIndex);
                    if (sheetRow == null) {
                        continue;
                    }
                    List<String> values = new ArrayList<>();
                    boolean hasValue = false;
                    for (int cellIndex = 0; cellIndex < headers.size(); cellIndex++) {
                        String value = formatter.formatCellValue(sheetRow.getCell(cellIndex));
                        values.add(value);
                        hasValue = hasValue || !isBlank(value);
                    }
                    if (!hasValue) {
                        continue;
                    }
                    rows.add(new RawRow(
                            rowIndex + 1,
                            originalMap(headers, values),
                            normalizedMap(headers, values, mapping)
                    ));
                }

                result.add(new SheetRows(sheet.getSheetName(), mapping, rows));
            }
        }

        return result;
    }

    private SheetRows readLegacyRdoWorkbook(
            String filename,
            Workbook workbook,
            DataFormatter formatter
    ) {
        Sheet frente = findSheet(workbook, "frente");
        Sheet verso = findSheet(workbook, "verso");

        if (frente == null || !containsAny(frente, formatter, List.of(
                "RELATÓRIO DIÁRIO DE OBRA",
                "MÃO DE OBRA",
                "EQUIPAMENTOS"
        ))) {
            return null;
        }

        Map<String, Object> original = new LinkedHashMap<>();
        Map<String, String> values = new LinkedHashMap<>();

        String contrato = first(
                rangeText(frente, formatter, 6, "Q", "U"),
                rangeText(frente, formatter, 5, "Q", "U")
                        .replace("N° DA OBRA", "")
                        .trim()
        );
        String dataRdo = first(
                rangeText(frente, formatter, 6, "AA", "AE"),
                verso == null ? null : rangeText(verso, formatter, 4, "AB", "AD")
        );
        String numeroRdo = filename == null || filename.isBlank()
                ? "RDO importado"
                : filename.replaceFirst("\\.[^.]+$", "");

        put(values, "obra", contrato);
        put(values, "numeroRdo", numeroRdo);
        put(values, "dataRdo", dataRdo);
        put(values, "cliente", rangeText(frente, formatter, 6, "B", "P"));
        put(values, "contrato", contrato);
        put(values, "rodovia", rangeText(frente, formatter, 6, "V", "Z"));
        put(values, "kmInicialProgramado", rangeText(frente, formatter, 10, "Q", "U"));
        put(values, "kmFinalProgramado", rangeText(frente, formatter, 12, "Q", "U"));
        put(values, "kmInicialInterditado", rangeText(frente, formatter, 10, "V", "Z"));
        put(values, "kmFinalInterditado", rangeText(frente, formatter, 12, "V", "Z"));
        put(values, "turno", detectTurno(frente, formatter));
        put(values, "horaInicio", first(
                rangeText(frente, formatter, 10, "AA", "AE"),
                rangeText(frente, formatter, 10, "AF", "AJ")
        ));
        put(values, "horaFim", first(
                rangeText(frente, formatter, 12, "AA", "AE"),
                rangeText(frente, formatter, 12, "AF", "AJ")
        ));
        put(values, "condicaoManha", detectCondicao(frente, formatter, 10));
        put(values, "condicaoTarde", detectCondicao(frente, formatter, 11));
        put(values, "condicaoNoite", detectCondicao(frente, formatter, 12));
        put(values, "pluviometriaMm", rangeText(frente, formatter, 10, "M", "P"));

        List<Map<String, Object>> maoObra = parseLegacyMaoObra(frente, formatter);
        List<Map<String, Object>> equipamentos = parseLegacyEquipamentos(frente, formatter);
        List<Map<String, Object>> controles = new ArrayList<>();
        controles.addAll(parseLegacyTrechoTrabalhado(frente, formatter));
        if (verso != null) {
            controles.addAll(parseLegacyControleGeometrico(verso, formatter));
        }

        List<Map<String, Object>> materiais =
                verso == null
                        ? List.of()
                        : parseLegacyMateriais(verso, formatter);
        String observacoes =
                verso == null
                        ? null
                        : rangeText(verso, formatter, 63, 69, "B", "AD");
        String apontador =
                verso == null
                        ? null
                        : rangeText(verso, formatter, 71, "B", "J");
        String encarregado =
                verso == null
                        ? null
                        : rangeText(verso, formatter, 71, "L", "T");
        String fiscalizacao =
                verso == null
                        ? null
                        : rangeText(verso, formatter, 71, "V", "AD");

        put(values, "observacoes", observacoes);
        put(values, "preenchidoPor", apontador);
        put(values, "apontadorRdo", apontador);
        put(values, "encarregadoObra", encarregado);
        put(values, "fiscalizacaoCampo", fiscalizacao);

        original.putAll(values);
        original.put("maoObra", maoObra);
        original.put("equipamentos", equipamentos);
        original.put("materiais", materiais);
        original.put("controlesGeometricos", controles);

        Map<String, String> mapping = new LinkedHashMap<>();
        values.keySet().forEach(field -> mapping.put(field, field));
        mapping.put("maoObra", "maoObra");
        mapping.put("equipamentos", "equipamentos");
        mapping.put("materiais", "materiais");
        mapping.put("controlesGeometricos", "controlesGeometricos");

        return new SheetRows(
                "RDO_LEGACY_V1",
                mapping,
                List.of(new RawRow(1, original, values))
        );
    }

    private Sheet findSheet(Workbook workbook, String token) {
        String normalizedToken = normalize(token);
        for (Sheet sheet : workbook) {
            if (normalize(sheet.getSheetName()).contains(normalizedToken)) {
                return sheet;
            }
        }
        if ("frente".equals(normalizedToken) && workbook.getNumberOfSheets() > 0) {
            return workbook.getSheetAt(0);
        }
        return null;
    }

    private boolean containsAny(
            Sheet sheet,
            DataFormatter formatter,
            List<String> terms
    ) {
        int lastRow = Math.min(sheet.getLastRowNum(), 80);
        for (int rowIndex = 0; rowIndex <= lastRow; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            for (Cell cell : row) {
                String value = normalize(formatter.formatCellValue(cell));
                for (String term : terms) {
                    if (value.contains(normalize(term))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private List<Map<String, Object>> parseLegacyMaoObra(
            Sheet sheet,
            DataFormatter formatter
    ) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int row = 16; row <= 28; row++) {
            result.addAll(parseLegacyMaoObraBlock(sheet, formatter, row, "B", "G", "J"));
            result.addAll(parseLegacyMaoObraBlock(sheet, formatter, row, "M", "R", "U"));
        }
        return result;
    }

    private List<Map<String, Object>> parseLegacyMaoObraBlock(
            Sheet sheet,
            DataFormatter formatter,
            int row,
            String cargoColumn,
            String contratadoColumn,
            String subcontratadoColumn
    ) {
        String cargo = rangeText(sheet, formatter, row, cargoColumn, cargoColumn);
        if (isBlank(cargo)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        addLegacyMaoObra(result, cargo, "CONTRATADO", rangeText(sheet, formatter, row, contratadoColumn, contratadoColumn));
        addLegacyMaoObra(result, cargo, "TERCEIRIZADO", rangeText(sheet, formatter, row, subcontratadoColumn, subcontratadoColumn));
        return result;
    }

    private void addLegacyMaoObra(
            List<Map<String, Object>> result,
            String cargo,
            String tipoVinculo,
            String quantidade
    ) {
        if (decimal(quantidade) == null) {
            return;
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("cargo", cargo);
        item.put("tipoVinculo", tipoVinculo);
        item.put("quantidade", quantidade);
        result.add(item);
    }

    private List<Map<String, Object>> parseLegacyEquipamentos(
            Sheet sheet,
            DataFormatter formatter
    ) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int row = 36; row <= 51; row++) {
            result.addAll(parseLegacyEquipamentoBlock(sheet, formatter, row, "B", "I", "L", "O"));
            result.addAll(parseLegacyEquipamentoBlock(sheet, formatter, row, "T", "AB", "AE", "AH"));
        }
        return result;
    }

    private List<Map<String, Object>> parseLegacyEquipamentoBlock(
            Sheet sheet,
            DataFormatter formatter,
            int row,
            String descricaoColumn,
            String proprioColumn,
            String subcontratadoColumn,
            String prefixoColumn
    ) {
        String descricao = rangeText(sheet, formatter, row, descricaoColumn, descricaoColumn);
        String prefixo = rangeText(sheet, formatter, row, prefixoColumn, prefixoColumn);
        if (isBlank(descricao)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        addLegacyEquipamento(result, descricao, prefixo, "PROPRIO", rangeText(sheet, formatter, row, proprioColumn, proprioColumn));
        addLegacyEquipamento(result, descricao, prefixo, "TERCEIRIZADO", rangeText(sheet, formatter, row, subcontratadoColumn, subcontratadoColumn));
        if (result.isEmpty() && !isBlank(prefixo)) {
            addLegacyEquipamento(result, descricao, prefixo, "PROPRIO", "1");
        }
        return result;
    }

    private void addLegacyEquipamento(
            List<Map<String, Object>> result,
            String descricao,
            String prefixo,
            String tipoVinculo,
            String quantidade
    ) {
        BigDecimal parsedQuantity = decimal(quantidade);
        if (parsedQuantity == null) {
            return;
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("prefixo", prefixo);
        item.put("descricao", descricao);
        item.put("tipoEquipamento", descricao);
        item.put("tipoVinculo", tipoVinculo);
        item.put("quantidade", quantidade);
        result.add(item);
    }

    private List<Map<String, Object>> parseLegacyMateriais(
            Sheet sheet,
            DataFormatter formatter
    ) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int row = 8; row <= 18; row++) {
            result.addAll(parseLegacyMaterialBlock(sheet, formatter, row, "B", "E", "G", "H"));
            result.addAll(parseLegacyMaterialBlock(sheet, formatter, row, "L", "O", "Q", "R"));
            result.addAll(parseLegacyMaterialBlock(sheet, formatter, row, "V", "Y", "AA", "AB"));
        }
        String usinado = extractNumberText(rangeText(sheet, formatter, 19, "B", "G"));
        String sobra = extractNumberText(rangeText(sheet, formatter, 21, "B", "G"));
        result.forEach(item -> {
            if (!isBlank(usinado)) {
                item.putIfAbsent("quantidadeUsinada", usinado);
            }
            if (!isBlank(sobra)) {
                item.putIfAbsent("quantidadeSobra", sobra);
            }
        });
        return result;
    }

    private List<Map<String, Object>> parseLegacyMaterialBlock(
            Sheet sheet,
            DataFormatter formatter,
            int row,
            String materialColumn,
            String quantidadeColumn,
            String unidadeColumn,
            String notaFiscalColumn
    ) {
        String material = rangeText(sheet, formatter, row, materialColumn, materialColumn);
        String quantidade = rangeText(sheet, formatter, row, quantidadeColumn, quantidadeColumn);
        String unidade = rangeText(sheet, formatter, row, unidadeColumn, unidadeColumn);
        String notaFiscal = rangeText(sheet, formatter, row, notaFiscalColumn, notaFiscalColumn);
        if (isBlank(material) && isBlank(quantidade) && isBlank(unidade) && isBlank(notaFiscal)) {
            return List.of();
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("materialNome", material);
        item.put("unidade", unidade);
        item.put("quantidadeAplicada", quantidade);
        item.put("notaFiscal", notaFiscal);
        return List.of(item);
    }

    private List<Map<String, Object>> parseLegacyTrechoTrabalhado(
            Sheet sheet,
            DataFormatter formatter
    ) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int row = 60; row <= 82; row++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kmInicial", rangeText(sheet, formatter, row, "B", "D"));
            item.put("kmFinal", rangeText(sheet, formatter, row, "E", "G"));
            item.put("numero", rangeText(sheet, formatter, row, "H", "I"));
            item.put("comprimentoM", rangeText(sheet, formatter, row, "J", "K"));
            item.put("larguraM", rangeText(sheet, formatter, row, "L", "M"));
            String espessuraM = rangeText(sheet, formatter, row, "N", "O");
            item.put("espessura1Cm", toCentimetersText(espessuraM));
            item.put("pista", rangeText(sheet, formatter, row, "P", "Q"));
            item.put("faixa", rangeText(sheet, formatter, row, "R", "S"));
            item.put("ordemServico", rangeText(sheet, formatter, row, "T", "W"));
            item.put("atividadeObservacoes", rangeText(sheet, formatter, row, "X", "AJ"));
            item.put("observacoes", item.get("atividadeObservacoes"));
            item.put("subtrecho", first(
                    (String) item.get("numero"),
                    (String) item.get("kmInicial"),
                    (String) item.get("kmFinal")
            ));
            if (hasAnyValue(item)) {
                result.add(item);
            }
        }
        return result;
    }

    private List<Map<String, Object>> parseLegacyControleGeometrico(
            Sheet sheet,
            DataFormatter formatter
    ) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int row = 26; row <= 61; row++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("subtrecho", rangeText(sheet, formatter, row, "B", "E"));
            item.put("comprimentoM", rangeText(sheet, formatter, row, "F", "H"));
            item.put("larguraM", rangeText(sheet, formatter, row, "I", "K"));
            item.put("espessura1Cm", toCentimetersText(rangeText(sheet, formatter, row, "L", "N")));
            item.put("espessura2Cm", toCentimetersText(rangeText(sheet, formatter, row, "O", "Q")));
            item.put("espessura3Cm", toCentimetersText(rangeText(sheet, formatter, row, "R", "T")));
            String massa = rangeText(sheet, formatter, row, "AA", "AD");
            if (!isBlank(massa)) {
                item.put("observacoes", "Massa informada no Excel: " + massa + " t");
            }
            if (hasAnyValue(item)) {
                result.add(item);
            }
        }
        return result;
    }

    private String detectTurno(Sheet sheet, DataFormatter formatter) {
        String diurno = first(
                rangeText(sheet, formatter, 10, "AA", "AE"),
                rangeText(sheet, formatter, 12, "AA", "AE")
        );
        String noturno = first(
                rangeText(sheet, formatter, 10, "AF", "AJ"),
                rangeText(sheet, formatter, 12, "AF", "AJ")
        );
        return isBlank(diurno) && !isBlank(noturno) ? "NOTURNO" : "DIURNO";
    }

    private String detectCondicao(
            Sheet sheet,
            DataFormatter formatter,
            int row
    ) {
        if (!isBlank(rangeText(sheet, formatter, row, "D", "F"))) {
            return "BOM";
        }
        if (!isBlank(rangeText(sheet, formatter, row, "G", "I"))) {
            return "CHUVA";
        }
        if (!isBlank(rangeText(sheet, formatter, row, "J", "L"))) {
            return "IMPOSSIBILITADO";
        }
        return null;
    }

    private String rangeText(
            Sheet sheet,
            DataFormatter formatter,
            int row,
            String startColumn,
            String endColumn
    ) {
        return rangeText(sheet, formatter, row, row, startColumn, endColumn);
    }

    private String rangeText(
            Sheet sheet,
            DataFormatter formatter,
            int startRow,
            int endRow,
            String startColumn,
            String endColumn
    ) {
        int start = columnIndex(startColumn);
        int end = columnIndex(endColumn);
        List<String> values = new ArrayList<>();
        for (int rowNumber = startRow; rowNumber <= endRow; rowNumber++) {
            Row row = sheet.getRow(rowNumber - 1);
            if (row == null) {
                continue;
            }
            for (int column = start; column <= end; column++) {
                String value = formatter.formatCellValue(row.getCell(column));
                if (!isBlank(value)) {
                    values.add(value.trim());
                }
            }
        }
        return String.join(" ", values).trim();
    }

    private int columnIndex(String column) {
        int result = 0;
        for (int index = 0; index < column.length(); index++) {
            result = result * 26 + (column.charAt(index) - 'A' + 1);
        }
        return result - 1;
    }

    private void put(Map<String, String> values, String key, String value) {
        if (!isBlank(value)) {
            values.put(key, value.trim());
        }
    }

    private void copyOriginalCollection(
            Map<String, Object> original,
            Map<String, Object> normalized,
            String key
    ) {
        Object value = original.get(key);
        if (value instanceof List<?> list && !list.isEmpty()) {
            normalized.put(key, list);
        }
    }

    private String toCentimetersText(String metersText) {
        BigDecimal meters = decimal(extractNumberText(metersText));
        return meters == null
                ? null
                : meters.multiply(BigDecimal.valueOf(100))
                        .setScale(3, RoundingMode.HALF_UP)
                        .stripTrailingZeros()
                        .toPlainString();
    }

    private String extractNumberText(String value) {
        if (isBlank(value)) {
            return null;
        }
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("-?\\d[\\d.,]*").matcher(value);
        return matcher.find() ? matcher.group() : null;
    }

    private boolean hasAnyValue(Map<String, Object> item) {
        return item.values().stream().anyMatch(value -> {
            if (value == null) {
                return false;
            }
            return !(value instanceof String text) || !text.isBlank();
        });
    }

    private Row firstNonEmptyRow(Sheet sheet) {
        for (Row row : sheet) {
            for (Cell cell : row) {
                if (!isBlank(new DataFormatter().formatCellValue(cell))) {
                    return row;
                }
            }
        }
        return null;
    }

    private Map<String, String> mapHeaders(List<String> headers) {
        Map<String, String> mapping = new LinkedHashMap<>();

        for (String header : headers) {
            String field = canonicalField(header);
            if (field != null) {
                mapping.put(header, field);
            }
        }

        return mapping;
    }

    private Map<String, Object> originalMap(
            List<String> headers,
            List<String> values
    ) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index < headers.size(); index++) {
            map.put(headers.get(index), index < values.size() ? values.get(index) : null);
        }
        return map;
    }

    private Map<String, String> normalizedMap(
            List<String> headers,
            List<String> values,
            Map<String, String> mapping
    ) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int index = 0; index < headers.size(); index++) {
            String field = mapping.get(headers.get(index));
            if (field != null) {
                map.put(field, index < values.size() ? trim(values.get(index)) : null);
            }
        }
        return map;
    }

    private String canonicalField(String header) {
        String value = normalize(header);
        return switch (value) {
            case "obra", "obra_id", "obraid", "codigo_obra", "codigoobra", "codigo_contrato", "contrato", "codigo_cw", "cw" -> "obra";
            case "numero_rdo", "numerordo", "numero", "rdo", "n_rdo", "nro_rdo" -> "numeroRdo";
            case "data_rdo", "datardo", "data", "data_operacional" -> "dataRdo";
            case "cliente", "obra_nome", "nome_obra" -> "cliente";
            case "rodovia", "rod" -> "rodovia";
            case "cidade", "municipio" -> "cidade";
            case "uf", "estado" -> "uf";
            case "km_inicial_programado", "kminicialprogramado", "trecho_programado_inicial" -> "kmInicialProgramado";
            case "km_final_programado", "kmfinalprogramado", "trecho_programado_final" -> "kmFinalProgramado";
            case "km_inicial_interditado", "kminicialinterditado", "trecho_interditado_inicial" -> "kmInicialInterditado";
            case "km_final_interditado", "kmfinalinterditado", "trecho_interditado_final" -> "kmFinalInterditado";
            case "turno" -> "turno";
            case "hora_inicio", "horainicio", "inicio" -> "horaInicio";
            case "hora_fim", "horafim", "fim" -> "horaFim";
            case "condicao_manha", "clima_manha" -> "condicaoManha";
            case "condicao_tarde", "clima_tarde" -> "condicaoTarde";
            case "condicao_noite", "clima_noite" -> "condicaoNoite";
            case "pluviometria", "pluviometria_mm", "chuva_mm" -> "pluviometriaMm";
            case "observacoes", "observacao", "obs" -> "observacoes";
            case "preenchido_por", "preenchidopor", "apontador_rdo", "apontador" -> "preenchidoPor";
            case "encarregado_obra", "encarregado" -> "encarregadoObra";
            case "fiscalizacao_campo", "fiscalizacao" -> "fiscalizacaoCampo";
            case "servico", "servico_nome", "serviconome", "atividade" -> "servicoNome";
            case "quantidade", "quantidade_executada", "qtd", "qtd_executada" -> "quantidadeExecutada";
            case "unidade", "unidade_medida", "un" -> "unidade";
            case "item_contratual_id", "itemcontratualid" -> "itemContratualId";
            case "codigo_item", "codigoitem", "item_contratual", "item" -> "codigoItem";
            case "trecho_inicial", "km_inicial", "kminicial" -> "trechoInicial";
            case "trecho_final", "km_final", "kmfinal" -> "trechoFinal";
            case "localizacao", "local", "trecho" -> "localizacao";
            case "custo", "custo_realizado", "custorealizado" -> "custoRealizado";
            default -> null;
        };
    }

    private List<String> parseDelimited(String line, char separator) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;

        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);

            if (character == '"') {
                quoted = !quoted;
            } else if (character == separator && !quoted) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }

        values.add(current.toString());
        return values;
    }

    private List<ImportLine> linhasValidas(String importacaoId) {
        return jdbcTemplate.query(
                """
                SELECT
                    id,
                    aba,
                    numero_linha,
                    payload_normalizado_json,
                    chave_negocio,
                    obra_id,
                    numero_rdo,
                    data_rdo
                FROM importacao_rdo_linha
                WHERE importacao_id = ?
                  AND status = 'VALIDA'
                ORDER BY aba, numero_linha
                """,
                (rs, rowNumber) -> new ImportLine(
                        rs.getString("id"),
                        rs.getString("aba"),
                        rs.getInt("numero_linha"),
                        parseJson(rs.getString("payload_normalizado_json")),
                        rs.getString("chave_negocio"),
                        rs.getString("obra_id"),
                        rs.getString("numero_rdo"),
                        rs.getDate("data_rdo").toLocalDate()
                ),
                importacaoId
        );
    }

    private ImportacaoCabecalho buscarCabecalho(String importacaoId) {
        List<ImportacaoCabecalho> result = jdbcTemplate.query(
                """
                SELECT
                    id,
                    nome_arquivo,
                    hash_arquivo,
                    tamanho_bytes,
                    layout_detectado,
                    status,
                    estrategia_duplicidade,
                    versao_mapeamento,
                    quantidade_processada,
                    quantidade_importada,
                    quantidade_rejeitada,
                    quantidade_duplicada,
                    usuario_id,
                    criado_em,
                    confirmado_em
                FROM importacao_rdo
                WHERE id = ?
                """,
                (rs, rowNumber) -> new ImportacaoCabecalho(
                        rs.getString("id"),
                        rs.getString("nome_arquivo"),
                        rs.getString("hash_arquivo"),
                        rs.getLong("tamanho_bytes"),
                        rs.getString("layout_detectado"),
                        rs.getString("status"),
                        rs.getString("estrategia_duplicidade"),
                        rs.getString("versao_mapeamento"),
                        rs.getInt("quantidade_processada"),
                        rs.getInt("quantidade_importada"),
                        rs.getInt("quantidade_rejeitada"),
                        rs.getInt("quantidade_duplicada"),
                        rs.getString("usuario_id"),
                        toLocalDateTime(rs.getTimestamp("criado_em")),
                        toLocalDateTime(rs.getTimestamp("confirmado_em"))
                ),
                importacaoId
        );

        if (result.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Importação de RDO não encontrada."
            );
        }

        return result.getFirst();
    }

    private void validarAcessoImportacao(
            ImportacaoCabecalho cabecalho,
            String usuarioAutenticadoId,
            boolean admin
    ) {
        if (admin) {
            return;
        }
        if (usuarioAutenticadoId == null || usuarioAutenticadoId.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Usuário autenticado é obrigatório para acessar a importação."
            );
        }
        if (cabecalho.usuarioId() == null
                || !cabecalho.usuarioId().equals(usuarioAutenticadoId.trim())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Você não pode acessar importação de outro usuário."
            );
        }
    }

    private List<RdoImportacaoErroResponse> buscarErros(String linhaId) {
        return jdbcTemplate.query(
                """
                SELECT
                    id,
                    campo,
                    valor_recebido,
                    valor_esperado,
                    mensagem,
                    sugestao,
                    severidade
                FROM importacao_rdo_erro
                WHERE linha_id = ?
                ORDER BY severidade, campo, id
                """,
                (rs, rowNumber) -> new RdoImportacaoErroResponse(
                        rs.getString("id"),
                        rs.getString("campo"),
                        rs.getString("valor_recebido"),
                        rs.getString("valor_esperado"),
                        rs.getString("mensagem"),
                        rs.getString("sugestao"),
                        rs.getString("severidade")
                ),
                linhaId
        );
    }

    private String buscarObraId(String identifier) {
        List<String> ids = jdbcTemplate.query(
                """
                SELECT id
                FROM obra
                WHERE arquivado_em IS NULL
                  AND (
                        id = ?
                     OR codigo_contrato = ?
                     OR codigo_cw = ?
                     OR codigo_interno = ?
                  )
                ORDER BY id
                LIMIT 2
                """,
                (rs, rowNumber) -> rs.getString("id"),
                identifier,
                identifier,
                identifier,
                identifier
        );

        return ids.size() == 1 ? ids.getFirst() : null;
    }

    private String buscarItemContratualPorCodigo(String obraId, String codigoItem) {
        List<String> ids = jdbcTemplate.query(
                """
                SELECT id
                FROM item_contratual
                WHERE obra_id = ?
                  AND codigo_item = ?
                  AND status = 'ATIVO'
                ORDER BY versao DESC
                LIMIT 1
                """,
                (rs, rowNumber) -> rs.getString("id"),
                obraId,
                codigoItem
        );

        return ids.isEmpty() ? null : ids.getFirst();
    }

    private String buscarProgramacao(String obraId, LocalDate dataRdo) {
        List<String> ids = jdbcTemplate.query(
                """
                SELECT id
                FROM programacao_operacional
                WHERE obra_id = ?
                  AND data_programacao = ?
                  AND cancelado_em IS NULL
                ORDER BY id
                LIMIT 1
                """,
                (rs, rowNumber) -> rs.getString("id"),
                obraId,
                dataRdo
        );

        return ids.isEmpty() ? null : ids.getFirst();
    }

    private String buscarRdoDuplicado(
            String obraId,
            String numeroRdo,
            LocalDate dataRdo
    ) {
        List<String> ids = jdbcTemplate.query(
                """
                SELECT id
                FROM rdo
                WHERE obra_id = ?
                  AND numero_rdo = ?
                  AND data_rdo = ?
                  AND cancelado_em IS NULL
                ORDER BY criado_em DESC, id DESC
                LIMIT 1
                """,
                (rs, rowNumber) -> rs.getString("id"),
                obraId,
                numeroRdo,
                dataRdo
        );

        return ids.isEmpty() ? null : ids.getFirst();
    }

    private void marcarLinhaImportada(
            String linhaId,
            String rdoId,
            String status
    ) {
        jdbcTemplate.update(
                """
                UPDATE importacao_rdo_linha
                SET status = ?, rdo_id = ?
                WHERE id = ?
                """,
                status,
                rdoId,
                linhaId
        );
    }

    private void marcarLinhaStatus(String linhaId, String status) {
        jdbcTemplate.update(
                "UPDATE importacao_rdo_linha SET status = ? WHERE id = ?",
                status,
                linhaId
        );
    }

    private void registrarErro(
            String importacaoId,
            String lineId,
            String aba,
            Integer numeroLinha,
            String campo,
            String valorRecebido,
            String valorEsperado,
            String mensagem,
            String sugestao,
            String severidade
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO importacao_rdo_erro (
                    id,
                    importacao_id,
                    linha_id,
                    aba,
                    numero_linha,
                    campo,
                    valor_recebido,
                    valor_esperado,
                    mensagem,
                    sugestao,
                    severidade
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(),
                importacaoId,
                lineId,
                aba,
                numeroLinha,
                campo,
                valorRecebido,
                valorEsperado,
                mensagem,
                sugestao,
                severidade
        );
    }

    private LineError error(
            String campo,
            String valorRecebido,
            String valorEsperado,
            String mensagem,
            String sugestao
    ) {
        return new LineError(
                campo,
                valorRecebido,
                valorEsperado,
                mensagem,
                sugestao
        );
    }

    private String normalizarEstrategia(String value) {
        String estrategia = isBlank(value)
                ? "IGNORAR_DUPLICADO"
                : value.trim().toUpperCase(Locale.ROOT);

        if (!List.of(
                "IGNORAR_DUPLICADO",
                "ATUALIZAR_EXISTENTE",
                "CRIAR_NOVA_VERSAO",
                "REJEITAR"
        ).contains(estrategia)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "estrategiaDuplicidade inválida."
            );
        }

        return estrategia;
    }

    private boolean isRequired(String field) {
        return List.of("obra", "numeroRdo", "dataRdo").contains(field);
    }

    private String filename(MultipartFile file) {
        return isBlank(file.getOriginalFilename())
                ? "importacao-rdo"
                : file.getOriginalFilename();
    }

    private String first(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String first(Map<String, String> map, String... keys) {
        for (String key : keys) {
            String value = map.get(key);
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        return value.asText().trim();
    }

    private BigDecimal decimal(JsonNode node, String field) {
        return decimal(text(node, field));
    }

    private BigDecimal decimal(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            String normalized = value.trim();
            if (List.of("x", "sim", "ok").contains(normalized.toLowerCase(Locale.ROOT))) {
                return BigDecimal.ONE.setScale(3, RoundingMode.HALF_UP);
            }
            if (normalized.contains(",")) {
                normalized = normalized
                        .replace(".", "")
                        .replace(",", ".");
            }
            return new BigDecimal(normalized)
                    .setScale(3, RoundingMode.HALF_UP);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private BigDecimal valorOuUm(BigDecimal valor) {
        return valor == null ? BigDecimal.ONE : valor;
    }

    private String nuloSeVazio(String valor) {
        return isBlank(valor) ? null : valor.trim();
    }

    private String primeiroNaoVazio(String... valores) {
        return first(valores);
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
        return quantidade == 0
                ? null
                : soma.divide(BigDecimal.valueOf(quantidade), 3, RoundingMode.HALF_UP);
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
        return arredondar(
                areaM2.multiply(
                        espessuraMediaCm.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)
                )
        );
    }

    private BigDecimal calcularMassa(BigDecimal volumeM3, BigDecimal densidade) {
        if (volumeM3 == null || densidade == null) {
            return null;
        }
        return arredondar(volumeM3.multiply(densidade));
    }

    private BigDecimal arredondar(BigDecimal valor) {
        return valor == null ? null : valor.setScale(3, RoundingMode.HALF_UP);
    }

    private LocalDate parseDate(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDate.parse(value.trim(), DATE_BR);
            } catch (DateTimeParseException ignoredAgain) {
                return null;
            }
        }
    }

    private LocalTime parseTime(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return LocalTime.parse(value.trim());
        } catch (DateTimeParseException exception) {
            return null;
        }
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

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace(" ", "_")
                .replace("-", "_")
                .replace("/", "_");

        return normalized.replaceAll("_+", "_");
    }

    private String trim(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "Não foi possível serializar os dados da importação.",
                    exception
            );
        }
    }

    private JsonNode parseJson(String json) {
        try {
            if (json == null || json.isBlank()) {
                return objectMapper.nullNode();
            }
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            return objectMapper.createObjectNode().put("jsonInvalido", true);
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes)
            );
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Não foi possível calcular o hash do arquivo.",
                    exception
            );
        }
    }

    private LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private record SheetRows(
            String name,
            Map<String, String> mapping,
            List<RawRow> rows
    ) {
    }

    private record RawRow(
            int number,
            Map<String, Object> original,
            Map<String, String> values
    ) {
    }

    private record LineError(
            String campo,
            String valorRecebido,
            String valorEsperado,
            String mensagem,
            String sugestao
    ) {
    }

    private record LineValidation(
            Map<String, Object> normalized,
            List<LineError> errors,
            List<LineError> warnings,
            String obraId,
            String numeroRdo,
            LocalDate dataRdo,
            String businessKey
    ) {
    }

    private record ImportacaoCabecalho(
            String id,
            String nomeArquivo,
            String hashArquivo,
            long tamanhoBytes,
            String layoutDetectado,
            String status,
            String estrategiaDuplicidade,
            String versaoMapeamento,
            int quantidadeProcessada,
            int quantidadeImportada,
            int quantidadeRejeitada,
            int quantidadeDuplicada,
            String usuarioId,
            LocalDateTime criadoEm,
            LocalDateTime confirmadoEm
    ) {
    }

    private record ImportLine(
            String id,
            String aba,
            int numeroLinha,
            JsonNode payloadNormalizado,
            String chaveNegocio,
            String obraId,
            String numeroRdo,
            LocalDate dataRdo
    ) {
    }

    private record ResultadoDuplicidade(
            int imported,
            int duplicated,
            int rejected
    ) {
    }
}
