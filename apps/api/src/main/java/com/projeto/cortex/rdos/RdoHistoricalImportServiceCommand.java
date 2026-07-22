package com.projeto.cortex.rdos;

import java.math.BigDecimal;

/**
 * Trusted internal command for a service read from the historical RDO importer.
 * It is deliberately separate from {@link RdoCreateRequest} so public RDO JSON
 * cannot opt into historical, unpriced revenue coverage.
 */
public record RdoHistoricalImportServiceCommand(
        String rdoId,
        String obraId,
        String importId,
        String fileName,
        String sheetName,
        Integer rowNumber,
        String executionId,
        String serviceName,
        String itemContractId,
        BigDecimal quantity,
        String unit,
        String initialSegment,
        String finalSegment,
        String location,
        String turn,
        String observations
) {
}
