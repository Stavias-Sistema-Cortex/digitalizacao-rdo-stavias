package com.projeto.cortex.rdos.export;

import static com.projeto.cortex.rdos.export.RdoExportTestFixtures.control;
import static com.projeto.cortex.rdos.export.RdoExportTestFixtures.emptyRdo;
import static com.projeto.cortex.rdos.export.RdoExportTestFixtures.populatedRdo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.projeto.cortex.rdos.RdoQueryService;
import com.projeto.cortex.rdos.RdoResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class RdoPdfExportServiceTest {

    private RdoQueryService queryService;
    private RdoPdfExportService service;

    @BeforeEach
    void setUp() {
        queryService = mock(RdoQueryService.class);
        RdoExportWorksiteReader worksiteReader =
                mock(RdoExportWorksiteReader.class);
        when(worksiteReader.read(anyString())).thenReturn(
                new RdoExportWorksiteReader.Worksite("Obra Norte", "CW-007")
        );
        service = new RdoPdfExportService(
                new RdoExportAggregateFactory(queryService, worksiteReader)
        );
    }

    @Test
    void exportsExactlyTwoA4PortraitFacesWithOperationalContent()
            throws Exception {
        when(queryService.buscarPorId("rdo-42")).thenReturn(
                populatedRdo("rdo-42", "RDO-0042")
        );

        RdoExportFile exported = service.export("rdo-42");

        assertThat(exported.content()).startsWith(
                "%PDF-".getBytes(StandardCharsets.US_ASCII)
        );
        assertThat(exported.filename()).isEqualTo("rdo-RDO-0042.pdf");
        try (PDDocument document = Loader.loadPDF(exported.content())) {
            assertThat(document.getNumberOfPages()).isEqualTo(2);
            for (int index = 0; index < document.getNumberOfPages(); index++) {
                PDRectangle page = document.getPage(index).getMediaBox();
                assertThat(page.getWidth()).isEqualTo(PDRectangle.A4.getWidth());
                assertThat(page.getHeight()).isEqualTo(PDRectangle.A4.getHeight());
                assertThat(page.getHeight()).isGreaterThan(page.getWidth());
            }
            String text = new PDFTextStripper().getText(document);
            assertThat(text)
                    .contains("RELATÓRIO DIÁRIO DE OBRA", "Obra Norte", "RDO-0042")
                    .contains(
                            "MÃO DE OBRA",
                            "EQUIPAMENTOS",
                            "TRECHOS E SERVIÇOS",
                            "MATERIAIS",
                            "CONTROLES GEOMÉTRICOS",
                            "OBSERVAÇÕES",
                            "ASSINATURAS"
                    )
                    .doesNotContain(
                            "rdo-42",
                            "rdo-41",
                            "obra-7",
                            "col-ana",
                            "asset-7",
                            "must-not-be-exported@example.com"
                    );
        }
    }

    @Test
    void preservesAggregatePrintableLimitWithoutGeneratingPdf() {
        List<RdoResponse.ControleGeometricoItem> controls = new ArrayList<>();
        for (int index = 0; index < 37; index++) {
            controls.add(control("cg-" + index));
        }
        when(queryService.buscarPorId("rdo-overflow")).thenReturn(copyRdo(
                emptyRdo("rdo-overflow", "RDO-OVERFLOW"),
                null,
                controls
        ));
        AtomicReference<byte[]> generated = new AtomicReference<>();

        assertThatThrownBy(() -> generated.set(
                service.export("rdo-overflow").content()
        )).isInstanceOfSatisfying(
                ResponseStatusException.class,
                exception -> {
                    assertThat(exception.getStatusCode())
                            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(exception.getReason()).isEqualTo(
                            "O template RDO v1 comporta 21 trechos/serviços, "
                                    + "mas o RDO possui 37; "
                                    + "nenhum item foi truncado."
                    );
                }
        );
        assertThat(generated).hasNullValue();
    }

    @Test
    void rejectsUnsupportedPdfGlyphWithoutGeneratingOrSubstitutingContent() {
        RdoResponse rdo = copyRdo(
                emptyRdo("rdo-emoji", "RDO-EMOJI"),
                "Trecho liberado 😀",
                List.of()
        );
        when(queryService.buscarPorId("rdo-emoji")).thenReturn(rdo);
        AtomicReference<byte[]> generated = new AtomicReference<>();

        assertThatThrownBy(() -> generated.set(
                service.export("rdo-emoji").content()
        )).isInstanceOfSatisfying(
                ResponseStatusException.class,
                exception -> {
                    assertThat(exception.getStatusCode())
                            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(exception.getReason()).isEqualTo(
                            "O conteúdo do RDO contém caractere sem representação "
                                    + "segura no PDF; nenhum conteúdo foi substituído."
                    );
                }
        );
        assertThat(generated).hasNullValue();
    }

    @Test
    void rejectsC0ControlBeforeSanitizerCanDropIt() {
        RdoResponse rdo = copyRdo(
                emptyRdo("rdo-control", "RDO-CONTROL"),
                "A\u0001B",
                List.of()
        );
        when(queryService.buscarPorId("rdo-control")).thenReturn(rdo);
        AtomicReference<byte[]> generated = new AtomicReference<>();

        assertThatThrownBy(() -> generated.set(
                service.export("rdo-control").content()
        )).isInstanceOfSatisfying(
                ResponseStatusException.class,
                exception -> {
                    assertThat(exception.getStatusCode())
                            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(exception.getReason()).isEqualTo(
                            "O conteúdo do RDO contém caractere sem representação "
                                    + "segura no PDF; nenhum conteúdo foi substituído."
                    );
                }
        );
        assertThat(generated).hasNullValue();
    }

    @Test
    void redactsSensitiveTextBeforeEmbeddingItInThePdf() throws Exception {
        RdoResponse rdo = copyRdo(
                emptyRdo("rdo-private", "RDO-PRIVATE"),
                "Contato ana@example.com, CPF 123.456.789-09, "
                        + "token=SECRET_CANARY",
                List.of()
        );
        when(queryService.buscarPorId("rdo-private")).thenReturn(rdo);

        byte[] content = service.export("rdo-private").content();

        try (PDDocument document = Loader.loadPDF(content)) {
            assertThat(new PDFTextStripper().getText(document))
                    .contains("[email removido]", "[CPF removido]", "[segredo removido]")
                    .doesNotContain(
                            "ana@example.com",
                            "123.456.789-09",
                            "SECRET_CANARY"
                    );
        }
    }

    @Test
    void allowsOnlyReviewedExportFilenameExtensions() {
        RdoExportTextSanitizer sanitizer = new RdoExportTextSanitizer();

        assertThat(sanitizer.filename("RDO-0042", "rdo-42"))
                .isEqualTo("rdo-RDO-0042.xlsx");
        assertThat(sanitizer.filename("RDO-0042", "rdo-42", ".pdf"))
                .isEqualTo("rdo-RDO-0042.pdf");
        assertThatThrownBy(
                () -> sanitizer.filename("RDO-0042", "rdo-42", ".zip")
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Extensão de exportação não permitida.");
    }

    private static RdoResponse copyRdo(
            RdoResponse original,
            String observations,
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
                original.kmFinalInterditado(), original.turno(),
                original.horaInicio(), original.horaFim(),
                original.condicaoManha(), original.condicaoTarde(),
                original.condicaoNoite(), original.pluviometriaMm(),
                original.status(), observations, original.preenchidoPor(),
                original.apontadorRdo(), original.encarregadoObra(),
                original.fiscalizacaoCampo(), original.maoObra(),
                original.equipamentos(), original.materiais(), controls,
                original.servicosExecutados(), original.alocacoesColaboradores(),
                original.attachments()
        );
    }
}
