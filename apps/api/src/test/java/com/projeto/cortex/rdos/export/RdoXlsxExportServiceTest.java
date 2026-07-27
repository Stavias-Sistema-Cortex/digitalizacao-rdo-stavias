package com.projeto.cortex.rdos.export;

import static com.projeto.cortex.rdos.export.RdoExportTestFixtures.control;
import static com.projeto.cortex.rdos.export.RdoExportTestFixtures.emptyRdo;
import static com.projeto.cortex.rdos.export.RdoExportTestFixtures.parityControl;
import static com.projeto.cortex.rdos.export.RdoExportTestFixtures.populatedRdo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
        service = new RdoXlsxExportService(
                new RdoExportAggregateFactory(queryService, worksiteReader)
        );
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

        RdoExportFile exported = service.export("rdo-42");

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
            assertThat(workbook.getPrintArea(0)).endsWith("$A$1:$AJ$80");
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
            assertThat(stringCell(frente, "X61")).contains("125.5");
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
            assertThat(stringCell(verso, "B63")).doesNotContain("rdo-41");

            assertThat(allFormulaCells(workbook)).isEmpty();
            assertThat(allHyperlinkedCells(workbook)).isEmpty();
        }
    }

    @Test
    void exportsHumanPreviousRdoNumberWithoutItsInternalId() throws Exception {
        RdoResponse current = populatedRdo("rdo-42", "RDO-0042");
        when(queryService.buscarPorId("rdo-42")).thenReturn(current);
        when(queryService.buscarPorId("rdo-41"))
                .thenReturn(emptyRdo("rdo-41", "RDO-0041"));

        byte[] content = service.export("rdo-42").content();

        try (Workbook workbook = WorkbookFactory.create(
                new ByteArrayInputStream(content)
        )) {
            String observations = stringCell(workbook.getSheetAt(1), "B63");
            assertThat(observations)
                    .contains("Continuidade da equipe")
                    .contains("RDO-0041")
                    .doesNotContain("rdo-41");
        }
    }

    @Test
    void omitsPreviousRdoProvenanceWhenNoHumanNumberIsAvailable()
            throws Exception {
        RdoResponse current = populatedRdo("rdo-42", "RDO-0042");
        when(queryService.buscarPorId("rdo-42")).thenReturn(current);
        when(queryService.buscarPorId("rdo-41"))
                .thenReturn(emptyRdo("rdo-41", " "));

        byte[] content = service.export("rdo-42").content();

        try (Workbook workbook = WorkbookFactory.create(
                new ByteArrayInputStream(content)
        )) {
            String observations = stringCell(workbook.getSheetAt(1), "B63");
            assertThat(observations)
                    .doesNotContain("Continuidade da equipe")
                    .doesNotContain("rdo-41");
        }
    }

    @Test
    void omitsPreviousRdoProvenanceWhenResolvedNumberIsAnInternalUuid()
            throws Exception {
        RdoResponse current = populatedRdo("rdo-42", "RDO-0042");
        when(queryService.buscarPorId("rdo-42")).thenReturn(current);
        when(queryService.buscarPorId("rdo-41")).thenReturn(emptyRdo(
                "rdo-41",
                "00000000-0000-4000-8000-000000000041"
        ));

        byte[] content = service.export("rdo-42").content();

        try (Workbook workbook = WorkbookFactory.create(
                new ByteArrayInputStream(content)
        )) {
            String observations = stringCell(workbook.getSheetAt(1), "B63");
            assertThat(observations)
                    .doesNotContain("Continuidade da equipe")
                    .doesNotContain(
                            "00000000-0000-4000-8000-000000000041"
                    );
        }
    }

    @Test
    void omitsPreviousRdoProvenanceForVersionZeroUuidShape()
            throws Exception {
        RdoResponse current = populatedRdo("rdo-42", "RDO-0042");
        when(queryService.buscarPorId("rdo-42")).thenReturn(current);
        when(queryService.buscarPorId("rdo-41")).thenReturn(emptyRdo(
                "rdo-41",
                "00000000-0000-0000-0000-000000000041"
        ));

        byte[] content = service.export("rdo-42").content();

        try (Workbook workbook = WorkbookFactory.create(
                new ByteArrayInputStream(content)
        )) {
            String observations = stringCell(workbook.getSheetAt(1), "B63");
            assertThat(observations)
                    .doesNotContain("Continuidade da equipe")
                    .doesNotContain(
                            "00000000-0000-0000-0000-000000000041"
                    );
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

        RdoExportFile exported = service.export("=cmd");

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
    void redactsCredentialHeadersBeforeWritingWorkbookCells() throws Exception {
        for (String[] vector : List.of(
                new String[] {
                        "Authorization: Basic BASIC_SECRET_CANARY",
                        "BASIC_SECRET_CANARY"
                },
                new String[] {
                        "Proxy-Authorization: Basic PROXY_BASIC_SECRET_CANARY",
                        "PROXY_BASIC_SECRET_CANARY"
                },
                new String[] {
                        "Authorization: Digest username=\"digest\", "
                                + "response=\"DIGEST_SECRET_CANARY\"",
                        "DIGEST_SECRET_CANARY"
                },
                new String[] {
                        "Cookie: session=COOKIE_SECRET_CANARY",
                        "COOKIE_SECRET_CANARY"
                },
                new String[] {
                        "Set-Cookie: session=SET_COOKIE_SECRET_CANARY",
                        "SET_COOKIE_SECRET_CANARY"
                }
        )) {
            RdoResponse rdo = copyRdo(
                    emptyRdo("rdo-credential", "RDO-CREDENTIAL"),
                    "", "", "", vector[0],
                    List.of(), List.of(), List.of(), List.of(), List.of()
            );
            when(queryService.buscarPorId("rdo-credential")).thenReturn(rdo);

            Map<String, byte[]> packageEntries = zipEntries(
                    service.export("rdo-credential").content()
            );
            String packageText = packageEntries.values().stream()
                    .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                    .reduce("", (left, right) -> left + "\n" + right);

            assertThat(packageText).contains("[segredo removido]")
                    .doesNotContain(vector[1]);
        }
    }

    @Test
    void redactsFoldedCredentialHeadersBeforeWritingWorkbookCells() throws Exception {
        for (String[] vector : List.of(
                new String[] {
                        "Authorization: Basic\r\n BASIC_FOLDED_SECRET_CANARY",
                        "BASIC_FOLDED_SECRET_CANARY"
                },
                new String[] {
                        "Proxy-Authorization: Basic\r\n "
                                + "PROXY_BASIC_FOLDED_SECRET_CANARY",
                        "PROXY_BASIC_FOLDED_SECRET_CANARY"
                },
                new String[] {
                        "Authorization: Digest\r\n "
                                + "response=\"DIGEST_FOLDED_SECRET_CANARY\"",
                        "DIGEST_FOLDED_SECRET_CANARY"
                },
                new String[] {
                        "Cookie: session=one;\r\n COOKIE_FOLDED_SECRET_CANARY",
                        "COOKIE_FOLDED_SECRET_CANARY"
                },
                new String[] {
                        "Set-Cookie: session=one;\r\n "
                                + "SET_COOKIE_FOLDED_SECRET_CANARY",
                        "SET_COOKIE_FOLDED_SECRET_CANARY"
                }
        )) {
            RdoResponse rdo = copyRdo(
                    emptyRdo("rdo-folded-credential", "RDO-FOLDED"),
                    "", "", "", vector[0],
                    List.of(), List.of(), List.of(), List.of(), List.of()
            );
            when(queryService.buscarPorId("rdo-folded-credential")).thenReturn(rdo);

            Map<String, byte[]> packageEntries = zipEntries(
                    service.export("rdo-folded-credential").content()
            );
            String packageText = packageEntries.values().stream()
                    .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                    .reduce("", (left, right) -> left + "\n" + right);

            assertThat(packageText).contains("[segredo removido]")
                    .doesNotContain(vector[1]);
        }
    }

    @Test
    void redactsNonBreakingSpaceCredentialHeadersBeforeWritingWorkbookCells()
            throws Exception {
        for (String[] vector : List.of(
                new String[] {
                        "Authorization\u00A0: Basic "
                                + "NBSP_AUTHORIZATION_SECRET_CANARY",
                        "NBSP_AUTHORIZATION_SECRET_CANARY"
                },
                new String[] {
                        "Cookie:\u00A0session=NBSP_COOKIE_SECRET_CANARY",
                        "NBSP_COOKIE_SECRET_CANARY"
                }
        )) {
            RdoResponse rdo = copyRdo(
                    emptyRdo("rdo-nbsp-credential", "RDO-NBSP"),
                    "", "", "", vector[0],
                    List.of(), List.of(), List.of(), List.of(), List.of()
            );
            when(queryService.buscarPorId("rdo-nbsp-credential")).thenReturn(rdo);

            Map<String, byte[]> packageEntries = zipEntries(
                    service.export("rdo-nbsp-credential").content()
            );
            String packageText = packageEntries.values().stream()
                    .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                    .reduce("", (left, right) -> left + "\n" + right);

            assertThat(packageText).contains("[segredo removido]")
                    .doesNotContain(vector[1]);
        }
    }

    @Test
    void preservesNonCredentialTextAfterABareCookieLabelOnANewLine()
            throws Exception {
        RdoResponse rdo = copyRdo(
                emptyRdo("rdo-cookie-label", "RDO-COOKIE-LABEL"),
                "", "", "", "Cookie:\r\nTexto operacional preservado",
                List.of(), List.of(), List.of(), List.of(), List.of()
        );
        when(queryService.buscarPorId("rdo-cookie-label")).thenReturn(rdo);

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(
                service.export("rdo-cookie-label").content()
        ))) {
            assertThat(stringCell(workbook.getSheetAt(1), "B63"))
                    .contains("Cookie:", "Texto operacional preservado")
                    .doesNotContain("[segredo removido]");
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
    void matchesOfflineCodePointAndLineBreakBoundariesForStructuredFields() {
        RdoResponse base = populatedRdo("rdo-print-parity", "RDO-PRINT-PARITY");
        List<PrintableParityCase> cases = List.of(
                new PrintableParityCase(
                        "kmInicialProgramado", "km inicial programado", 12
                ),
                new PrintableParityCase(
                        "kmFinalProgramado", "km final programado", 12
                ),
                new PrintableParityCase(
                        "kmInicialInterditado", "km inicial interditado", 12
                ),
                new PrintableParityCase(
                        "kmFinalInterditado", "km final interditado", 12
                ),
                new PrintableParityCase("material", "material", 24),
                new PrintableParityCase("numero", "número do trecho", 12),
                new PrintableParityCase("pista", "pista", 16),
                new PrintableParityCase("faixa", "faixa", 16),
                new PrintableParityCase(
                        "ordemServico", "ordem de serviço", 30
                )
        );

        for (PrintableParityCase testCase : cases) {
            for (String accepted : List.of(
                    "W".repeat(testCase.limit()),
                    "😀".repeat(testCase.limit())
            )) {
                when(queryService.buscarPorId(base.id())).thenReturn(
                        withPrintableField(base, testCase.field(), accepted)
                );
                assertThatCode(() -> service.export(base.id()))
                        .as(testCase.section() + " accepted")
                        .doesNotThrowAnyException();
            }

            String expectedReason = "O conteúdo de " + testCase.section()
                    + " não permanece legível no RDO (limite de "
                    + testCase.limit() + " caracteres em uma linha); "
                    + "nenhum conteúdo foi truncado.";
            for (String rejected : List.of(
                    "W".repeat(testCase.limit() + 1),
                    "valor\nquebra",
                    "valor\rquebra"
            )) {
                when(queryService.buscarPorId(base.id())).thenReturn(
                        withPrintableField(base, testCase.field(), rejected)
                );
                assertThatThrownBy(() -> service.export(base.id()))
                        .as(testCase.section() + " rejected")
                        .isInstanceOfSatisfying(
                                ResponseStatusException.class,
                                exception -> {
                                    assertThat(exception.getStatusCode())
                                            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                                    assertThat(exception.getReason())
                                            .isEqualTo(expectedReason);
                                }
                        );
            }
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

        RdoExportFile exported = service.export("rdo-boundary");
        String requestedOutput = System.getProperty("cortex.rdo.render.output");
        if (requestedOutput != null && !requestedOutput.isBlank()) {
            writeAuditFixture(requestedOutput, exported.content());
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

    @Test
    void emitsCompleteSanitizedParityFixtureForOfflineComparison()
            throws Exception {
        RdoResponse base = populatedRdo("rdo-parity", "RDO-PARITY");
        RdoResponse parity = copyRdo(
                base,
                base.condicaoManha(),
                base.condicaoTarde(),
                base.condicaoNoite(),
                "@cmd ana@example.com 123.456.789-09 "
                        + "Bearer BEARER_SECRET_CANARY",
                base.maoObra(),
                base.equipamentos(),
                base.materiais(),
                List.of(parityControl()),
                base.servicosExecutados()
        );
        when(queryService.buscarPorId("rdo-parity")).thenReturn(parity);

        RdoExportFile exported = service.export("rdo-parity");
        String requestedOutput = System.getProperty("cortex.rdo.parity.output");
        if (requestedOutput != null && !requestedOutput.isBlank()) {
            writeAuditFixture(requestedOutput, exported.content());
        }

        try (Workbook workbook = WorkbookFactory.create(
                new ByteArrayInputStream(exported.content())
        )) {
            assertThat(stringCell(workbook.getSheetAt(0), "D10")).isEqualTo("X");
            assertThat(stringCell(workbook.getSheetAt(0), "G11")).isEqualTo("X");
            assertThat(stringCell(workbook.getSheetAt(0), "J12")).isEqualTo("X");
            assertThat(stringCell(workbook.getSheetAt(0), "B60")).isEqualTo("10+000");
            assertThat(stringCell(workbook.getSheetAt(0), "X61")).contains("Fresagem");
            assertThat(stringCell(workbook.getSheetAt(1), "B8")).isEqualTo("CAP (U)");
            assertThat(stringCell(workbook.getSheetAt(1), "B26"))
                    .isEqualTo("km 10 ao km 11");
            assertThat(stringCell(workbook.getSheetAt(1), "B63"))
                    .contains("[email removido]")
                    .contains("[CPF removido]")
                    .contains("Bearer [segredo removido]")
                    .doesNotContain("ana@example.com")
                    .doesNotContain("BEARER_SECRET_CANARY");
            assertThat(stringCell(workbook.getSheetAt(1), "B69"))
                    .isEqualTo("Ana Apontadora");
            assertThat(stringCell(workbook.getSheetAt(1), "L69"))
                    .isEqualTo("Enzo Encarregado");
            assertThat(stringCell(workbook.getSheetAt(1), "V69"))
                    .isEqualTo("Fiscal de Campo");
            assertThat(allFormulaCells(workbook)).isEmpty();
        }
    }

    private static void writeAuditFixture(String requestedOutput, byte[] content)
            throws Exception {
        Path output = Path.of(requestedOutput).toAbsolutePath();
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(output, content);
    }

    private static RdoResponse withPrintableField(
            RdoResponse original,
            String field,
            String value
    ) {
        List<RdoResponse.MaterialItem> materials = "material".equals(field)
                ? List.of(new RdoResponse.MaterialItem(
                        "mat-print", value, "t", null, null, null, null,
                        "NF-PRINT", null, null
                ))
                : original.materiais();
        boolean controlField = List.of(
                "numero", "pista", "faixa", "ordemServico"
        ).contains(field);
        List<RdoResponse.ControleGeometricoItem> controls = controlField
                ? List.of(withPrintableControlField(parityControl(), field, value))
                : original.controlesGeometricos();
        return new RdoResponse(
                original.id(), original.obraId(), original.programacaoId(),
                original.numeroRdo(), original.dataRdo(), original.previousRdoId(),
                original.creationContextVersion(), original.clientMutationId(),
                original.apontadorColaboradorId(), original.diaSemana(),
                original.cliente(), original.contrato(), original.rodovia(),
                original.cidade(), original.uf(),
                "kmInicialProgramado".equals(field)
                        ? value : original.kmInicialProgramado(),
                "kmFinalProgramado".equals(field)
                        ? value : original.kmFinalProgramado(),
                "kmInicialInterditado".equals(field)
                        ? value : original.kmInicialInterditado(),
                "kmFinalInterditado".equals(field)
                        ? value : original.kmFinalInterditado(),
                original.turno(), original.horaInicio(), original.horaFim(),
                original.condicaoManha(), original.condicaoTarde(),
                original.condicaoNoite(), original.pluviometriaMm(),
                original.status(), original.observacoes(), original.preenchidoPor(),
                original.apontadorRdo(), original.encarregadoObra(),
                original.fiscalizacaoCampo(), original.maoObra(),
                original.equipamentos(), materials, controls,
                original.servicosExecutados(), original.alocacoesColaboradores(),
                original.attachments()
        );
    }

    private static RdoResponse.ControleGeometricoItem withPrintableControlField(
            RdoResponse.ControleGeometricoItem original,
            String field,
            String value
    ) {
        return new RdoResponse.ControleGeometricoItem(
                original.id(), original.subtrecho(),
                "numero".equals(field) ? value : original.numero(),
                original.estacaInicial(), original.estacaFinal(),
                original.kmInicial(), original.kmFinal(),
                "pista".equals(field) ? value : original.pista(),
                "faixa".equals(field) ? value : original.faixa(),
                "ordemServico".equals(field) ? value : original.ordemServico(),
                original.atividadeObservacoes(), original.comprimentoM(),
                original.larguraM(), original.espessura1Cm(),
                original.espessura2Cm(), original.espessura3Cm(),
                original.espessuraMediaCm(), original.areaM2(),
                original.volumeM3(), original.densidade(),
                original.massaTonelada(), original.observacoes()
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
                "svc-boundary", null, null, name, "item-boundary", BigDecimal.ONE,
                "m²", "10+000", "10+001", null, "DIURNO", "VALIDADO",
                "ESTIMADA", "HISTORICAL_UNPRICED", null, null, null,
                false, false, observations
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

    private record PrintableParityCase(
            String field,
            String section,
            int limit
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
