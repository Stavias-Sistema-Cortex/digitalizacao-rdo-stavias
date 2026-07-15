package com.projeto.cortex.financeiro.invoice.extraction;

import static com.projeto.cortex.financeiro.invoice.extraction.FiscalExtractionDtos.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class PdfAndOcrFiscalExtractorTest {

    @Test
    void extractsTextPdfAsReviewableCandidatesWithPageEvidence()
            throws Exception {
        PdfTextFiscalExtractor extractor = new PdfTextFiscalExtractor();

        FiscalExtractionResult result = extractor.extract(new FiscalDocumentSource(
                "application/pdf",
                "danfe.pdf",
                textPdf("Numero: 125 Serie: 3 Emissao: 14/07/2026 "
                        + "CNPJ: 12.345.678/0001-95 Valor bruto: 100,00 "
                        + "Desconto: 10,00 Acrescimo: 5,00 Valor total: 95,00")
        ));

        assertThat(result.status())
                .isEqualTo(ExtractionStatus.REVISAO_NECESSARIA);
        assertThat(result.candidate("numero").orElseThrow()
                .normalizedValue().asText()).isEqualTo("125");
        assertThat(result.candidate("valorLiquido").orElseThrow().location())
                .contains("pagina:1");
        assertThat(result.autorizacaoFiscal()).isEqualTo("NAO_VERIFICADA");
    }

    @Test
    void usesConfiguredOcrForImagesAndReportsUnavailableProviderHonestly() {
        OcrFiscalExtractor available = new OcrFiscalExtractor(
                new OcrFiscalExtractor.OcrProvider() {
                    @Override
                    public boolean available() {
                        return true;
                    }

                    @Override
                    public OcrFiscalExtractor.OcrRecognition recognize(
                            FiscalDocumentSource source
                    ) {
                        return new OcrFiscalExtractor.OcrRecognition(
                                "Numero: 9 Serie: 1 Emissao: 14/07/2026 "
                                        + "Valor bruto: 50,00 Valor total: 50,00",
                                List.of("pagina:1")
                        );
                    }
                }
        );
        OcrFiscalExtractor unavailable = new OcrFiscalExtractor(null);
        FiscalDocumentSource image = new FiscalDocumentSource(
                "image/png",
                "nota.png",
                new byte[] {(byte) 0x89, 'P', 'N', 'G'}
        );

        assertThat(available.extract(image).candidate("valorLiquido"))
                .isPresent();
        FiscalExtractionResult pending = unavailable.extract(image);
        assertThat(pending.status())
                .isEqualTo(ExtractionStatus.REVISAO_NECESSARIA);
        assertThat(pending.candidates()).isEmpty();
        assertThat(pending.warnings()).contains("OCR_INDISPONIVEL");
    }

    @Test
    void coordinatorRejectsZipAndRoutesImagesOnlyToOcr() {
        InvoiceExtractionCoordinator coordinator =
                new InvoiceExtractionCoordinator(
                        new XmlFiscalExtractor(),
                        new PdfTextFiscalExtractor(),
                        new OcrFiscalExtractor(null)
                );

        FiscalExtractionResult zip = coordinator.extract(
                new FiscalDocumentSource(
                        "application/zip",
                        "notas.zip",
                        "PK".getBytes(StandardCharsets.US_ASCII)
                )
        );
        FiscalExtractionResult image = coordinator.extract(
                new FiscalDocumentSource(
                        "image/jpeg",
                        "nota.jpg",
                        new byte[] {(byte) 0xff, (byte) 0xd8}
                )
        );

        assertThat(zip.status()).isEqualTo(ExtractionStatus.FALHA);
        assertThat(zip.warnings()).contains("FORMATO_NAO_SUPORTADO");
        assertThat(image.warnings()).contains("OCR_INDISPONIVEL");
    }

    @Test
    void coordinatorRoutesScannedPdfToConfiguredOcr() throws Exception {
        OcrFiscalExtractor ocr = new OcrFiscalExtractor(
                new OcrFiscalExtractor.OcrProvider() {
                    @Override
                    public boolean available() {
                        return true;
                    }

                    @Override
                    public OcrFiscalExtractor.OcrRecognition recognize(
                            FiscalDocumentSource source
                    ) {
                        return new OcrFiscalExtractor.OcrRecognition(
                                "Numero: 71 Valor bruto: 80,00 "
                                        + "Valor total: 80,00",
                                List.of("pagina:1")
                        );
                    }
                }
        );
        InvoiceExtractionCoordinator coordinator =
                new InvoiceExtractionCoordinator(
                        new XmlFiscalExtractor(),
                        new PdfTextFiscalExtractor(),
                        ocr
                );

        FiscalExtractionResult result = coordinator.extract(
                new FiscalDocumentSource(
                        "application/pdf",
                        "digitalizada.pdf",
                        blankPdf()
                )
        );

        assertThat(result.status())
                .isEqualTo(ExtractionStatus.REVISAO_NECESSARIA);
        assertThat(result.extractor()).isEqualTo("OCR");
        assertThat(result.candidate("numero").orElseThrow()
                .normalizedValue().asText()).isEqualTo("71");
    }

    private byte[] textPdf(String text) throws Exception {
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(
                    document,
                    page
            )) {
                content.beginText();
                content.setFont(
                        new PDType1Font(Standard14Fonts.FontName.HELVETICA),
                        10
                );
                content.newLineAtOffset(40, 740);
                content.showText(text);
                content.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private byte[] blankPdf() throws Exception {
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            return output.toByteArray();
        }
    }
}
