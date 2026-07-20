package com.projeto.cortex.financeiro.invoice.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public final class FiscalExtractionDtos {

    private FiscalExtractionDtos() {
    }

    public enum ExtractionStatus {
        EXTRAIDO,
        REVISAO_NECESSARIA,
        FALHA
    }

    public enum CandidateValidation {
        VALIDO,
        DIVERGENTE,
        NAO_VERIFICADO
    }

    public record FiscalDocumentSource(
            String mediaType,
            String fileName,
            byte[] content
    ) {
        public FiscalDocumentSource {
            mediaType = mediaType == null ? "" : mediaType.strip();
            fileName = fileName == null ? "" : fileName.strip();
            content = content == null ? new byte[0] : content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    public record FiscalCandidate(
            String field,
            JsonNode normalizedValue,
            String originalText,
            String extractor,
            String extractorVersion,
            String location,
            BigDecimal confidence,
            CandidateValidation validation,
            String validationDetail
    ) {
    }

    public record FiscalExtractionResult(
            String format,
            ExtractionStatus status,
            String extractor,
            String extractorVersion,
            List<FiscalCandidate> candidates,
            List<String> warnings,
            String autorizacaoFiscal
    ) {
        public FiscalExtractionResult {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            autorizacaoFiscal = autorizacaoFiscal == null
                    ? "NAO_VERIFICADA"
                    : autorizacaoFiscal;
        }

        public Optional<FiscalCandidate> candidate(String field) {
            return candidates.stream()
                    .filter(candidate -> candidate.field().equals(field))
                    .findFirst();
        }
    }

    public record FiscalExtractionJobResponse(
            String id,
            String storedObjectId,
            String obraId,
            String fileName,
            String mediaType,
            long size,
            String clientMutationId,
            String format,
            ExtractionStatus status,
            String extractor,
            String extractorVersion,
            String clientSha256,
            String serverSha256,
            List<FiscalCandidate> candidates,
            List<String> warnings,
            String autorizacaoFiscal,
            String createdBy,
            String deviceId,
            String correlationId,
            LocalDateTime createdAt,
            LocalDateTime completedAt,
            boolean replayed
    ) {
        public FiscalExtractionJobResponse {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }
}
