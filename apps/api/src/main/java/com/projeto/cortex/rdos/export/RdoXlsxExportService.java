package com.projeto.cortex.rdos.export;

import com.projeto.cortex.rdos.RdoResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackageRelationship;
import org.apache.poi.openxml4j.opc.PackageRelationshipTypes;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.xmlbeans.XmlCursor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
public class RdoXlsxExportService {

    static final String TEMPLATE_PATH = "rdo/export/RDO-v1.xlsx";
    static final String TEMPLATE_SHA256 =
            "2a97db997d939b738146bad7c39428e38e159a6160f23afdf3297500fb2b8f87";
    private static final String FRONT_SHEET = "v.1 RDO frente";
    private static final String BACK_SHEET = "v.1 RDO verso";
    private static final String SOURCE_PATH_NAMESPACE =
            "http://schemas.microsoft.com/office/spreadsheetml/2010/11/ac";
    private final RdoExportAggregateFactory aggregateFactory;
    private final RdoExportTextSanitizer sanitizer =
            new RdoExportTextSanitizer();

    public RdoXlsxExportService(RdoExportAggregateFactory aggregateFactory) {
        this.aggregateFactory = aggregateFactory;
    }

    public RdoExportFile export(String rdoId) {
        RdoExportAggregate aggregate = aggregateFactory.load(rdoId);
        byte[] template = loadVerifiedTemplate();
        try (Workbook genericWorkbook = WorkbookFactory.create(
                new ByteArrayInputStream(template)
        )) {
            if (!(genericWorkbook instanceof XSSFWorkbook workbook)) {
                throw new IllegalStateException(
                        "O template versionado do RDO não é um arquivo XLSX."
                );
            }
            validateTemplateContract(workbook);
            removeExecutableContent(workbook);
            populate(workbook, aggregate);
            stripPackageMetadata(workbook);
            assertNoExecutableContent(workbook);

            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                workbook.write(output);
                return new RdoExportFile(
                        output.toByteArray(),
                        sanitizer.filename(
                                aggregate.rdo().numeroRdo(),
                                aggregate.rdo().id()
                        )
                );
            }
        } catch (IOException | InvalidFormatException exception) {
            throw new IllegalStateException(
                    "Não foi possível gerar o XLSX do RDO.",
                    exception
            );
        }
    }

    private byte[] loadVerifiedTemplate() {
        try {
            byte[] bytes = new ClassPathResource(TEMPLATE_PATH)
                    .getInputStream()
                    .readAllBytes();
            String actualHash = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes)
            );
            if (!TEMPLATE_SHA256.equals(actualHash)) {
                throw new IllegalStateException(
                        "O template RDO v1 divergiu do contrato revisado."
                );
            }
            return bytes;
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "Não foi possível carregar o template versionado do RDO.",
                    exception
            );
        }
    }

    private void validateTemplateContract(XSSFWorkbook workbook) {
        if (workbook.getNumberOfSheets() != 2
                || !FRONT_SHEET.equals(workbook.getSheetName(0))
                || !BACK_SHEET.equals(workbook.getSheetName(1))) {
            throw new IllegalStateException(
                    "O template RDO v1 não preserva as duas faces esperadas."
            );
        }
        if (workbook.isMacroEnabled()
                || !workbook.getExternalLinksTable().isEmpty()) {
            throw new IllegalStateException(
                    "O template RDO v1 contém macro ou link externo não permitido."
            );
        }
    }

    private void removeExecutableContent(XSSFWorkbook workbook) {
        for (Sheet sheet : workbook) {
            for (Row row : sheet) {
                for (Cell cell : row) {
                    cell.setHyperlink(null);
                    if (cell.getCellType() == CellType.FORMULA) {
                        cell.setBlank();
                    }
                }
            }
        }
    }

    private void stripPackageMetadata(XSSFWorkbook workbook)
            throws InvalidFormatException {
        POIXMLProperties properties = workbook.getProperties();
        POIXMLProperties.CoreProperties core = properties.getCoreProperties();
        core.setCreator("");
        core.setLastModifiedByUser("");
        core.setTitle("");
        core.setSubjectProperty("");
        core.setDescription("");
        core.setKeywords("");
        core.setCategory("");
        core.setContentStatus("");
        core.setContentType("");
        core.setIdentifier("");
        core.setVersion("");
        core.setRevision("");
        core.setCreated(Optional.empty());
        core.setModified(Optional.empty());
        core.setLastPrinted(Optional.empty());

        POIXMLProperties.ExtendedProperties extended =
                properties.getExtendedProperties();
        extended.setTemplate("");
        extended.setManager("");
        extended.setCompany("");
        extended.setHyperlinkBase("");

        try (XmlCursor cursor = workbook.getCTWorkbook().newCursor()) {
            while (cursor.hasNextToken()) {
                cursor.toNextToken();
                if (cursor.isStart()
                        && "absPath".equals(cursor.getName().getLocalPart())
                        && SOURCE_PATH_NAMESPACE.equals(
                                cursor.getName().getNamespaceURI()
                        )) {
                    cursor.removeXml();
                }
            }
        }

        var custom = properties.getCustomProperties()
                .getUnderlyingProperties();
        while (custom.sizeOfPropertyArray() > 0) {
            custom.removeProperty(0);
        }

        List<String> customXmlRelationshipIds = new ArrayList<>();
        for (PackageRelationship relationship : workbook.getPackagePart()
                .getRelationshipsByType(PackageRelationshipTypes.CUSTOM_XML)) {
            customXmlRelationshipIds.add(relationship.getId());
        }
        for (String relationshipId : customXmlRelationshipIds) {
            workbook.getPackagePart().removeRelationship(relationshipId);
        }

        List<PackagePart> customXmlParts = workbook.getPackage()
                .getParts()
                .stream()
                .filter(part -> part.getPartName()
                        .getName()
                        .startsWith("/customXml/"))
                .toList();
        for (PackagePart part : customXmlParts) {
            workbook.getPackage().removePart(part);
        }
    }

    private void assertNoExecutableContent(XSSFWorkbook workbook) {
        if (workbook.isMacroEnabled()
                || !workbook.getExternalLinksTable().isEmpty()) {
            throw new IllegalStateException(
                    "A exportação do RDO não permite macros ou links externos."
            );
        }
        for (Sheet sheet : workbook) {
            for (Row row : sheet) {
                for (Cell cell : row) {
                    if (cell.getCellType() == CellType.FORMULA
                            || cell.getHyperlink() != null) {
                        throw new IllegalStateException(
                                "A exportação do RDO não permite fórmulas ou links."
                        );
                    }
                }
            }
        }
    }

    private void populate(
            XSSFWorkbook workbook,
            RdoExportAggregate aggregate
    ) {
        RdoResponse rdo = aggregate.rdo();
        Sheet front = workbook.getSheet(FRONT_SHEET);
        Sheet back = workbook.getSheet(BACK_SHEET);
        workbook.setPrintArea(0, 0, 35, 0, 79);
        workbook.setPrintArea(1, 0, 33, 1, 69);
        clearOperationalFixtures(front, back);
        populateHeader(workbook, front, back, rdo, aggregate.worksite());
        populateConditions(workbook, front, rdo);
        populateWorkforce(workbook, front, aggregate.workforce());
        populateEquipment(workbook, front, aggregate.equipment());
        populateWorkedSection(workbook, front, aggregate.worked());
        populateMaterials(workbook, back, aggregate.materials());
        populateGeometry(workbook, back, aggregate.geometry());
        populateObservationsAndSignatures(
                workbook,
                back,
                rdo,
                aggregate.observations(),
                aggregate.apontadorName()
        );
    }

    private void clearOperationalFixtures(Sheet front, Sheet back) {
        clear(front, "B6:AJ6");
        clear(front, "D10:P12");
        clear(front, "Q10:AJ12");
        clear(front, "B16:AJ28");
        clear(front, "M30:AA31");
        clear(front, "B36:Q51");
        clear(front, "T36:AJ51");
        clear(front, "M53:AA54");
        clear(front, "B60:AJ80");
        clear(back, "AB4:AD4");
        clear(back, "B8:J17");
        clear(back, "L8:T17");
        clear(back, "V8:AD17");
        clear(back, "B26:AD61");
        clear(back, "B63:AD69");
    }

    private void populateHeader(
            Workbook workbook,
            Sheet front,
            Sheet back,
            RdoResponse rdo,
            RdoExportWorksiteReader.Worksite worksite
    ) {
        mergeIfMissing(front, "B6:P6");
        mergeIfMissing(front, "Q6:U6");
        mergeIfMissing(front, "V6:Z6");
        mergeIfMissing(front, "AA6:AE6");
        mergeIfMissing(front, "AF6:AJ6");
        mergeIfMissing(back, "AB4:AD4");
        String traceableRdo = firstNonBlank(rdo.numeroRdo(), rdo.id());
        text(front, "B1", "RELATÓRIO DIÁRIO DE OBRA (RDO) — " + traceableRdo);
        text(back, "B2", "RELATÓRIO DIÁRIO DE OBRA (RDO)");
        text(front, "B6", worksite.name());
        text(front, "Q6", worksite.code());
        text(front, "V6", rdo.rodovia());
        date(workbook, front, "AA6", rdo.dataRdo());
        text(front, "AF6", firstNonBlank(rdo.diaSemana(), weekday(rdo.dataRdo())));
        date(workbook, back, "AB4", rdo.dataRdo());
    }

    private void populateConditions(
            Workbook workbook,
            Sheet front,
            RdoResponse rdo
    ) {
        condition(front, 10, rdo.condicaoManha());
        condition(front, 11, rdo.condicaoTarde());
        condition(front, 12, rdo.condicaoNoite());
        number(workbook, front, "M10", rdo.pluviometriaMm());
        text(front, "Q10", rdo.kmInicialProgramado());
        text(front, "Q12", rdo.kmFinalProgramado());
        text(front, "V10", rdo.kmInicialInterditado());
        text(front, "V12", rdo.kmFinalInterditado());

        boolean nighttime = normalize(rdo.turno()).contains("NOTURN");
        String start = nighttime ? "AF10" : "AA10";
        String end = nighttime ? "AF12" : "AA12";
        time(workbook, front, start, rdo.horaInicio());
        time(workbook, front, end, rdo.horaFim());
    }

    private void condition(Sheet sheet, int oneBasedRow, String condition) {
        String normalized = normalize(condition);
        if (normalized.isBlank()) {
            return;
        }
        String column = switch (normalized) {
            case "BOM" -> "D";
            case "CHUVA" -> "G";
            case "IMPOSSIBILITADO" -> "J";
            case "NAO_APLICAVEL", "NUBLADO" -> null;
            default -> throw new IllegalStateException(
                    "A condição climática deveria ter sido validada."
            );
        };
        if (column == null) {
            return;
        }
        text(sheet, column + oneBasedRow, "X");
    }

    private void populateWorkforce(
            Workbook workbook,
            Sheet sheet,
            List<WorkforceGroup> groups
    ) {
        BigDecimal ownTotal = BigDecimal.ZERO;
        BigDecimal subcontractedTotal = BigDecimal.ZERO;
        for (int index = 0; index < groups.size(); index++) {
            WorkforceGroup group = groups.get(index);
            boolean right = index % 2 == 1;
            int row = 16 + index / 2;
            text(sheet, (right ? "M" : "B") + row, group.role());
            String ownCell = (right ? "R" : "G") + row;
            String subcontractedCell = (right ? "U" : "J") + row;
            if (group.subcontracted()) {
                number(workbook, sheet, subcontractedCell, group.quantity());
                subcontractedTotal = subcontractedTotal.add(group.quantity());
            } else {
                number(workbook, sheet, ownCell, group.quantity());
                ownTotal = ownTotal.add(group.quantity());
            }
        }
        number(workbook, sheet, "M30", ownTotal);
        number(workbook, sheet, "M31", subcontractedTotal);
    }

    private void populateEquipment(
            Workbook workbook,
            Sheet sheet,
            List<RdoResponse.EquipamentoItem> equipment
    ) {
        BigDecimal ownTotal = BigDecimal.ZERO;
        BigDecimal subcontractedTotal = BigDecimal.ZERO;
        for (int index = 0; index < equipment.size(); index++) {
            RdoResponse.EquipamentoItem item = equipment.get(index);
            boolean right = index % 2 == 1;
            int row = 36 + index / 2;
            text(sheet, (right ? "T" : "B") + row, item.descricao());
            text(sheet, (right ? "AH" : "O") + row, item.prefixo());
            boolean subcontracted =
                    !"PROPRIO".equals(normalize(item.tipoVinculo()));
            String quantityCell = subcontracted
                    ? (right ? "AE" : "L") + row
                    : (right ? "AB" : "I") + row;
            BigDecimal quantity = nonNull(item.quantidade());
            number(workbook, sheet, quantityCell, quantity);
            if (subcontracted) {
                subcontractedTotal = subcontractedTotal.add(quantity);
            } else {
                ownTotal = ownTotal.add(quantity);
            }
        }
        number(workbook, sheet, "M53", ownTotal);
        number(workbook, sheet, "M54", subcontractedTotal);
    }

    private void populateWorkedSection(
            Workbook workbook,
            Sheet sheet,
            List<WorkedRow> rows
    ) {
        for (int index = 0; index < rows.size(); index++) {
            WorkedRow value = rows.get(index);
            int row = 60 + index;
            mergeIfMissing(sheet, "B" + row + ":D" + row);
            mergeIfMissing(sheet, "E" + row + ":G" + row);
            mergeIfMissing(sheet, "H" + row + ":I" + row);
            mergeIfMissing(sheet, "J" + row + ":K" + row);
            mergeIfMissing(sheet, "L" + row + ":M" + row);
            mergeIfMissing(sheet, "N" + row + ":O" + row);
            mergeIfMissing(sheet, "P" + row + ":Q" + row);
            mergeIfMissing(sheet, "R" + row + ":S" + row);
            mergeIfMissing(sheet, "T" + row + ":W" + row);
            mergeIfMissing(sheet, "X" + row + ":AJ" + row);
            text(sheet, "B" + row, value.start());
            text(sheet, "E" + row, value.end());
            text(sheet, "H" + row, value.number());
            number(workbook, sheet, "J" + row, value.length());
            number(workbook, sheet, "L" + row, value.width());
            number(workbook, sheet, "N" + row, centimetersToMeters(value.thicknessCm()));
            text(sheet, "P" + row, value.roadway());
            text(sheet, "R" + row, value.lane());
            text(sheet, "T" + row, value.serviceOrder());
            text(sheet, "X" + row, value.activity());
        }
    }

    private void populateMaterials(
            Workbook workbook,
            Sheet sheet,
            List<MaterialRow> rows
    ) {
        for (int index = 0; index < rows.size(); index++) {
            int block = index / 10;
            int row = 8 + index % 10;
            String descriptionColumn = switch (block) {
                case 0 -> "B";
                case 1 -> "L";
                case 2 -> "V";
                default -> throw new IllegalStateException("Bloco de material inválido.");
            };
            String quantityColumn = switch (block) {
                case 0 -> "E";
                case 1 -> "O";
                case 2 -> "Y";
                default -> throw new IllegalStateException("Bloco de material inválido.");
            };
            String unitColumn = switch (block) {
                case 0 -> "G";
                case 1 -> "Q";
                case 2 -> "AA";
                default -> throw new IllegalStateException("Bloco de material inválido.");
            };
            String invoiceColumn = switch (block) {
                case 0 -> "H";
                case 1 -> "R";
                case 2 -> "AB";
                default -> throw new IllegalStateException("Bloco de material inválido.");
            };
            MaterialRow value = rows.get(index);
            String descriptionEndColumn = switch (block) {
                case 0 -> "D";
                case 1 -> "N";
                case 2 -> "X";
                default -> throw new IllegalStateException("Bloco de material inválido.");
            };
            String quantityEndColumn = switch (block) {
                case 0 -> "F";
                case 1 -> "P";
                case 2 -> "Z";
                default -> throw new IllegalStateException("Bloco de material inválido.");
            };
            String invoiceEndColumn = switch (block) {
                case 0 -> "J";
                case 1 -> "T";
                case 2 -> "AD";
                default -> throw new IllegalStateException("Bloco de material inválido.");
            };
            mergeIfMissing(
                    sheet,
                    descriptionColumn + row + ":" + descriptionEndColumn + row
            );
            mergeIfMissing(
                    sheet,
                    quantityColumn + row + ":" + quantityEndColumn + row
            );
            mergeIfMissing(
                    sheet,
                    invoiceColumn + row + ":" + invoiceEndColumn + row
            );
            text(sheet, descriptionColumn + row, value.description());
            number(workbook, sheet, quantityColumn + row, value.quantity());
            text(sheet, unitColumn + row, value.unit());
            text(sheet, invoiceColumn + row, value.invoice());
        }
    }

    private void populateGeometry(
            Workbook workbook,
            Sheet sheet,
            List<RdoResponse.ControleGeometricoItem> controls
    ) {
        for (int index = 0; index < controls.size(); index++) {
            RdoResponse.ControleGeometricoItem value = controls.get(index);
            int row = 26 + index;
            mergeIfMissing(sheet, "B" + row + ":E" + row);
            mergeIfMissing(sheet, "F" + row + ":H" + row);
            mergeIfMissing(sheet, "I" + row + ":K" + row);
            mergeIfMissing(sheet, "L" + row + ":N" + row);
            mergeIfMissing(sheet, "O" + row + ":Q" + row);
            mergeIfMissing(sheet, "R" + row + ":T" + row);
            mergeIfMissing(sheet, "U" + row + ":W" + row);
            mergeIfMissing(sheet, "X" + row + ":Z" + row);
            mergeIfMissing(sheet, "AA" + row + ":AD" + row);
            text(sheet, "B" + row, value.subtrecho());
            number(workbook, sheet, "F" + row, value.comprimentoM());
            number(workbook, sheet, "I" + row, value.larguraM());
            number(workbook, sheet, "L" + row, centimetersToMeters(value.espessura1Cm()));
            number(workbook, sheet, "O" + row, centimetersToMeters(value.espessura2Cm()));
            number(workbook, sheet, "R" + row, centimetersToMeters(value.espessura3Cm()));
            number(workbook, sheet, "U" + row, centimetersToMeters(value.espessuraMediaCm()));
            number(workbook, sheet, "X" + row, value.volumeM3());
            number(workbook, sheet, "AA" + row, value.massaTonelada());
        }
    }

    private void populateObservationsAndSignatures(
            Workbook workbook,
            Sheet sheet,
            RdoResponse rdo,
            String aggregateObservations,
            String apontadorName
    ) {
        mergeIfMissing(sheet, "B63:AD68");
        Cell observations = cell(sheet, "B63");
        observations.setCellValue(safeText(aggregateObservations));
        CellStyle style = workbook.createCellStyle();
        style.cloneStyleFrom(observations.getCellStyle());
        style.setWrapText(true);
        style.setShrinkToFit(false);
        style.setVerticalAlignment(
                org.apache.poi.ss.usermodel.VerticalAlignment.TOP
        );
        observations.setCellStyle(style);

        mergeIfMissing(sheet, "B69:J69");
        mergeIfMissing(sheet, "L69:T69");
        mergeIfMissing(sheet, "V69:AD69");
        text(sheet, "B69", apontadorName);
        text(sheet, "L69", rdo.encarregadoObra());
        text(sheet, "V69", rdo.fiscalizacaoCampo());
    }

    private BigDecimal centimetersToMeters(BigDecimal centimeters) {
        return centimeters == null
                ? null
                : centimeters.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)
                        .stripTrailingZeros();
    }

    private void clear(Sheet sheet, String rangeAddress) {
        CellRangeAddress range = CellRangeAddress.valueOf(rangeAddress);
        for (int rowIndex = range.getFirstRow(); rowIndex <= range.getLastRow(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            for (int column = range.getFirstColumn();
                    column <= range.getLastColumn(); column++) {
                Cell cell = row.getCell(column);
                if (cell != null) {
                    cell.setHyperlink(null);
                    cell.setBlank();
                }
            }
        }
    }

    private void text(Sheet sheet, String address, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        Cell target = cell(sheet, address);
        target.setCellValue(safeText(value));
        CellStyle style = sheet.getWorkbook().createCellStyle();
        style.cloneStyleFrom(target.getCellStyle());
        style.setShrinkToFit(true);
        style.setWrapText(false);
        target.setCellStyle(style);
    }

    private void number(
            Workbook workbook,
            Sheet sheet,
            String address,
            BigDecimal value
    ) {
        if (value == null) {
            return;
        }
        Cell target = cell(sheet, address);
        target.setCellValue(value.doubleValue());
        setNumberFormat(workbook, target, "0.##");
    }

    private void date(
            Workbook workbook,
            Sheet sheet,
            String address,
            LocalDate value
    ) {
        if (value == null) {
            return;
        }
        Cell target = cell(sheet, address);
        target.setCellValue(value.atStartOfDay());
        setNumberFormat(workbook, target, "dd/mm/yy");
    }

    private void time(
            Workbook workbook,
            Sheet sheet,
            String address,
            LocalTime value
    ) {
        if (value == null) {
            return;
        }
        Cell target = cell(sheet, address);
        target.setCellValue(value.toSecondOfDay() / 86_400d);
        setNumberFormat(workbook, target, "hh:mm");
    }

    private void setNumberFormat(Workbook workbook, Cell target, String format) {
        CellStyle style = workbook.createCellStyle();
        style.cloneStyleFrom(target.getCellStyle());
        style.setDataFormat(workbook.createDataFormat().getFormat(format));
        target.setCellStyle(style);
    }

    private Cell cell(Sheet sheet, String address) {
        CellRangeAddress range = CellRangeAddress.valueOf(address);
        Row row = sheet.getRow(range.getFirstRow());
        if (row == null) {
            row = sheet.createRow(range.getFirstRow());
        }
        Cell cell = row.getCell(range.getFirstColumn());
        return cell == null ? row.createCell(range.getFirstColumn()) : cell;
    }

    private void mergeIfMissing(Sheet sheet, String address) {
        CellRangeAddress expected = CellRangeAddress.valueOf(address);
        boolean exists = sheet.getMergedRegions().stream().anyMatch(expected::equals);
        if (!exists) {
            sheet.addMergedRegion(expected);
        }
    }

    private String safeText(String value) {
        return sanitizer.cellText(value);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String weekday(LocalDate date) {
        return date == null
                ? null
                : date.getDayOfWeek().getDisplayName(
                        java.time.format.TextStyle.FULL,
                        Locale.forLanguageTag("pt-BR")
                );
    }

    private BigDecimal nonNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

}
