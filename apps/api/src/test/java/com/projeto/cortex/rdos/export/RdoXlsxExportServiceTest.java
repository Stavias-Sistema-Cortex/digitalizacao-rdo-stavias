package com.projeto.cortex.rdos.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.projeto.cortex.rdos.RdoQueryService;
import com.projeto.cortex.rdos.RdoResponse;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class RdoXlsxExportServiceTest {

    private static final String EXPECTED_TEMPLATE_SHA256 =
            "2a97db997d939b738146bad7c39428e38e159a6160f23afdf3297500fb2b8f87";

    private RdoQueryService queryService;
    private RdoXlsxExportService service;

    @BeforeEach
    void setUp() {
        queryService = mock(RdoQueryService.class);
        service = new RdoXlsxExportService(queryService);
    }

    @Test
    void versionedTemplateMatchesReviewedBinaryContract() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                "/rdo/export/RDO-v1.xlsx"
        )) {
            assertThat(input).isNotNull();
            assertThat(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())
            )).isEqualTo(EXPECTED_TEMPLATE_SHA256);
        }
    }

    @Test
    void exportsAuthoritativeAggregateWithoutChangingInstitutionalStructure()
            throws Exception {
        RdoResponse rdo = populatedRdo("rdo-42", "RDO-0042");
        when(queryService.buscarPorId("rdo-42")).thenReturn(rdo);

        RdoXlsxExportService.ExportedRdo exported = service.export("rdo-42");

        assertThat(exported.filename()).isEqualTo("rdo-RDO-0042.xlsx");
        assertThat(exported.content()).isNotEmpty();
        try (Workbook workbook = WorkbookFactory.create(
                new ByteArrayInputStream(exported.content())
        )) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            assertThat(workbook.getSheetName(0)).isEqualTo("v.1 RDO frente");
            assertThat(workbook.getSheetName(1)).isEqualTo("v.1 RDO verso");

            Sheet frente = workbook.getSheetAt(0);
            Sheet verso = workbook.getSheetAt(1);
            assertThat(frente.getPrintSetup().getPaperSize()).isEqualTo((short) 9);
            assertThat(frente.getPrintSetup().getLandscape()).isFalse();
            assertThat(frente.getPrintSetup().getScale()).isEqualTo((short) 26);
            assertThat(verso.getPrintSetup().getPaperSize()).isEqualTo((short) 9);
            assertThat(verso.getPrintSetup().getLandscape()).isFalse();
            assertThat(verso.getPrintSetup().getScale()).isEqualTo((short) 33);
            assertThat(workbook.getPrintArea(1)).endsWith("$A$2:$AH$70");
            assertThat(containsMergedRange(frente, "B1:AJ3")).isTrue();
            assertThat(containsMergedRange(frente, "X58:AJ59")).isTrue();
            assertThat(containsMergedRange(verso, "B2:AA4")).isTrue();
            assertThat(containsMergedRange(verso, "B70:J70")).isTrue();
            assertThat(frente.getRow(57).getCell(1).getCellStyle().getBorderTop())
                    .isNotEqualTo(BorderStyle.NONE);
            XSSFWorkbook xssf = (XSSFWorkbook) workbook;
            assertThat(xssf.getAllPictures()).isNotEmpty();
            assertThat(xssf.isMacroEnabled()).isFalse();
            assertThat(xssf.getExternalLinksTable()).isEmpty();

            assertThat(stringCell(frente, "B1")).contains("RDO-0042");
            assertThat(stringCell(frente, "B6")).isEqualTo("Obra Norte");
            assertThat(stringCell(frente, "Q6")).isEqualTo("obra-7");
            assertThat(stringCell(frente, "V6")).isEqualTo("BR-101");
            assertThat(frente.getRow(5).getCell(26).getCellType())
                    .isEqualTo(CellType.NUMERIC);
            assertThat(frente.getRow(9).getCell(12).getCellType())
                    .isEqualTo(CellType.NUMERIC);
            assertThat(frente.getRow(9).getCell(26).getCellType())
                    .isEqualTo(CellType.NUMERIC);

            assertThat(stringCell(frente, "B16")).isEqualTo("Apontador");
            assertThat(numericCell(frente, "G16")).isEqualTo(1d);
            assertThat(stringCell(frente, "M16")).isEqualTo("Operador");
            assertThat(numericCell(frente, "U16")).isEqualTo(2d);
            assertThat(stringCell(frente, "B36")).isEqualTo("Escavadeira");
            assertThat(numericCell(frente, "I36")).isEqualTo(1d);
            assertThat(stringCell(frente, "O36")).isEqualTo("EQ-7");

            assertThat(stringCell(frente, "B60")).isEqualTo("10+000");
            assertThat(numericCell(frente, "J60")).isEqualTo(1000d);
            assertThat(numericCell(frente, "N60")).isEqualTo(0.05d);
            assertThat(stringCell(frente, "X61")).contains("Fresagem");
            assertThat(stringCell(frente, "X61")).contains("125.50");

            assertThat(verso.getRow(3).getCell(27).getCellType())
                    .isEqualTo(CellType.NUMERIC);
            assertThat(stringCell(verso, "B8")).isEqualTo("CAP — Usinado");
            assertThat(numericCell(verso, "E8")).isEqualTo(15.5d);
            assertThat(stringCell(verso, "B9")).isEqualTo("CAP — Aplicado");
            assertThat(numericCell(verso, "E9")).isEqualTo(14.25d);
            assertThat(stringCell(verso, "B26")).isEqualTo("km 10 ao km 11");
            assertThat(numericCell(verso, "L26")).isEqualTo(0.04d);
            assertThat(numericCell(verso, "U26")).isEqualTo(0.05d);
            assertThat(numericCell(verso, "AA26")).isEqualTo(12.75d);
            assertThat(stringCell(verso, "B69")).isEqualTo("Ana Apontadora");
            assertThat(stringCell(verso, "L69")).isEqualTo("Enzo Encarregado");
            assertThat(stringCell(verso, "B63")).contains("rdo-41");

            assertThat(allFormulaCells(workbook)).isEmpty();
            assertThat(allHyperlinkedCells(workbook)).isEmpty();
        }
    }

    @Test
    void clearsOperationalFixturesAndLeavesMissingOptionalValuesBlank()
            throws Exception {
        RdoResponse empty = emptyRdo("rdo-empty", "RDO-EMPTY");
        when(queryService.buscarPorId("rdo-empty")).thenReturn(empty);

        byte[] content = service.export("rdo-empty").content();

        try (Workbook workbook = WorkbookFactory.create(
                new ByteArrayInputStream(content)
        )) {
            Sheet frente = workbook.getSheetAt(0);
            assertThat(stringCell(frente, "B16")).isBlank();
            assertThat(stringCell(frente, "M16")).isBlank();
            assertThat(stringCell(frente, "B36")).isBlank();
            assertThat(stringCell(frente, "T36")).isBlank();
            assertThat(stringCell(frente, "B60")).isBlank();
            assertThat(stringCell(workbook.getSheetAt(1), "B8")).isBlank();
            assertThat(allStringValues(workbook))
                    .doesNotContain("Engenheiro")
                    .doesNotContain("Caminhão de Sinaliz. de Obras");
        }
    }

    @Test
    void sanitizesFormulaInjectionAndRedactsPiiAndSecretsEverywhere()
            throws Exception {
        RdoResponse malicious = withMaliciousText(
                populatedRdo("=cmd", "+SUM(A1:A2)")
        );
        when(queryService.buscarPorId("=cmd")).thenReturn(malicious);

        RdoXlsxExportService.ExportedRdo exported = service.export("=cmd");

        assertThat(exported.filename()).matches("rdo-[A-Za-z0-9._-]+\\.xlsx");
        assertThat(exported.filename()).doesNotContain("=").doesNotContain("+");
        try (Workbook workbook = WorkbookFactory.create(
                new ByteArrayInputStream(exported.content())
        )) {
            assertThat(allFormulaCells(workbook)).isEmpty();
            assertThat(allHyperlinkedCells(workbook)).isEmpty();
            assertThat(allStringValues(workbook)).allSatisfy(value -> {
                assertThat(value).doesNotContain("ana@example.com");
                assertThat(value).doesNotContain("123.456.789-09");
                assertThat(value).doesNotContain("PRIVATE KEY");
                if (!value.isEmpty()) {
                    assertThat(value.charAt(0)).isNotIn('=', '+', '-', '@');
                }
            });
        }
    }

    @Test
    void rejectsOverflowWithExactCoverageInsteadOfTruncating() {
        List<RdoResponse.ControleGeometricoItem> controls = new ArrayList<>();
        for (int index = 0; index < 22; index++) {
            controls.add(control("cg-" + index));
        }
        RdoResponse overflowing = replaceControls(
                emptyRdo("rdo-overflow", "RDO-OVERFLOW"),
                controls
        );
        when(queryService.buscarPorId("rdo-overflow")).thenReturn(overflowing);

        assertThatThrownBy(() -> service.export("rdo-overflow"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode())
                            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(exception.getReason())
                            .contains("trechos/serviços")
                            .contains("22")
                            .contains("21")
                            .contains("nenhum item foi truncado");
                });
    }

    private static RdoResponse populatedRdo(String id, String numero) {
        return new RdoResponse(
                id, "obra-7", null, numero, LocalDate.of(2026, 7, 22),
                "rdo-41", 9L, "mutation-42", "col-ana", "quarta-feira",
                "Cliente Rodovias", "Obra Norte", "BR-101", "Joinville", "SC",
                "10+000", "11+000", "10+200", "10+800", "DIURNO",
                LocalTime.of(7, 30), LocalTime.of(17, 15), "BOM", "CHUVA",
                "IMPRODUTIVO", new BigDecimal("3.25"), "RASCUNHO",
                "Execução conferida", "encarregado-7", "Ana Apontadora",
                "Enzo Encarregado", "Fiscal de Campo",
                List.of(
                        new RdoResponse.MaoObraItem(
                                "mo-1", "col-ana", "Ana Apontadora", "Apontador",
                                "CONTRATADO", BigDecimal.ONE, LocalTime.of(7, 30),
                                LocalTime.of(17, 15), null, "mo-anterior"
                        ),
                        new RdoResponse.MaoObraItem(
                                "mo-2", "col-op", "Otávio Operador", "Operador",
                                "SUBCONTRATADO", new BigDecimal("2"),
                                LocalTime.of(8, 0), LocalTime.of(16, 0), null, null
                        )
                ),
                List.of(new RdoResponse.EquipamentoItem(
                        "eq-1", "asset-7", "EQ-7", "Escavadeira", "ESCAVADEIRA",
                        "PROPRIO", BigDecimal.ONE, LocalTime.of(8, 0),
                        LocalTime.of(16, 0), "Operação normal"
                )),
                List.of(new RdoResponse.MaterialItem(
                        "mat-1", "CAP", "t", new BigDecimal("16"),
                        new BigDecimal("15.50"), new BigDecimal("14.25"),
                        new BigDecimal("1.25"), "NF-88", "Fornecedor", null
                )),
                List.of(control("cg-1")),
                List.of(new RdoResponse.ServicoExecutadoItem(
                        "svc-1", "Fresagem", "item-1", new BigDecimal("125.50"),
                        "m²", "10+000", "10+500", "Faixa direita", "DIURNO",
                        "VALIDADO", "ESTIMADA", new BigDecimal("1000"), null,
                        false, false, "Sem intercorrências"
                )),
                List.of(),
                List.of(new RdoResponse.AttachmentItem(
                        "att-1", id, "obra-7", "FOTO", "foto", "foto.jpg",
                        "image/jpeg", 10L, 8L, 8L, "SINCRONIZADO", null, null,
                        null, Map.of("email", "must-not-be-exported@example.com")
                ))
        );
    }

    private static RdoResponse emptyRdo(String id, String numero) {
        return new RdoResponse(
                id, "obra-7", null, numero, LocalDate.of(2026, 7, 22),
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, "RASCUNHO", null, null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of()
        );
    }

    private static RdoResponse.ControleGeometricoItem control(String id) {
        return new RdoResponse.ControleGeometricoItem(
                id, "km 10 ao km 11", "1", "10+000", "11+000", "10", "11",
                "Pista norte", "Direita", "OS-7", "Regularização",
                new BigDecimal("1000"), new BigDecimal("3.5"),
                new BigDecimal("4"), new BigDecimal("5"), new BigDecimal("6"),
                new BigDecimal("5"), new BigDecimal("3500"),
                new BigDecimal("175"), new BigDecimal("2.45"),
                new BigDecimal("12.75"), "Controle aprovado"
        );
    }

    private static RdoResponse withMaliciousText(RdoResponse original) {
        return new RdoResponse(
                original.id(), original.obraId(), original.programacaoId(),
                original.numeroRdo(), original.dataRdo(), original.previousRdoId(),
                original.creationContextVersion(), original.clientMutationId(),
                original.apontadorColaboradorId(), original.diaSemana(),
                "=HYPERLINK(\"https://example.com\")", original.contrato(),
                original.rodovia(), original.cidade(), original.uf(),
                original.kmInicialProgramado(), original.kmFinalProgramado(),
                original.kmInicialInterditado(), original.kmFinalInterditado(),
                original.turno(), original.horaInicio(), original.horaFim(),
                original.condicaoManha(), original.condicaoTarde(),
                original.condicaoNoite(), original.pluviometriaMm(), original.status(),
                "@cmd ana@example.com 123.456.789-09 -----BEGIN PRIVATE KEY-----",
                original.preenchidoPor(), original.apontadorRdo(),
                original.encarregadoObra(), original.fiscalizacaoCampo(),
                original.maoObra(), original.equipamentos(),
                List.of(new RdoResponse.MaterialItem(
                        "mat-x", "+CMD", "t", null, BigDecimal.ONE, null, null,
                        "-NF", null, "secret=abc"
                )),
                original.controlesGeometricos(), original.servicosExecutados(),
                original.alocacoesColaboradores(), original.attachments()
        );
    }

    private static RdoResponse replaceControls(
            RdoResponse original,
            List<RdoResponse.ControleGeometricoItem> controls
    ) {
        return new RdoResponse(
                original.id(), original.obraId(), original.programacaoId(),
                original.numeroRdo(), original.dataRdo(), original.previousRdoId(),
                original.creationContextVersion(), original.clientMutationId(),
                original.apontadorColaboradorId(), original.diaSemana(),
                original.cliente(), original.contrato(), original.rodovia(),
                original.cidade(), original.uf(), original.kmInicialProgramado(),
                original.kmFinalProgramado(), original.kmInicialInterditado(),
                original.kmFinalInterditado(), original.turno(), original.horaInicio(),
                original.horaFim(), original.condicaoManha(),
                original.condicaoTarde(), original.condicaoNoite(),
                original.pluviometriaMm(), original.status(), original.observacoes(),
                original.preenchidoPor(), original.apontadorRdo(),
                original.encarregadoObra(), original.fiscalizacaoCampo(),
                original.maoObra(), original.equipamentos(), original.materiais(),
                controls, original.servicosExecutados(),
                original.alocacoesColaboradores(), original.attachments()
        );
    }

    private static boolean containsMergedRange(Sheet sheet, String address) {
        CellRangeAddress expected = CellRangeAddress.valueOf(address);
        return sheet.getMergedRegions().stream().anyMatch(expected::equals);
    }

    private static String stringCell(Sheet sheet, String address) {
        CellRangeAddress range = CellRangeAddress.valueOf(address);
        Row row = sheet.getRow(range.getFirstRow());
        if (row == null) {
            return "";
        }
        Cell cell = row.getCell(range.getFirstColumn());
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return "";
        }
        return cell.getStringCellValue();
    }

    private static double numericCell(Sheet sheet, String address) {
        CellRangeAddress range = CellRangeAddress.valueOf(address);
        return sheet.getRow(range.getFirstRow())
                .getCell(range.getFirstColumn())
                .getNumericCellValue();
    }

    private static List<Cell> allFormulaCells(Workbook workbook) {
        return allCells(workbook).stream()
                .filter(cell -> cell.getCellType() == CellType.FORMULA)
                .toList();
    }

    private static List<Cell> allHyperlinkedCells(Workbook workbook) {
        return allCells(workbook).stream()
                .filter(cell -> cell.getHyperlink() != null)
                .toList();
    }

    private static List<String> allStringValues(Workbook workbook) {
        return allCells(workbook).stream()
                .filter(cell -> cell.getCellType() == CellType.STRING)
                .map(Cell::getStringCellValue)
                .toList();
    }

    private static List<Cell> allCells(Workbook workbook) {
        List<Cell> cells = new ArrayList<>();
        for (Sheet sheet : workbook) {
            for (Row row : sheet) {
                for (Cell cell : row) {
                    cells.add(cell);
                }
            }
        }
        return cells;
    }
}
