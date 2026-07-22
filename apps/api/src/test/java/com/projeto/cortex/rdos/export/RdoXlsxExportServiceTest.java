package com.projeto.cortex.rdos.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.projeto.cortex.rdos.RdoQueryService;
import com.projeto.cortex.rdos.RdoResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
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
    private RdoExportWorksiteReader worksiteReader;
    private RdoXlsxExportService service;

    @BeforeEach
    void setUp() {
        queryService = mock(RdoQueryService.class);
        worksiteReader = mock(RdoExportWorksiteReader.class);
        when(worksiteReader.read(anyString())).thenReturn(
                new RdoExportWorksiteReader.Worksite("Obra Norte", "CW-007")
        );
        service = new RdoXlsxExportService(queryService, worksiteReader);
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
            assertThat(stringCell(verso, "B2"))
                    .isEqualTo("RELATÓRIO DIÁRIO DE OBRA (RDO)");
            assertThat(stringCell(frente, "B6")).isEqualTo("Obra Norte");
            assertThat(stringCell(frente, "Q6")).isEqualTo("CW-007");
            assertThat(stringCell(frente, "B6")).isNotEqualTo(rdo.contrato());
            assertThat(stringCell(frente, "B6")).isNotEqualTo(rdo.cliente());
            assertThat(stringCell(frente, "Q6")).isNotEqualTo(rdo.obraId());
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
            assertThat(containsMergedRange(frente, "X61:AJ61")).isTrue();

            assertThat(verso.getRow(3).getCell(27).getCellType())
                    .isEqualTo(CellType.NUMERIC);
            assertThat(stringCell(verso, "B8")).isEqualTo("CAP (U)");
            assertThat(containsMergedRange(verso, "B8:D8")).isTrue();
            assertThat(numericCell(verso, "E8")).isEqualTo(15.5d);
            assertThat(stringCell(verso, "B9")).isEqualTo("CAP (A)");
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
    void mapsEveryProductionWeatherStateWithoutInventingGoodWeather()
            throws Exception {
        RdoResponse rdo = copyRdo(
                emptyRdo("rdo-weather", "RDO-WEATHER"),
                "IMPOSSIBILITADO",
                "NAO_APLICAVEL",
                "NUBLADO",
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        when(queryService.buscarPorId("rdo-weather")).thenReturn(rdo);

        try (Workbook workbook = WorkbookFactory.create(
                new ByteArrayInputStream(service.export("rdo-weather").content())
        )) {
            Sheet front = workbook.getSheetAt(0);
            assertThat(stringCell(front, "D10")).isBlank();
            assertThat(stringCell(front, "G10")).isBlank();
            assertThat(stringCell(front, "J10")).isEqualTo("X");
            assertThat(stringCell(front, "D11")).isBlank();
            assertThat(stringCell(front, "G11")).isBlank();
            assertThat(stringCell(front, "J11")).isBlank();
            assertThat(stringCell(front, "D12")).isBlank();
            assertThat(stringCell(front, "G12")).isBlank();
            assertThat(stringCell(front, "J12")).isBlank();
            assertThat(stringCell(workbook.getSheetAt(1), "B63"))
                    .contains("Clima noite: Nublado");
        }

        RdoResponse productiveWeather = copyRdo(
                emptyRdo("rdo-weather-productive", "RDO-WEATHER-PROD"),
                "BOM",
                "CHUVA",
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        when(queryService.buscarPorId("rdo-weather-productive"))
                .thenReturn(productiveWeather);
        try (Workbook workbook = WorkbookFactory.create(
                new ByteArrayInputStream(
                        service.export("rdo-weather-productive").content()
                )
        )) {
            Sheet front = workbook.getSheetAt(0);
            assertThat(stringCell(front, "D10")).isEqualTo("X");
            assertThat(stringCell(front, "G11")).isEqualTo("X");
        }

        RdoResponse unknown = copyRdo(
                emptyRdo("rdo-weather-unknown", "RDO-WEATHER-UNKNOWN"),
                "TEMPESTADE",
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        when(queryService.buscarPorId("rdo-weather-unknown")).thenReturn(unknown);

        assertThatThrownBy(() -> service.export("rdo-weather-unknown"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode())
                            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(exception.getReason())
                            .contains("condição climática")
                            .contains("TEMPESTADE");
                });
    }

    @Test
    void mapsOwnedRentedAndOutsourcedEquipmentExhaustively()
            throws Exception {
        RdoResponse rdo = copyRdo(
                emptyRdo("rdo-equipment", "RDO-EQUIPMENT"),
                null,
                null,
                null,
                null,
                List.of(),
                List.of(
                        equipment("eq-own", "Escavadeira", "PROPRIO", "P-1", "1"),
                        equipment("eq-rent", "Fresadora", "LOCADO", "L-2", "2"),
                        equipment("eq-third", "Rolo", "TERCEIRIZADO", "T-3", "3")
                ),
                List.of(),
                List.of(),
                List.of()
        );
        when(queryService.buscarPorId("rdo-equipment")).thenReturn(rdo);

        try (Workbook workbook = WorkbookFactory.create(
                new ByteArrayInputStream(service.export("rdo-equipment").content())
        )) {
            Sheet front = workbook.getSheetAt(0);
            assertThat(numericCell(front, "I36")).isEqualTo(1d);
            assertThat(numericCell(front, "AE36")).isEqualTo(2d);
            assertThat(numericCell(front, "L37")).isEqualTo(3d);
            assertThat(numericCell(front, "M53")).isEqualTo(1d);
            assertThat(numericCell(front, "M54")).isEqualTo(5d);
        }

        RdoResponse unknown = copyRdo(
                emptyRdo("rdo-equipment-unknown", "RDO-EQUIPMENT-UNKNOWN"),
                null,
                null,
                null,
                null,
                List.of(),
                List.of(equipment(
                        "eq-unknown", "Guindaste", "EMPRESTADO", "E-1", "1"
                )),
                List.of(),
                List.of(),
                List.of()
        );
        when(queryService.buscarPorId("rdo-equipment-unknown")).thenReturn(unknown);

        assertThatThrownBy(() -> service.export("rdo-equipment-unknown"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode())
                            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(exception.getReason())
                            .contains("vínculo de equipamento")
                            .contains("EMPRESTADO");
                });
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
        Map<String, byte[]> packageEntries = zipEntries(exported.content());
        assertThat(packageEntries.keySet())
                .noneMatch(name -> name.startsWith("customXml/"));
        String packageText = packageEntries.values().stream()
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .reduce("", (left, right) -> left + "\n" + right);
        assertThat(packageText)
                .doesNotContain("Hugo Florêncio")
                .doesNotContain("RSA_BODY_CANARY")
                .doesNotContain("EC_BODY_CANARY")
                .doesNotContain("OPENSSH_BODY_CANARY")
                .doesNotContain("GENERIC_BODY_CANARY")
                .doesNotContain("BEARER_SECRET_CANARY")
                .doesNotContain("API_SECRET_CANARY")
                .doesNotContain("AKIAIOSFODNN7EXAMPLE")
                .doesNotContain("C:\\Users\\user\\Downloads\\")
                .doesNotContain("ana@example.com")
                .doesNotContain("123.456.789-09")
                .doesNotContain("TargetMode=\"External\"")
                .doesNotContain("<f>")
                .doesNotContain("<f ");
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
            XSSFWorkbook xssf = (XSSFWorkbook) workbook;
            assertThat(xssf.getProperties().getCoreProperties().getCreator())
                    .isBlank();
            assertThat(xssf.getProperties()
                    .getCoreProperties()
                    .getLastModifiedByUser()).isBlank();
            assertThat(xssf.getProperties()
                    .getCustomProperties()
                    .getUnderlyingProperties()
                    .sizeOfPropertyArray()).isZero();
        }
    }

    @Test
    void rejectsTextThatCannotRemainLegibleInFixedPrintRegions() {
        RdoResponse base = emptyRdo("rdo-text", "RDO-TEXT");
        List<InvalidPrintCase> cases = List.of(
                new InvalidPrintCase(
                        "rdo-role-long",
                        copyRdo(
                                base,
                                null, null, null, null,
                                List.of(new RdoResponse.MaoObraItem(
                                        "mo-long", "col-long", "Nome",
                                        "W".repeat(19), "CONTRATADO", BigDecimal.ONE,
                                        null, null, null, null
                                )),
                                List.of(), List.of(), List.of(), List.of()
                        ),
                        "cargo"
                ),
                new InvalidPrintCase(
                        "rdo-equipment-long",
                        copyRdo(
                                base,
                                null, null, null, null,
                                List.of(),
                                List.of(equipment(
                                        "eq-long", "W".repeat(25), "PROPRIO", "P-1", "1"
                                )),
                                List.of(), List.of(), List.of()
                        ),
                        "descrição do equipamento"
                ),
                new InvalidPrintCase(
                        "rdo-material-long",
                        copyRdo(
                                base,
                                null, null, null, null,
                                List.of(), List.of(),
                                List.of(new RdoResponse.MaterialItem(
                                        "mat-long", "W".repeat(25), "t", null,
                                        BigDecimal.ONE, null, null,
                                        "NF-1", null, null
                                )),
                                List.of(), List.of()
                        ),
                        "material"
                ),
                new InvalidPrintCase(
                        "rdo-invoice-long",
                        copyRdo(
                                base,
                                null, null, null, null,
                                List.of(), List.of(),
                                List.of(new RdoResponse.MaterialItem(
                                        "mat-nf", "CAP", "t", null,
                                        BigDecimal.ONE, null, null,
                                        "N".repeat(25), null, null
                                )),
                                List.of(), List.of()
                        ),
                        "nota fiscal"
                ),
                new InvalidPrintCase(
                        "rdo-service-long",
                        copyRdo(
                                base,
                                null, null, null, null,
                                List.of(), List.of(), List.of(), List.of(),
                                List.of(service("S".repeat(62), null))
                        ),
                        "atividade executada"
                ),
                new InvalidPrintCase(
                        "rdo-observations-long",
                        copyRdo(
                                base,
                                null, null, null,
                                String.join("\n", List.of(
                                        "linha 1", "linha 2", "linha 3", "linha 4",
                                        "linha 5", "linha 6", "linha 7"
                                )),
                                List.of(), List.of(), List.of(), List.of(), List.of()
                        ),
                        "observações gerais"
                )
        );

        for (InvalidPrintCase testCase : cases) {
            RdoResponse rdo = withIdentity(testCase.rdo(), testCase.id());
            when(queryService.buscarPorId(testCase.id())).thenReturn(rdo);
            assertThatThrownBy(() -> service.export(testCase.id()))
                    .as(testCase.id())
                    .isInstanceOfSatisfying(
                            ResponseStatusException.class,
                            exception -> {
                                assertThat(exception.getStatusCode())
                                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                                assertThat(exception.getReason())
                                        .contains(testCase.section())
                                        .contains("legível")
                                        .contains("nenhum conteúdo foi truncado");
                            }
                    );
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

    @Test
    void acceptsDocumentedTextBoundaryAndEmitsAuditableRenderFixture()
            throws Exception {
        String boundaryObservations = String.join("\n", List.of(
                "A".repeat(95),
                "B".repeat(100),
                "C".repeat(100),
                "D".repeat(100),
                "E".repeat(100),
                "F".repeat(100)
        ));
        RdoResponse boundary = copyRdo(
                emptyRdo("rdo-boundary", "RDO-BOUNDARY"),
                "BOM",
                "CHUVA",
                "IMPOSSIBILITADO",
                boundaryObservations,
                List.of(new RdoResponse.MaoObraItem(
                        "mo-boundary", "col-boundary", "Nome",
                        "Operador pavimento", "CONTRATADO", BigDecimal.ONE,
                        null, null, null, null
                )),
                List.of(equipment(
                        "eq-boundary", "Escavadeira hidráulica", "LOCADO",
                        "EQ-26-07", "1"
                )),
                List.of(new RdoResponse.MaterialItem(
                        "mat-boundary", "Ligante RR-2C modificado", "m²/d", null,
                        BigDecimal.ONE, null, null,
                        "NF-2026-00000000000042", null, null
                )),
                List.of(),
                List.of(service(
                        "Fresagem localizada e recomposição asfáltica na faixa direita",
                        null
                ))
        );
        when(queryService.buscarPorId("rdo-boundary")).thenReturn(boundary);

        RdoXlsxExportService.ExportedRdo exported = service.export("rdo-boundary");
        String requestedOutput = System.getProperty("cortex.rdo.render.output");
        if (requestedOutput != null && !requestedOutput.isBlank()) {
            Files.write(Path.of(requestedOutput), exported.content());
        }

        try (Workbook workbook = WorkbookFactory.create(
                new ByteArrayInputStream(exported.content())
        )) {
            assertThat(workbook.getSheetAt(0)
                    .getRow(15)
                    .getCell(1)
                    .getCellStyle()
                    .getShrinkToFit()).isTrue();
            assertThat(workbook.getSheetAt(1)
                    .getRow(62)
                    .getCell(1)
                    .getCellStyle()
                    .getWrapText()).isTrue();
            assertThat(containsMergedRange(workbook.getSheetAt(0), "X60:AJ60"))
                    .isTrue();
            assertThat(stringCell(workbook.getSheetAt(0), "X60"))
                    .contains("Fresagem localizada")
                    .contains("Quantidade: 1 m²");
            assertThat(containsMergedRange(workbook.getSheetAt(1), "B8:D8"))
                    .isTrue();
            assertThat(stringCell(workbook.getSheetAt(1), "B63").lines().count())
                    .isEqualTo(6);
        }
    }

    private static RdoResponse populatedRdo(String id, String numero) {
        return new RdoResponse(
                id, "obra-7", null, numero, LocalDate.of(2026, 7, 22),
                "rdo-41", 9L, "mutation-42", "col-ana", "quarta-feira",
                "Cliente Rodovias", "CTR-9", "BR-101", "Joinville", "SC",
                "10+000", "11+000", "10+200", "10+800", "DIURNO",
                LocalTime.of(7, 30), LocalTime.of(17, 15), "BOM", "CHUVA",
                "IMPOSSIBILITADO", new BigDecimal("3.25"), "RASCUNHO",
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
                        "VALIDADO", "ESTIMADA", new BigDecimal("1000"),
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
        String maliciousObservations = """
                @cmd ana@example.com 123.456.789-09
                -----BEGIN RSA PRIVATE KEY-----
                RSA_BODY_CANARY
                -----END RSA PRIVATE KEY-----
                -----BEGIN EC PRIVATE KEY-----
                EC_BODY_CANARY
                -----END EC PRIVATE KEY-----
                -----BEGIN OPENSSH PRIVATE KEY-----
                OPENSSH_BODY_CANARY
                -----END OPENSSH PRIVATE KEY-----
                -----BEGIN PRIVATE KEY-----
                GENERIC_BODY_CANARY
                -----END PRIVATE KEY-----
                Authorization: Bearer BEARER_SECRET_CANARY \
                api_key="API_SECRET_CANARY" \
                aws_access_key_id=AKIAIOSFODNN7EXAMPLE
                """;
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
                maliciousObservations,
                original.preenchidoPor(), original.apontadorRdo(),
                original.encarregadoObra(), original.fiscalizacaoCampo(),
                List.of(), List.of(),
                List.of(new RdoResponse.MaterialItem(
                        "mat-x", "+CMD", "t", null, BigDecimal.ONE, null, null,
                        "-NF", null, null
                )),
                List.of(), List.of(),
                original.alocacoesColaboradores(), original.attachments()
        );
    }

    private static RdoResponse replaceControls(
            RdoResponse original,
            List<RdoResponse.ControleGeometricoItem> controls
    ) {
        return copyRdo(
                original,
                original.condicaoManha(),
                original.condicaoTarde(),
                original.condicaoNoite(),
                original.observacoes(),
                original.maoObra(),
                original.equipamentos(),
                original.materiais(),
                controls,
                original.servicosExecutados()
        );
    }

    private static RdoResponse copyRdo(
            RdoResponse original,
            String morning,
            String afternoon,
            String night,
            String observations,
            List<RdoResponse.MaoObraItem> workforce,
            List<RdoResponse.EquipamentoItem> equipment,
            List<RdoResponse.MaterialItem> materials,
            List<RdoResponse.ControleGeometricoItem> controls,
            List<RdoResponse.ServicoExecutadoItem> services
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
                original.horaFim(), morning, afternoon, night,
                original.pluviometriaMm(), original.status(), observations,
                original.preenchidoPor(), original.apontadorRdo(),
                original.encarregadoObra(), original.fiscalizacaoCampo(),
                workforce, equipment, materials, controls, services,
                original.alocacoesColaboradores(), original.attachments()
        );
    }

    private static RdoResponse withIdentity(RdoResponse original, String id) {
        return new RdoResponse(
                id, original.obraId(), original.programacaoId(),
                original.numeroRdo(),
                original.dataRdo(), original.previousRdoId(),
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
                original.controlesGeometricos(), original.servicosExecutados(),
                original.alocacoesColaboradores(), original.attachments()
        );
    }

    private static RdoResponse.EquipamentoItem equipment(
            String id,
            String description,
            String linkType,
            String prefix,
            String quantity
    ) {
        return new RdoResponse.EquipamentoItem(
                id, "asset-" + id, prefix, description, "EQUIPAMENTO",
                linkType, new BigDecimal(quantity), null, null, null
        );
    }

    private static RdoResponse.ServicoExecutadoItem service(
            String name,
            String observations
    ) {
        return new RdoResponse.ServicoExecutadoItem(
                "svc-boundary", name, "item-boundary", BigDecimal.ONE,
                "m²", "10+000", "10+001", null, "DIURNO", "VALIDADO",
                "ESTIMADA", BigDecimal.ONE, false, false, observations
        );
    }

    private static Map<String, byte[]> zipEntries(byte[] content)
            throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(content)
        )) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                zip.transferTo(bytes);
                entries.put(entry.getName(), bytes.toByteArray());
            }
        }
        return entries;
    }

    private record InvalidPrintCase(
            String id,
            RdoResponse rdo,
            String section
    ) {
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
