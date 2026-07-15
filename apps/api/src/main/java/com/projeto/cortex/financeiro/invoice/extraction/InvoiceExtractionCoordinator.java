package com.projeto.cortex.financeiro.invoice.extraction;

import static com.projeto.cortex.financeiro.invoice.extraction.FiscalExtractionDtos.*;

import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class InvoiceExtractionCoordinator {

    private final XmlFiscalExtractor xmlExtractor;
    private final PdfTextFiscalExtractor pdfExtractor;
    private final OcrFiscalExtractor ocrExtractor;

    public InvoiceExtractionCoordinator(
            XmlFiscalExtractor xmlExtractor,
            PdfTextFiscalExtractor pdfExtractor,
            OcrFiscalExtractor ocrExtractor
    ) {
        this.xmlExtractor = xmlExtractor;
        this.pdfExtractor = pdfExtractor;
        this.ocrExtractor = ocrExtractor;
    }

    public FiscalExtractionResult extract(FiscalDocumentSource source) {
        String mediaType = source.mediaType().toLowerCase(Locale.ROOT);
        if (xmlExtractor.supports(mediaType)) return xmlExtractor.extract(source);
        if (pdfExtractor.supports(mediaType)) {
            FiscalExtractionResult result = pdfExtractor.extract(source);
            if (result.warnings().contains("PDF_SEM_CAMADA_TEXTO")) {
                return ocrExtractor.extract(source);
            }
            return result;
        }
        if (ocrExtractor.supports(mediaType)) return ocrExtractor.extract(source);
        return new FiscalExtractionResult(
                "DESCONHECIDO", ExtractionStatus.FALHA, "COORDENADOR", "1",
                List.of(), List.of("FORMATO_NAO_SUPORTADO"),
                "NAO_VERIFICADA");
    }
}
