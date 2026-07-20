package com.projeto.cortex.financeiro.invoice.extraction;

import static com.projeto.cortex.financeiro.invoice.extraction.FiscalExtractionDtos.*;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class OcrFiscalExtractor implements FiscalDocumentExtractor {

    private static final String EXTRACTOR = "OCR";
    private static final String VERSION = "1";
    private final OcrProvider provider;

    @Autowired
    private OcrFiscalExtractor(ObjectProvider<OcrProvider> provider) {
        this(provider.getIfAvailable());
    }

    public OcrFiscalExtractor(OcrProvider provider) {
        this.provider = provider;
    }

    @Override
    public boolean supports(String mediaType) {
        return mediaType != null && mediaType.toLowerCase().startsWith("image/");
    }

    @Override
    public FiscalExtractionResult extract(FiscalDocumentSource source) {
        if (provider == null || !provider.available()) {
            return result(
                    ExtractionStatus.REVISAO_NECESSARIA,
                    List.of("OCR_INDISPONIVEL"));
        }
        try {
            OcrRecognition recognition = provider.recognize(source);
            if (recognition == null || recognition.text() == null
                    || recognition.text().isBlank()) {
                return result(
                        ExtractionStatus.REVISAO_NECESSARIA,
                        List.of("OCR_SEM_TEXTO"));
            }
            return FiscalTextCandidateParser.parse(
                    "IMAGEM", EXTRACTOR, VERSION, recognition.text(),
                    recognition.locations(), new BigDecimal("0.700000"));
        } catch (Exception exception) {
            return result(ExtractionStatus.FALHA, List.of("OCR_FALHOU"));
        }
    }

    private FiscalExtractionResult result(
            ExtractionStatus status,
            List<String> warnings
    ) {
        return new FiscalExtractionResult(
                "IMAGEM", status, EXTRACTOR, VERSION,
                List.of(), warnings, "NAO_VERIFICADA");
    }

    public interface OcrProvider {
        boolean available();

        OcrRecognition recognize(FiscalDocumentSource source) throws Exception;
    }

    public record OcrRecognition(String text, List<String> locations) {
        public OcrRecognition {
            locations = locations == null ? List.of() : List.copyOf(locations);
        }
    }
}
