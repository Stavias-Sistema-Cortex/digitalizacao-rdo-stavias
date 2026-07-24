package com.projeto.cortex.rdos;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Domain boundary for the fixed row capacity of the official RDO workbook.
 */
public final class RdoPrintableCollectionLimits {

    private static final int MAX_WORKFORCE_ROWS = 26;
    private static final int MAX_SERVICE_ROWS = 21;
    private static final int MAX_MATERIAL_ROWS = 30;
    private static final int MAX_ATTACHMENT_ROWS = 5;

    private RdoPrintableCollectionLimits() {
    }

    public static void requireWithinTemplateCapacity(RdoCreateRequest request) {
        if (request == null) {
            return;
        }
        requireLimit(request.maoObra(), MAX_WORKFORCE_ROWS, "maoObra");
        requireLimit(
                request.servicosExecutados(),
                MAX_SERVICE_ROWS,
                "servicosExecutados"
        );
        requireLimit(request.materiais(), MAX_MATERIAL_ROWS, "materiais");
        requireLimit(request.attachments(), MAX_ATTACHMENT_ROWS, "attachments");
    }

    private static void requireLimit(List<?> values, int limit, String field) {
        if (values != null && values.size() > limit) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    field + " excede o limite permitido."
            );
        }
    }
}
