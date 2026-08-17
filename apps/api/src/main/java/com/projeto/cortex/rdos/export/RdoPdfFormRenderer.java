package com.projeto.cortex.rdos.export;

import com.projeto.cortex.rdos.RdoResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class RdoPdfFormRenderer {

    private static final float POINTS_PER_MILLIMETRE = 72f / 25.4f;
    private static final float PAGE_MARGIN = mm(10);
    private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    private static final float CONTENT_WIDTH = PAGE_WIDTH - (2 * PAGE_MARGIN);
    private static final float HAIRLINE = 0.35f;
    private static final float SECTION_HEIGHT = mm(4.7f);
    private static final float CELL_PADDING = 1.6f;
    private static final float WORDMARK_WIDTH = mm(38);
    private static final float WORDMARK_HEIGHT =
            WORDMARK_WIDTH * (329f / 1200f);
    private static final int SECTION_GRAY = 225;
    private static final float PAGE_BOX_TOLERANCE = 0.1f;
    private static final float OBSERVATION_FONT_SIZE = 6.5f;
    private static final float OBSERVATION_LINE_HEIGHT = 7.6f;
    private static final float MIN_READABLE_FONT_SIZE = 4f;
    private static final String UNSAFE_GLYPH_MESSAGE =
            "O conteúdo do RDO contém caractere sem representação segura no PDF; "
                    + "nenhum conteúdo foi substituído.";
    private static final String UNREADABLE_FITTED_TEXT_MESSAGE =
            "O conteúdo do RDO não permanece legível na célula fixa do PDF; "
                    + "nenhum conteúdo foi truncado.";
    /*
     * Os rótulos das colunas que recebem conteúdo do usuário. Ficam aqui, e
     * não soltos em cada chamada, porque servem duas vezes: desenham o
     * cabeçalho da tabela e nomeiam a coluna quando o conteúdo não couber.
     */
    private static final String[] WORKFORCE_COLUMNS = {
        "FUNÇÃO", "PRÓPRIA", "SUBCONT.",
        "FUNÇÃO", "PRÓPRIA", "SUBCONT."
    };
    private static final String[] EQUIPMENT_COLUMNS = {
        "DESCRIÇÃO", "PREFIXO", "QTD.", "VÍNCULO",
        "DESCRIÇÃO", "PREFIXO", "QTD.", "VÍNCULO"
    };
    private static final String[] WORKED_COLUMNS = {
        "INÍCIO", "FIM", "Nº", "COMP.", "LARG.", "ESP. m",
        "PISTA", "FAIXA", "OS", "ATIVIDADE / SERVIÇO"
    };
    private static final String[] MATERIAL_COLUMNS = {
        "MATERIAL", "QTD.", "UN.", "NF",
        "MATERIAL", "QTD.", "UN.", "NF",
        "MATERIAL", "QTD.", "UN.", "NF"
    };
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm");
    private static final Locale PORTUGUESE = Locale.forLanguageTag("pt-BR");

    private final PDFont regular;
    private final PDFont bold;
    private final PDImageXObject corporateWordmark;
    private final RdoExportTextSanitizer sanitizer =
            new RdoExportTextSanitizer();

    RdoPdfFormRenderer(
            PDFont regular,
            PDFont bold,
            PDImageXObject corporateWordmark
    ) {
        this.regular = regular;
        this.bold = bold;
        this.corporateWordmark = corporateWordmark;
    }

    void render(PDDocument document, RdoExportAggregate aggregate)
            throws IOException {
        requireTwoA4PortraitPages(document);
        validateAllUserText(aggregate);
        renderFront(document, document.getPage(0), aggregate);
        renderBack(document, document.getPage(1), aggregate);
        if (document.getNumberOfPages() != 2) {
            throw new IllegalStateException(
                    "O PDF do RDO deve preservar exatamente duas faces."
            );
        }
    }

    private void requireTwoA4PortraitPages(PDDocument document) {
        if (document.getNumberOfPages() != 2) {
            throw new IllegalArgumentException(
                    "O renderer do RDO exige exatamente duas páginas."
            );
        }
        for (PDPage page : document.getPages()) {
            PDRectangle box = page.getMediaBox();
            if (Math.abs(box.getWidth() - PAGE_WIDTH) > PAGE_BOX_TOLERANCE
                    || Math.abs(box.getHeight() - PAGE_HEIGHT)
                    > PAGE_BOX_TOLERANCE
                    || box.getWidth() >= box.getHeight()) {
                throw new IllegalArgumentException(
                        "O renderer do RDO exige páginas A4 em orientação retrato."
                );
            }
        }
    }

    private void validateAllUserText(RdoExportAggregate aggregate)
            throws IOException {
        RdoResponse rdo = aggregate.rdo();
        validateUserText(aggregate.worksite().name(), false);
        validateUserText(aggregate.worksite().code(), false);
        validateUserText(rdo.numeroRdo(), false);
        validateUserText(rdo.rodovia(), false);
        validateUserText(rdo.diaSemana(), false);
        validateUserText(rdo.turno(), false);
        validateUserText(rdo.kmInicialProgramado(), false);
        validateUserText(rdo.kmFinalProgramado(), false);
        validateUserText(rdo.kmInicialInterditado(), false);
        validateUserText(rdo.kmFinalInterditado(), false);

        for (WorkforceGroup group : aggregate.workforce()) {
            validateUserText(group.role(), false);
        }
        for (RdoResponse.EquipamentoItem item : aggregate.equipment()) {
            validateUserText(item.descricao(), false);
            validateUserText(item.prefixo(), false);
        }
        for (WorkedRow row : aggregate.worked()) {
            validateUserText(row.start(), false);
            validateUserText(row.end(), false);
            validateUserText(row.number(), false);
            validateUserText(row.roadway(), false);
            validateUserText(row.lane(), false);
            validateUserText(row.serviceOrder(), false);
            validateUserText(row.activity(), false);
        }
        for (MaterialRow row : aggregate.materials()) {
            validateUserText(row.description(), false);
            validateUserText(row.unit(), false);
            validateUserText(row.invoice(), false);
        }
        for (RdoResponse.ControleGeometricoItem item : aggregate.geometry()) {
            validateUserText(item.subtrecho(), false);
        }
        validateOriginalObservationSources(aggregate);
        validateUserText(aggregate.observations(), true);
        validateUserText(aggregate.apontadorName(), false);
        validateUserText(rdo.encarregadoObra(), false);
        validateUserText(rdo.fiscalizacaoCampo(), false);
    }

    private void validateOriginalObservationSources(
            RdoExportAggregate aggregate
    ) throws IOException {
        RdoResponse rdo = aggregate.rdo();
        validateUserText(aggregate.previousRdoNumber(), false);
        validateUserText(rdo.observacoes(), true);
        for (RdoResponse.MaoObraItem item : safeItems(rdo.maoObra())) {
            validateUserText(item.observacoes(), true);
        }
        for (RdoResponse.EquipamentoItem item : safeItems(rdo.equipamentos())) {
            validateUserText(item.observacoes(), true);
        }
        for (RdoResponse.MaterialItem item : safeItems(rdo.materiais())) {
            validateUserText(item.observacoes(), true);
        }
        for (RdoResponse.ControleGeometricoItem item
                : safeItems(rdo.controlesGeometricos())) {
            validateUserText(item.observacoes(), true);
        }
        for (RdoResponse.ServicoExecutadoItem item
                : safeItems(rdo.servicosExecutados())) {
            validateUserText(item.observacoes(), true);
        }
    }

    private String validateUserText(String value, boolean allowLineBreaks)
            throws IOException {
        requireSafePdfText(value, allowLineBreaks);
        String sanitized = sanitizer.cellText(value);
        requireSafePdfText(sanitized, allowLineBreaks);
        return sanitized;
    }

    private void requireSafePdfText(String value, boolean allowLineBreaks)
            throws IOException {
        if (value == null) {
            return;
        }
        int offset = 0;
        while (offset < value.length()) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (allowLineBreaks && (codePoint == '\n' || codePoint == '\r')) {
                continue;
            }
            if (codePoint < 0x20 || codePoint > 0xFF
                    || !hasGlyph(regular, codePoint)
                    || !hasGlyph(bold, codePoint)) {
                throw unsafeGlyph();
            }
        }
    }

    private boolean hasGlyph(PDFont font, int codePoint) throws IOException {
        return font instanceof PDType1Font type1Font
                && type1Font.hasGlyph(codePoint);
    }

    private ResponseStatusException unsafeGlyph() {
        return new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                UNSAFE_GLYPH_MESSAGE
        );
    }

    private void renderFront(
            PDDocument document,
            PDPage page,
            RdoExportAggregate aggregate
    ) throws IOException {
        try (PDPageContentStream content =
                new PDPageContentStream(document, page)) {
            prepare(content);
            float y = drawPageHeading(
                    content,
                    "FRENTE",
                    displayedRdoNumber(aggregate.rdo())
            );
            y = drawFrontIdentity(content, y, aggregate);
            y = sectionBar(content, y, "CONDIÇÕES, INTERDIÇÃO E TURNO");
            y = drawConditions(content, y, aggregate.rdo());
            y = sectionBar(content, y, "MÃO DE OBRA");
            y = drawWorkforce(content, y, aggregate.workforce());
            y = sectionBar(content, y, "EQUIPAMENTOS E VEÍCULOS");
            y = drawEquipment(content, y, aggregate.equipment());
            y = sectionBar(content, y, "TRECHOS E SERVIÇOS");
            drawWorked(content, y, aggregate.worked());
        }
    }

    private void renderBack(
            PDDocument document,
            PDPage page,
            RdoExportAggregate aggregate
    ) throws IOException {
        try (PDPageContentStream content =
                new PDPageContentStream(document, page)) {
            prepare(content);
            float y = drawPageHeading(
                    content,
                    "VERSO",
                    displayedRdoNumber(aggregate.rdo())
            );
            y = sectionBar(content, y, "MATERIAIS");
            y = drawMaterials(content, y, aggregate.materials());
            /*
             * A seção de controle geométrico saiu do verso junto com a etapa
             * que a preenchia. Ela era, além disso, duplicata: as mesmas
             * medidas já aparecem na tabela de trecho trabalhado da frente,
             * agora também para os serviços. RDO anterior à remoção continua
             * mostrando o que registrou — só que na tabela da frente, e não
             * duas vezes.
             */
            y = sectionBar(content, y, "OBSERVAÇÕES");
            y = drawObservations(content, y, aggregate.observations());
            y = sectionBar(content, y, "ASSINATURAS");
            drawSignatures(content, y, aggregate);
        }
    }

    private void prepare(PDPageContentStream content) throws IOException {
        content.setLineWidth(HAIRLINE);
        content.setStrokingColor(0f);
        content.setNonStrokingColor(0f);
    }

    private float drawPageHeading(
            PDPageContentStream content,
            String face,
            String rdoNumber
    ) throws IOException {
        float top = PAGE_HEIGHT - PAGE_MARGIN;
        content.drawImage(
                corporateWordmark,
                PAGE_MARGIN,
                top - WORDMARK_HEIGHT,
                WORDMARK_WIDTH,
                WORDMARK_HEIGHT
        );
        drawRightAligned(
                content,
                bold,
                8f,
                PAGE_WIDTH - PAGE_MARGIN,
                top - 12f,
                face + "  |  RDO " + rdoNumber
        );
        drawCentered(
                content,
                bold,
                11f,
                PAGE_MARGIN,
                CONTENT_WIDTH,
                top - 31f,
                "RELATÓRIO DIÁRIO DE OBRA"
        );
        content.moveTo(PAGE_MARGIN, top - 38f);
        content.lineTo(PAGE_WIDTH - PAGE_MARGIN, top - 38f);
        content.stroke();
        return top - 44f;
    }

    private float drawFrontIdentity(
            PDPageContentStream content,
            float top,
            RdoExportAggregate aggregate
    ) throws IOException {
        RdoResponse rdo = aggregate.rdo();
        float[] firstWidths = {260f, 130f, CONTENT_WIDTH - 390f};
        drawRow(
                content,
                PAGE_MARGIN,
                top,
                firstWidths,
                16f,
                new String[] {
                    labelValue("OBRA", user(aggregate.worksite().name())),
                    labelValue("CONTRATO/CÓDIGO", user(aggregate.worksite().code())),
                    labelValue("RDO", displayedRdoNumber(rdo))
                },
                bold,
                7f
        );
        float[] secondWidths = {150f, 90f, 128f, CONTENT_WIDTH - 368f};
        drawRow(
                content,
                PAGE_MARGIN,
                top - 16f,
                secondWidths,
                16f,
                new String[] {
                    labelValue("RODOVIA", user(rdo.rodovia())),
                    labelValue("DATA", date(rdo.dataRdo())),
                    labelValue("DIA", displayedWeekday(rdo)),
                    labelValue("TURNO", user(rdo.turno()))
                },
                regular,
                6.6f
        );
        return top - 35f;
    }

    private float drawConditions(
            PDPageContentStream content,
            float top,
            RdoResponse rdo
    ) throws IOException {
        float[] widths = {
            CONTENT_WIDTH / 4f,
            CONTENT_WIDTH / 4f,
            CONTENT_WIDTH / 4f,
            CONTENT_WIDTH / 4f
        };
        drawRow(
                content,
                PAGE_MARGIN,
                top,
                widths,
                16f,
                new String[] {
                    labelValue("MANHÃ", weather(rdo.condicaoManha())),
                    labelValue("TARDE", weather(rdo.condicaoTarde())),
                    labelValue("NOITE", weather(rdo.condicaoNoite())),
                    labelValue("CHUVA (mm)", decimal(rdo.pluviometriaMm()))
                },
                regular,
                6.4f
        );
        drawRow(
                content,
                PAGE_MARGIN,
                top - 16f,
                widths,
                16f,
                new String[] {
                    labelValue(
                            "PROGRAMADO",
                            range(
                                    user(rdo.kmInicialProgramado()),
                                    user(rdo.kmFinalProgramado())
                            )
                    ),
                    labelValue(
                            "INTERDITADO",
                            range(
                                    user(rdo.kmInicialInterditado()),
                                    user(rdo.kmFinalInterditado())
                            )
                    ),
                    labelValue("INÍCIO", time(rdo.horaInicio())),
                    labelValue("FIM", time(rdo.horaFim()))
                },
                regular,
                6.2f
        );
        return top - 35f;
    }

    private float drawWorkforce(
            PDPageContentStream content,
            float top,
            List<WorkforceGroup> groups
    ) throws IOException {
        float half = CONTENT_WIDTH / 2f;
        float[] widths = {
            half - 88f, 40f, 48f,
            half - 88f, 40f, 48f
        };
        drawRow(
                content,
                PAGE_MARGIN,
                top,
                widths,
                11f,
                WORKFORCE_COLUMNS,
                bold,
                5.4f
        );
        int leftCapacity = 13;
        for (int row = 0; row < leftCapacity; row++) {
            WorkforceGroup left = item(groups, row);
            WorkforceGroup right = item(groups, row + leftCapacity);
            drawRow(
                    content,
                    PAGE_MARGIN,
                    top - 11f - (row * 8f),
                    widths,
                    8f,
                    new String[] {
                        workforceRole(left),
                        workforceQuantity(left, false),
                        workforceQuantity(left, true),
                        workforceRole(right),
                        workforceQuantity(right, false),
                        workforceQuantity(right, true)
                    },
                    regular,
                    5.2f,
                    WORKFORCE_COLUMNS
            );
        }
        return top - 118f;
    }

    private float drawEquipment(
            PDPageContentStream content,
            float top,
            List<RdoResponse.EquipamentoItem> equipment
    ) throws IOException {
        float half = CONTENT_WIDTH / 2f;
        float[] widths = {
            half - 124f, 43f, 31f, 50f,
            half - 124f, 43f, 31f, 50f
        };
        drawRow(
                content,
                PAGE_MARGIN,
                top,
                widths,
                11f,
                EQUIPMENT_COLUMNS,
                bold,
                5.1f
        );
        int leftCapacity = 16;
        for (int row = 0; row < leftCapacity; row++) {
            RdoResponse.EquipamentoItem left = item(equipment, row);
            RdoResponse.EquipamentoItem right =
                    item(equipment, row + leftCapacity);
            drawRow(
                    content,
                    PAGE_MARGIN,
                    top - 11f - (row * 8f),
                    widths,
                    8f,
                    new String[] {
                        equipmentDescription(left),
                        equipmentPrefix(left),
                        equipmentQuantity(left),
                        equipmentOwnership(left),
                        equipmentDescription(right),
                        equipmentPrefix(right),
                        equipmentQuantity(right),
                        equipmentOwnership(right)
                    },
                    regular,
                    5f,
                    EQUIPMENT_COLUMNS
            );
        }
        return top - 142f;
    }

    private void drawWorked(
            PDPageContentStream content,
            float top,
            List<WorkedRow> worked
    ) throws IOException {
        float[] widths = {
            38f, 38f, 25f, 33f, 31f, 31f, 50f, 43f, 56f,
            CONTENT_WIDTH - 345f
        };
        drawRow(
                content,
                PAGE_MARGIN,
                top,
                widths,
                11f,
                WORKED_COLUMNS,
                bold,
                5.2f
        );
        for (int row = 0; row < 21; row++) {
            WorkedRow value = item(worked, row);
            drawRow(
                    content,
                    PAGE_MARGIN,
                    top - 11f - (row * 11f),
                    widths,
                    11f,
                    workedCells(value),
                    regular,
                    5.2f,
                    WORKED_COLUMNS
            );
        }
    }

    private float drawMaterials(
            PDPageContentStream content,
            float top,
            List<MaterialRow> materials
    ) throws IOException {
        float block = CONTENT_WIDTH / 3f;
        /*
         * A coluna da unidade nasceu com 21 pontos, e 21 pontos não comportam
         * unidade escrita por extenso: descontado o respiro da célula, sobram
         * 17,8 pontos, que no piso de legibilidade de 4 pontos valem 4,45 em —
         * menos que "UNIDADE" (4,50) e bem menos que "TONELADA" (5,39). Não
         * era o limite de caracteres que apertava: era a coluna.
         *
         * <p>Os 9 pontos saem de quem tinha folga de sobra, e nenhuma coluna
         * fica no fio: 4 da quantidade, que ainda desenha "1.234.567,89"
         * (5,84 em contra 6,70 disponíveis), e 5 da descrição, que passa a dar
         * 22,08 em — bem mais que o nome de material mais comprido da obra
         * ("CONCRETO USINADO FCK 30" gasta 14,22). A soma do bloco continua
         * exata, então o formulário não se desloca.
         *
         * <p>A nota fiscal veio junto, pela mesma medição: 28 pontos davam
         * 6,20 em, e uma nota escrita como se escreve — com série ou ano,
         * "NF 123456/2026" — gasta 7,4. O número puro cabia; o que a gente
         * digita, não. Ela vai a 36 pontos (8,20 em) com mais 8 tirados da
         * descrição, que mesmo assim fica com 20,08 em, ainda cinco em acima
         * do maior nome de material real.
         */
        float[] widths = {
            block - 96f, 30f, 30f, 36f,
            block - 96f, 30f, 30f, 36f,
            block - 96f, 30f, 30f, 36f
        };
        drawRow(
                content,
                PAGE_MARGIN,
                top,
                widths,
                10f,
                MATERIAL_COLUMNS,
                bold,
                4.8f
        );
        for (int row = 0; row < 10; row++) {
            MaterialRow first = item(materials, row);
            MaterialRow second = item(materials, row + 10);
            MaterialRow third = item(materials, row + 20);
            drawRow(
                    content,
                    PAGE_MARGIN,
                    top - 10f - (row * 9f),
                    widths,
                    9f,
                    new String[] {
                        materialDescription(first),
                        materialQuantity(first),
                        materialUnit(first),
                        materialInvoice(first),
                        materialDescription(second),
                        materialQuantity(second),
                        materialUnit(second),
                        materialInvoice(second),
                        materialDescription(third),
                        materialQuantity(third),
                        materialUnit(third),
                        materialInvoice(third)
                    },
                    regular,
                    4.7f,
                    MATERIAL_COLUMNS
            );
        }
        return top - 103f;
    }

    private float drawObservations(
            PDPageContentStream content,
            float top,
            String observations
    ) throws IOException {
        float height = 100f;
        content.addRect(PAGE_MARGIN, top - height, CONTENT_WIDTH, height);
        content.stroke();
        String safe = observationText(observations)
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        List<String> lines = wrapObservation(
                safe,
                CONTENT_WIDTH - 8f,
                OBSERVATION_FONT_SIZE
        );
        int capacity = (int) ((height - 8f) / OBSERVATION_LINE_HEIGHT);
        if (lines.size() > capacity) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "O conteúdo de observações gerais não permanece legível "
                            + "no RDO (área fixa de observações do PDF); "
                            + "nenhum conteúdo foi truncado."
            );
        }
        float baseline = top - 10f;
        for (String line : lines) {
            drawRawText(
                    content,
                    regular,
                    OBSERVATION_FONT_SIZE,
                    PAGE_MARGIN + 4f,
                    baseline,
                    line
            );
            baseline -= OBSERVATION_LINE_HEIGHT;
        }
        return top - height - 3f;
    }

    private void drawSignatures(
            PDPageContentStream content,
            float top,
            RdoExportAggregate aggregate
    ) throws IOException {
        float gap = 10f;
        float signatureWidth = (CONTENT_WIDTH - (2 * gap)) / 3f;
        float lineY = top - 37f;
        for (int index = 0; index < 3; index++) {
            float x = PAGE_MARGIN + (index * (signatureWidth + gap));
            content.moveTo(x, lineY);
            content.lineTo(x + signatureWidth, lineY);
            content.stroke();
        }
        String[] names = {
            user(aggregate.apontadorName()),
            user(aggregate.rdo().encarregadoObra()),
            user(aggregate.rdo().fiscalizacaoCampo())
        };
        String[] roles = {"APONTADOR", "ENCARREGADO", "FISCALIZAÇÃO"};
        for (int index = 0; index < names.length; index++) {
            float x = PAGE_MARGIN + (index * (signatureWidth + gap));
            drawFittedRaw(
                    content,
                    regular,
                    6.5f,
                    x,
                    lineY - 10f,
                    signatureWidth,
                    names[index]
            );
            drawCentered(
                    content,
                    bold,
                    5.8f,
                    x,
                    signatureWidth,
                    lineY - 21f,
                    roles[index]
            );
        }
    }

    private float sectionBar(
            PDPageContentStream content,
            float top,
            String title
    ) throws IOException {
        float bottom = top - SECTION_HEIGHT;
        content.setNonStrokingColor(SECTION_GRAY / 255f);
        content.addRect(PAGE_MARGIN, bottom, CONTENT_WIDTH, SECTION_HEIGHT);
        content.fill();
        content.setNonStrokingColor(0f);
        content.addRect(PAGE_MARGIN, bottom, CONTENT_WIDTH, SECTION_HEIGHT);
        content.stroke();
        drawRawText(
                content,
                bold,
                7f,
                PAGE_MARGIN + 4f,
                bottom + 4f,
                title
        );
        return bottom - 2f;
    }

    private void drawRow(
            PDPageContentStream content,
            float x,
            float top,
            float[] widths,
            float height,
            String[] values,
            PDFont font,
            float maximumFontSize
    ) throws IOException {
        drawRow(content, x, top, widths, height, values, font, maximumFontSize, null);
    }

    /**
     * Desenha a linha sabendo o nome de cada coluna, para poder dizer qual
     * delas não coube.
     *
     * <p>Os rótulos são os mesmos que já vão no cabeçalho da tabela — quem
     * recebe a recusa lê a mesma palavra que enxerga na folha.
     */
    private void drawRow(
            PDPageContentStream content,
            float x,
            float top,
            float[] widths,
            float height,
            String[] values,
            PDFont font,
            float maximumFontSize,
            String[] columnLabels
    ) throws IOException {
        if (widths.length != values.length) {
            throw new IllegalArgumentException("Células e larguras incompatíveis.");
        }
        float cursor = x;
        for (int index = 0; index < widths.length; index++) {
            content.addRect(cursor, top - height, widths[index], height);
            content.stroke();
            drawFittedRaw(
                    content,
                    font,
                    maximumFontSize,
                    cursor + CELL_PADDING,
                    top - height + 2f,
                    widths[index] - (2 * CELL_PADDING),
                    values[index],
                    columnLabels == null || index >= columnLabels.length
                            ? null
                            : columnLabels[index]
            );
            cursor += widths[index];
        }
    }

    private void drawFittedRaw(
            PDPageContentStream content,
            PDFont font,
            float maximumFontSize,
            float x,
            float baseline,
            float availableWidth,
            String value
    ) throws IOException {
        drawFittedRaw(
                content, font, maximumFontSize, x, baseline, availableWidth,
                value, null
        );
    }

    private void drawFittedRaw(
            PDPageContentStream content,
            PDFont font,
            float maximumFontSize,
            float x,
            float baseline,
            float availableWidth,
            String value,
            String columnLabel
    ) throws IOException {
        if (value == null || value.isBlank()) {
            return;
        }
        float units = font.getStringWidth(value) / 1000f;
        float fontSize = units <= 0f
                ? maximumFontSize
                : Math.min(maximumFontSize, availableWidth / units);
        if (fontSize < MIN_READABLE_FONT_SIZE) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    unreadableMessage(columnLabel, availableWidth, font, value)
            );
        }
        drawRawText(content, font, fontSize, x, baseline, value);
    }

    /**
     * A recusa que diz o que fazer.
     *
     * <p>A mensagem antiga dizia só que "o conteúdo do RDO" não cabia, e quem
     * lia ficava sem saber qual dos vinte e poucos campos encurtar — num
     * documento com trinta linhas de trecho, procurar o culpado à mão é o
     * trabalho todo. Quando a coluna se identifica, a frase passa a nomear a
     * coluna e a dizer quantos caracteres daquele conteúdo cabem, que é a
     * única informação capaz de resolver o problema de quem está no campo.
     *
     * <p>O conteúdo em si não entra na mensagem: ele é dado do RDO e a recusa
     * viaja por log e por tela alheia.
     */
    private String unreadableMessage(
            String columnLabel,
            float availableWidth,
            PDFont font,
            String value
    ) throws IOException {
        if (columnLabel == null || columnLabel.isBlank()) {
            return UNREADABLE_FITTED_TEXT_MESSAGE;
        }
        int fits = fittingCharacters(font, value, availableWidth);
        return "O conteúdo da coluna " + columnLabel
                + " não permanece legível na célula fixa do PDF (cabem "
                + fits + " caracteres deste conteúdo, e foram informados "
                + value.codePointCount(0, value.length())
                + "); nenhum conteúdo foi truncado.";
    }

    /** Quantos caracteres do próprio conteúdo cabem, medidos e não estimados. */
    private int fittingCharacters(PDFont font, String value, float availableWidth)
            throws IOException {
        int fits = 0;
        for (int index = 1; index <= value.length(); index++) {
            if (value.charAt(index - 1) >= Character.MIN_LOW_SURROGATE
                    && value.charAt(index - 1) <= Character.MAX_LOW_SURROGATE) {
                continue;
            }
            float units = font.getStringWidth(value.substring(0, index)) / 1000f;
            if (units > 0f && availableWidth / units < MIN_READABLE_FONT_SIZE) {
                break;
            }
            fits = value.codePointCount(0, index);
        }
        return fits;
    }

    private void drawRawText(
            PDPageContentStream content,
            PDFont font,
            float fontSize,
            float x,
            float baseline,
            String value
    ) throws IOException {
        if (value == null || value.isEmpty()) {
            return;
        }
        content.beginText();
        content.setFont(font, fontSize);
        content.newLineAtOffset(x, baseline);
        content.showText(value);
        content.endText();
    }

    private void drawCentered(
            PDPageContentStream content,
            PDFont font,
            float fontSize,
            float x,
            float width,
            float baseline,
            String value
    ) throws IOException {
        float textWidth = font.getStringWidth(value) / 1000f * fontSize;
        drawRawText(
                content,
                font,
                fontSize,
                x + Math.max(0f, (width - textWidth) / 2f),
                baseline,
                value
        );
    }

    private void drawRightAligned(
            PDPageContentStream content,
            PDFont font,
            float fontSize,
            float right,
            float baseline,
            String value
    ) throws IOException {
        float textWidth = font.getStringWidth(value) / 1000f * fontSize;
        drawRawText(
                content,
                font,
                fontSize,
                right - textWidth,
                baseline,
                value
        );
    }

    private List<String> wrapObservation(
            String value,
            float availableWidth,
            float fontSize
    ) throws IOException {
        if (value.isBlank()) {
            return List.of();
        }
        List<String> wrapped = new ArrayList<>();
        for (String inputLine : value.split("\n", -1)) {
            if (inputLine.isEmpty()) {
                wrapped.add("");
                continue;
            }
            String remaining = inputLine;
            while (!remaining.isEmpty()) {
                if (textWidth(remaining, regular, fontSize) <= availableWidth) {
                    wrapped.add(remaining);
                    break;
                }
                int fittingEnd = largestFittingEnd(
                        remaining,
                        availableWidth,
                        fontSize
                );
                int preferredEnd = remaining.lastIndexOf(' ', fittingEnd);
                if (preferredEnd > 0) {
                    fittingEnd = preferredEnd;
                }
                wrapped.add(remaining.substring(0, fittingEnd).stripTrailing());
                remaining = remaining.substring(fittingEnd).stripLeading();
            }
        }
        return wrapped;
    }

    private int largestFittingEnd(
            String value,
            float availableWidth,
            float fontSize
    ) throws IOException {
        int end = 0;
        int candidate = 0;
        while (candidate < value.length()) {
            candidate += Character.charCount(value.codePointAt(candidate));
            if (textWidth(
                    value.substring(0, candidate),
                    regular,
                    fontSize
            ) > availableWidth) {
                break;
            }
            end = candidate;
        }
        return end == 0
                ? Character.charCount(value.codePointAt(0))
                : end;
    }

    private float textWidth(String value, PDFont font, float fontSize)
            throws IOException {
        return font.getStringWidth(value) / 1000f * fontSize;
    }

    private String displayedRdoNumber(RdoResponse rdo) throws IOException {
        if (rdo.numeroRdo() == null || rdo.numeroRdo().isBlank()) {
            return "NÃO INFORMADO";
        }
        return user(rdo.numeroRdo());
    }

    private String displayedWeekday(RdoResponse rdo) throws IOException {
        if (rdo.diaSemana() != null && !rdo.diaSemana().isBlank()) {
            return user(rdo.diaSemana());
        }
        LocalDate date = rdo.dataRdo();
        return date == null
                ? ""
                : date.getDayOfWeek().getDisplayName(TextStyle.FULL, PORTUGUESE);
    }

    private String user(String value) throws IOException {
        return validateUserText(value, false);
    }

    private String observationText(String value) throws IOException {
        return validateUserText(value, true);
    }

    private String labelValue(String label, String value) {
        return value == null || value.isBlank()
                ? label + ":"
                : label + ": " + value;
    }

    private String range(String start, String end) {
        if (start.isBlank() && end.isBlank()) {
            return "";
        }
        if (start.isBlank()) {
            return end;
        }
        if (end.isBlank()) {
            return start;
        }
        return start + " a " + end;
    }

    private String weather(String value) {
        return switch (normalize(value)) {
            case "BOM" -> "Bom";
            case "NUBLADO" -> "Nublado";
            case "CHUVA" -> "Chuva";
            case "IMPOSSIBILITADO" -> "Impossibilitado";
            case "NAO_APLICAVEL" -> "N/A";
            default -> "";
        };
    }

    private String normalize(String value) {
        return value == null
                ? ""
                : value.trim().toUpperCase(Locale.ROOT);
    }

    private String date(LocalDate value) {
        return value == null ? "" : DATE_FORMAT.format(value);
    }

    private String time(LocalTime value) {
        return value == null ? "" : TIME_FORMAT.format(value);
    }

    private String decimal(BigDecimal value) {
        return value == null
                ? ""
                : value.stripTrailingZeros().toPlainString();
    }

    private String workforceRole(WorkforceGroup value) throws IOException {
        return value == null ? "" : user(value.role());
    }

    private String workforceQuantity(
            WorkforceGroup value,
            boolean subcontracted
    ) {
        return value == null || value.subcontracted() != subcontracted
                ? ""
                : decimal(value.quantity());
    }

    private String equipmentDescription(RdoResponse.EquipamentoItem value)
            throws IOException {
        return value == null ? "" : user(value.descricao());
    }

    private String equipmentPrefix(RdoResponse.EquipamentoItem value)
            throws IOException {
        return value == null ? "" : user(value.prefixo());
    }

    private String equipmentQuantity(RdoResponse.EquipamentoItem value) {
        return value == null ? "" : decimal(value.quantidade());
    }

    private String equipmentOwnership(RdoResponse.EquipamentoItem value) {
        if (value == null) {
            return "";
        }
        return "PROPRIO".equals(normalize(value.tipoVinculo()))
                ? "PRÓPRIO"
                : "TERCEIRO";
    }

    private String[] workedCells(WorkedRow value) throws IOException {
        if (value == null) {
            return new String[10];
        }
        return new String[] {
            user(value.start()),
            user(value.end()),
            user(value.number()),
            decimal(value.length()),
            decimal(value.width()),
            decimal(value.thicknessMeters()),
            user(value.roadway()),
            user(value.lane()),
            user(value.serviceOrder()),
            user(value.activity())
        };
    }

    private String materialDescription(MaterialRow value) throws IOException {
        return value == null ? "" : user(value.description());
    }

    private String materialQuantity(MaterialRow value) {
        return value == null ? "" : decimal(value.quantity());
    }

    private String materialUnit(MaterialRow value) throws IOException {
        return value == null ? "" : user(value.unit());
    }

    private String materialInvoice(MaterialRow value) throws IOException {
        return value == null ? "" : user(value.invoice());
    }

    private <T> T item(List<T> values, int index) {
        return index >= 0 && index < values.size() ? values.get(index) : null;
    }

    private <T> List<T> safeItems(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static float mm(float millimetres) {
        return millimetres * POINTS_PER_MILLIMETRE;
    }
}
