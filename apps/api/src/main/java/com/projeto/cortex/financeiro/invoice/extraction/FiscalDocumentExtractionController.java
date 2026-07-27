package com.projeto.cortex.financeiro.invoice.extraction;

import static com.projeto.cortex.financeiro.invoice.extraction.FiscalExtractionDtos.*;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import com.projeto.cortex.financeiro.core.FinanceAuditContext;
import com.projeto.cortex.financeiro.core.FinanceValidation;
import com.projeto.cortex.storage.StoredObjectUploadResult;
import com.projeto.cortex.storage.StoredObjectService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Profile("legacy-finance")
@RestController
public class FiscalDocumentExtractionController {

    private final FinancialAccessService access;
    private final CurrentUserService currentUser;
    private final StoredObjectService storage;
    private final FiscalDocumentExtractionService extraction;

    public FiscalDocumentExtractionController(
            FinancialAccessService access,
            CurrentUserService currentUser,
            StoredObjectService storage,
            FiscalDocumentExtractionService extraction
    ) {
        this.access = access;
        this.currentUser = currentUser;
        this.storage = storage;
        this.extraction = extraction;
    }

    @PostMapping(
            value = "/api/financeiro/notas-fiscais/extracoes",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<FiscalExtractionJobResponse> upload(
            @RequestPart("arquivo") MultipartFile file,
            @RequestParam String obraId,
            @RequestParam String clientMutationId,
            @RequestParam(required = false) String sha256Cliente,
            @RequestParam(required = false) String dispositivoId,
            @RequestHeader(value = "X-Correlation-Id", required = false)
            String correlationId
    ) {
        access.requirePermission(
                obraId,
                FinancialPermission.FINANCEIRO_OPERAR
        );
        if (file == null || file.isEmpty()) {
            throw FinanceValidation.badRequest(
                    "O arquivo fiscal é obrigatório."
            );
        }
        String actorId = currentUser.requireUserId();
        StoredObjectUploadResult stored = storage.upload(
                obraId,
                file.getOriginalFilename(),
                file.getContentType(),
                file.getSize(),
                file::getInputStream
        );
        FiscalExtractionJobResponse response = extraction.extract(
                stored.object(),
                clientMutationId,
                sha256Cliente,
                new FinanceAuditContext(
                        actorId,
                        dispositivoId,
                        correlationId,
                        "ONLINE"
                )
        );
        return ResponseEntity.status(
                response.replayed() ? HttpStatus.OK : HttpStatus.CREATED
        ).body(response);
    }
}
