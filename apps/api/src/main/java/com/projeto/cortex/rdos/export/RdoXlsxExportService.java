package com.projeto.cortex.rdos.export;

import com.projeto.cortex.rdos.RdoQueryService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RdoXlsxExportService {

    static final String TEMPLATE_PATH = "rdo/export/RDO-v1.xlsx";
    static final String TEMPLATE_SHA256 =
            "2a97db997d939b738146bad7c39428e38e159a6160f23afdf3297500fb2b8f87";
    private static final String FRONT_SHEET = "v.1 RDO frente";
    private static final String BACK_SHEET = "v.1 RDO verso";
    private static final int MAX_WORKFORCE_GROUPS = 26;
    private static final int MAX_EQUIPMENT = 32;
    private static final int MAX_WORKED_ROWS = 21;
    private static final int MAX_MATERIAL_ROWS = 30;
    private static final int MAX_GEOMETRY_ROWS = 36;
    private static final int MAX_CELL_TEXT_LENGTH = 32_767;

    private final RdoQueryService queryService;
    private final RdoExportTextSanitizer sanitizer =
            new RdoExportTextSanitizer();

    public RdoXlsxExportService(RdoQueryService queryService) {
        this.queryService = queryService;
    }

    public ExportedRdo export(String rdoId) {
        RdoResponse rdo = queryService.buscarPorId(rdoId);
        ExportRows rows = buildRows(rdo);
        validateCoverage(rows);

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
            populate(workbook, rdo, rows);
            assertNoExecutableContent(workbook);

            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                workbook.write(output);
                return new ExportedRdo(
                        output.toByteArray(),
                        sanitizer.filename(rdo.numeroRdo(), rdo.id())
                );
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Não foi possível gerar o XLSX do RDO.",
                    exception
            );
        }
    }

    private ExportRows buildRows(RdoResponse rdo) {
        List<WorkforceGroup> workforce = groupWorkforce(rdo.maoObra());
        List<RdoResponse.EquipamentoItem> equipment = copy(rdo.equipamentos());
        List<WorkedRow> worked = new ArrayList<>();
        for (RdoResponse.ControleGeometricoItem control
                : copy(rdo.controlesGeometricos())) {
            worked.add(WorkedRow.fromControl(control));
        }
        for (RdoResponse.ServicoExecutadoItem service
                : copy(rdo.servicosExecutados())) {
            worked.add(WorkedRow.fromService(service));
        }

        List<MaterialRow> materials = new ArrayList<>();
        for (RdoResponse.MaterialItem material : copy(rdo.materiais())) {
            addMaterialRows(materials, material);
        }

        return new ExportRows(
                workforce,
                equipment,
                worked,
                materials,
                copy(rdo.controlesGeometricos())
        );
    }

    private void validateCoverage(ExportRows rows) {
        rejectOverflow(
                "grupos de mão de obra",
                rows.workforce().size(),
                MAX_WORKFORCE_GROUPS
        );
        rejectOverflow(
                "equipamentos/veículos",
                rows.equipment().size(),
                MAX_EQUIPMENT
        );
        rejectOverflow(
                "trechos/serviços",
                rows.worked().size(),
                MAX_WORKED_ROWS
        );
        rejectOverflow(
                "linhas de materiais",
                rows.materials().size(),
                MAX_MATERIAL_ROWS
        );
        rejectOverflow(
                "controles geométricos",
                rows.geometry().size(),
                MAX_GEOMETRY_ROWS
        );
    }

    private void rejectOverflow(String section, int received, int capacity) {
        if (received <= capacity) {
            return;
        }
        throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "O template RDO v1 comporta " + capacity + " " + section
                        + ", mas o RDO possui " + received
                        + "; nenhum item foi truncado."
        );
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
            RdoResponse rdo,
            ExportRows rows
    ) {
        Sheet front = workbook.getSheet(FRONT_SHEET);
        Sheet back = workbook.getSheet(BACK_SHEET);
        clearOperationalFixtures(front, back);
        populateHeader(workbook, front, back, rdo);
        populateConditions(workbook, front, rdo);
        populateWorkforce(workbook, front, rows.workforce());
        populateEquipment(workbook, front, rows.equipment());
        populateWorkedSection(workbook, front, rows.worked());
        populateMaterials(workbook, back, rows.materials());
        populateGeometry(workbook, back, rows.geometry());
        populateObservationsAndSignatures(workbook, back, rdo);
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
            RdoResponse rdo
    ) {
        mergeIfMissing(front, "B6:P6");
        mergeIfMissing(front, "Q6:U6");
        mergeIfMissing(front, "V6:Z6");
        mergeIfMissing(front, "AA6:AE6");
        mergeIfMissing(front, "AF6:AJ6");
        mergeIfMissing(back, "AB4:AD4");
        String traceableRdo = firstNonBlank(rdo.numeroRdo(), rdo.id());
        text(front, "B1", "RELATÓRIO DIÁRIO DE OBRA (RDO) — " + traceableRdo);
        text(back, "B2", "RELATÓRIO DIÁRIO DE OBRA (RDO) — " + traceableRdo);
        text(front, "B6", firstNonBlank(rdo.contrato(), rdo.cliente()));
        text(front, "Q6", rdo.obraId());
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
        String column = normalized.contains("CHUVA")
                ? "G"
                : normalized.contains("IMPROD") ? "J" : "D";
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
            requireDescription(item.descricao(), "equipamento/veículo");
            boolean right = index % 2 == 1;
            int row = 36 + index / 2;
            text(sheet, (right ? "T" : "B") + row, item.descricao());
            text(sheet, (right ? "AH" : "O") + row, item.prefixo());
            boolean subcontracted = isSubcontracted(item.tipoVinculo());
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
            RdoResponse rdo
    ) {
        mergeIfMissing(sheet, "B63:AD68");
        Cell observations = cell(sheet, "B63");
        observations.setCellValue(safeText(allObservations(rdo)));
        CellStyle style = workbook.createCellStyle();
        style.cloneStyleFrom(observations.getCellStyle());
        style.setWrapText(true);
        style.setVerticalAlignment(
                org.apache.poi.ss.usermodel.VerticalAlignment.TOP
        );
        observations.setCellStyle(style);

        mergeIfMissing(sheet, "B69:J69");
        mergeIfMissing(sheet, "L69:T69");
        mergeIfMissing(sheet, "V69:AD69");
        text(sheet, "B69", selectedApontadorName(rdo));
        text(sheet, "L69", rdo.encarregadoObra());
        text(sheet, "V69", rdo.fiscalizacaoCampo());
    }

    private String allObservations(RdoResponse rdo) {
        List<String> entries = new ArrayList<>();
        boolean carriesPreviousWorkforce = rdo.previousRdoId() != null
                && copy(rdo.maoObra()).stream()
                        .anyMatch(item -> item.origemItemId() != null
                                && !item.origemItemId().isBlank());
        if (carriesPreviousWorkforce) {
            entries.add("Continuidade da equipe: mão de obra importada do RDO "
                    + safeText(rdo.previousRdoId()));
        }
        addObservation(entries, "RDO", rdo.observacoes());
        for (RdoResponse.MaoObraItem item : copy(rdo.maoObra())) {
            addObservation(entries, "Mão de obra " + safeText(item.cargo()), item.observacoes());
        }
        for (RdoResponse.EquipamentoItem item : copy(rdo.equipamentos())) {
            addObservation(
                    entries,
                    "Equipamento " + safeText(item.descricao()),
                    item.observacoes()
            );
        }
        for (RdoResponse.MaterialItem item : copy(rdo.materiais())) {
            addObservation(
                    entries,
                    "Material " + safeText(item.materialNome()),
                    item.observacoes()
            );
        }
        for (RdoResponse.ControleGeometricoItem item
                : copy(rdo.controlesGeometricos())) {
            addObservation(entries, "Controle " + safeText(item.subtrecho()), item.observacoes());
        }
        for (RdoResponse.ServicoExecutadoItem item : copy(rdo.servicosExecutados())) {
            addObservation(entries, "Serviço " + safeText(item.servicoNome()), item.observacoes());
        }
        String result = String.join("\n", entries);
        if (result.length() > MAX_CELL_TEXT_LENGTH) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "As observações do RDO excedem " + MAX_CELL_TEXT_LENGTH
                            + " caracteres; nenhum conteúdo foi truncado."
            );
        }
        return result;
    }

    private void addObservation(List<String> entries, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        entries.add(safeText(label) + ": " + safeText(value));
    }

    private String selectedApontadorName(RdoResponse rdo) {
        if (rdo.apontadorRdo() != null && !rdo.apontadorRdo().isBlank()) {
            return rdo.apontadorRdo();
        }
        if (rdo.apontadorColaboradorId() == null) {
            return null;
        }
        return copy(rdo.maoObra()).stream()
                .filter(item -> rdo.apontadorColaboradorId().equals(item.colaboradorId()))
                .map(RdoResponse.MaoObraItem::nomeColaborador)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
    }

    private void addMaterialRows(
            List<MaterialRow> rows,
            RdoResponse.MaterialItem material
    ) {
        requireDescription(material.materialNome(), "material");
        int initialSize = rows.size();
        addMaterialRow(rows, material, "Usinado", material.quantidadeUsinada());
        addMaterialRow(rows, material, "Aplicado", material.quantidadeAplicada());
        addMaterialRow(rows, material, "Sobra", material.quantidadeSobra());
        if (rows.size() == initialSize) {
            addMaterialRow(rows, material, "Previsto", material.quantidadePrevista());
        }
        if (rows.size() == initialSize) {
            rows.add(new MaterialRow(
                    material.materialNome(),
                    null,
                    material.unidade(),
                    material.notaFiscal()
            ));
        }
    }

    private void addMaterialRow(
            List<MaterialRow> rows,
            RdoResponse.MaterialItem material,
            String measure,
            BigDecimal quantity
    ) {
        if (quantity == null) {
            return;
        }
        rows.add(new MaterialRow(
                material.materialNome().trim() + " — " + measure,
                quantity,
                material.unidade(),
                material.notaFiscal()
        ));
    }

    private List<WorkforceGroup> groupWorkforce(
            List<RdoResponse.MaoObraItem> items
    ) {
        Map<String, WorkforceGroup> grouped = new LinkedHashMap<>();
        for (RdoResponse.MaoObraItem item : copy(items)) {
            String role = firstNonBlank(item.cargo(), item.nomeColaborador());
            if (role == null || role.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "Há mão de obra sem cargo ou nome; nenhum item foi truncado."
                );
            }
            boolean subcontracted = isSubcontracted(item.tipoVinculo());
            String key = normalize(role) + "|" + subcontracted;
            WorkforceGroup previous = grouped.get(key);
            BigDecimal quantity = nonNull(item.quantidade());
            grouped.put(key, previous == null
                    ? new WorkforceGroup(role, subcontracted, quantity)
                    : new WorkforceGroup(
                            previous.role(),
                            subcontracted,
                            previous.quantity().add(quantity)
                    ));
        }
        return List.copyOf(grouped.values());
    }

    private boolean isSubcontracted(String value) {
        String normalized = normalize(value);
        return normalized.contains("SUBCONTRAT")
                || normalized.contains("TERCEIR");
    }

    private void requireDescription(String value, String section) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Há " + section
                            + " sem descrição; nenhum item foi truncado."
            );
        }
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
        cell(sheet, address).setCellValue(safeText(value));
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

    private <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    public record ExportedRdo(byte[] content, String filename) {
        public ExportedRdo {
            content = content == null ? new byte[0] : content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    private record WorkforceGroup(
            String role,
            boolean subcontracted,
            BigDecimal quantity
    ) {
    }

    private record MaterialRow(
            String description,
            BigDecimal quantity,
            String unit,
            String invoice
    ) {
    }

    private record ExportRows(
            List<WorkforceGroup> workforce,
            List<RdoResponse.EquipamentoItem> equipment,
            List<WorkedRow> worked,
            List<MaterialRow> materials,
            List<RdoResponse.ControleGeometricoItem> geometry
    ) {
    }

    private record WorkedRow(
            String start,
            String end,
            String number,
            BigDecimal length,
            BigDecimal width,
            BigDecimal thicknessCm,
            String roadway,
            String lane,
            String serviceOrder,
            String activity
    ) {
        static WorkedRow fromControl(RdoResponse.ControleGeometricoItem value) {
            return new WorkedRow(
                    firstNonBlankStatic(
                            value.estacaInicial(),
                            value.kmInicial(),
                            value.subtrecho()
                    ),
                    firstNonBlankStatic(value.estacaFinal(), value.kmFinal()),
                    value.numero(),
                    value.comprimentoM(),
                    value.larguraM(),
                    value.espessuraMediaCm(),
                    value.pista(),
                    value.faixa(),
                    value.ordemServico(),
                    firstNonBlankStatic(value.atividadeObservacoes(), value.observacoes())
            );
        }

        static WorkedRow fromService(RdoResponse.ServicoExecutadoItem value) {
            String quantity = value.quantidadeExecutada() == null
                    ? ""
                    : value.quantidadeExecutada().toPlainString()
                            + (value.unidade() == null || value.unidade().isBlank()
                            ? "" : " " + value.unidade());
            String activity = joinNonBlank(
                    value.servicoNome(),
                    quantity.isBlank() ? null : "Quantidade: " + quantity,
                    value.localizacao(),
                    value.observacoes()
            );
            return new WorkedRow(
                    value.trechoInicial(),
                    value.trechoFinal(),
                    null,
                    null,
                    null,
                    null,
                    value.localizacao(),
                    null,
                    null,
                    activity
            );
        }

        private static String firstNonBlankStatic(String... values) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
            return null;
        }

        private static String joinNonBlank(String... values) {
            List<String> parts = new ArrayList<>();
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    parts.add(value.trim());
                }
            }
            return String.join(" | ", parts);
        }
    }
}
