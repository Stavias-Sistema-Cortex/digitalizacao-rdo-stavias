package com.projeto.cortex.rdos.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Service;

@Service
public class RdoPdfExportService {

    private static final int MAX_PDF_BYTES = 5 * 1024 * 1024;
    private static final int INITIAL_PDF_CAPACITY = 64 * 1024;

    private final RdoExportAggregateFactory aggregateFactory;
    private final RdoExportTextSanitizer sanitizer =
            new RdoExportTextSanitizer();

    public RdoPdfExportService(RdoExportAggregateFactory aggregateFactory) {
        this.aggregateFactory = aggregateFactory;
    }

    public RdoExportFile export(String rdoId) {
        RdoExportAggregate aggregate = aggregateFactory.load(rdoId);
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(PDRectangle.A4));
            document.addPage(new PDPage(PDRectangle.A4));
            PDImageXObject corporateWordmark =
                    PDImageXObject.createFromByteArray(
                            document,
                            RdoPdfCorporateWordmark.bytes(),
                            "corporate-wordmark"
                    );
            RdoPdfFormRenderer renderer = new RdoPdfFormRenderer(
                    new PDType1Font(Standard14Fonts.FontName.HELVETICA),
                    new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD),
                    corporateWordmark
            );
            renderer.render(document, aggregate);

            BoundedByteArrayOutputStream output =
                    new BoundedByteArrayOutputStream(
                            INITIAL_PDF_CAPACITY,
                            MAX_PDF_BYTES
                    );
            document.save(output);
            return new RdoExportFile(
                    output.toByteArray(),
                    sanitizer.filename(
                            aggregate.rdo().numeroRdo(),
                            aggregate.rdo().id(),
                            ".pdf"
                    )
            );
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Não foi possível gerar o PDF do RDO.",
                    exception
            );
        }
    }

    private static final class BoundedByteArrayOutputStream
            extends ByteArrayOutputStream {

        private final int limit;

        private BoundedByteArrayOutputStream(int initialCapacity, int limit) {
            super(initialCapacity);
            this.limit = limit;
        }

        @Override
        public synchronized void write(int value) {
            ensureCapacityWithinLimit(1);
            super.write(value);
        }

        @Override
        public synchronized void write(byte[] value, int offset, int length) {
            ensureCapacityWithinLimit(length);
            super.write(value, offset, length);
        }

        private void ensureCapacityWithinLimit(int additionalBytes) {
            if (additionalBytes < 0 || count > limit - additionalBytes) {
                throw new IllegalStateException(
                        "O PDF do RDO excede o limite seguro de geração."
                );
            }
        }
    }
}
