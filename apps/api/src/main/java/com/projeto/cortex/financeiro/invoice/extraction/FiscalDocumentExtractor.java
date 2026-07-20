package com.projeto.cortex.financeiro.invoice.extraction;

import static com.projeto.cortex.financeiro.invoice.extraction.FiscalExtractionDtos.*;

public interface FiscalDocumentExtractor {

    boolean supports(String mediaType);

    FiscalExtractionResult extract(FiscalDocumentSource source);
}
