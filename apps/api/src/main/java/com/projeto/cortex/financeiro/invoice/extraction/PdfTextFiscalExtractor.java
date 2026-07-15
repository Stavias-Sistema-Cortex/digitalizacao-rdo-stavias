package com.projeto.cortex.financeiro.invoice.extraction;

import static com.projeto.cortex.financeiro.invoice.extraction.FiscalExtractionDtos.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

@Component
public class PdfTextFiscalExtractor implements FiscalDocumentExtractor {

    private static final String EXTRACTOR = "PDF_TEXT";
    private static final String VERSION = "1";

    @Override
    public boolean supports(String mediaType) {
        return "application/pdf".equalsIgnoreCase(mediaType);
    }

    @Override
    public FiscalExtractionResult extract(FiscalDocumentSource source) {
        try (PDDocument document = Loader.loadPDF(source.content())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            if (text == null || text.isBlank()) {
                return new FiscalExtractionResult(
                        "PDF", ExtractionStatus.REVISAO_NECESSARIA,
                        EXTRACTOR, VERSION, List.of(),
                        List.of("PDF_SEM_CAMADA_TEXTO"), "NAO_VERIFICADA");
            }
            List<String> locations = new ArrayList<>();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                locations.add("pagina:" + page);
            }
            return FiscalTextCandidateParser.parse(
                    "PDF", EXTRACTOR, VERSION, text, locations,
                    new BigDecimal("0.820000"));
        } catch (Exception exception) {
            return new FiscalExtractionResult(
                    "PDF", ExtractionStatus.FALHA, EXTRACTOR, VERSION,
                    List.of(), List.of("PDF_INVALIDO"), "NAO_VERIFICADA");
        }
    }
}
