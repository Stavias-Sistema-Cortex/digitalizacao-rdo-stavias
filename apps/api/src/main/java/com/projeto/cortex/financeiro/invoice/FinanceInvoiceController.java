package com.projeto.cortex.financeiro.invoice;

import static com.projeto.cortex.financeiro.invoice.FinanceInvoiceDtos.*;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import com.projeto.cortex.financeiro.core.FinanceAuditContext;
import com.projeto.cortex.financeiro.core.FinanceValidation;
import com.projeto.cortex.storage.ObjectStorageException;
import com.projeto.cortex.storage.StoredObjectDownload;
import com.projeto.cortex.storage.StoredObjectRecord;
import com.projeto.cortex.storage.StoredObjectService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Profile("legacy-finance")
@RestController
@RequestMapping("/api/financeiro/notas-fiscais")
public class FinanceInvoiceController {

    private static final Pattern CORRELATION = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._:-]{0,119}"
    );

    private final FinanceInvoiceService service;
    private final FinancialAccessService access;
    private final CurrentUserService currentUser;
    private final StoredObjectService storage;

    public FinanceInvoiceController(
            FinanceInvoiceService service,
            FinancialAccessService access,
            CurrentUserService currentUser,
            StoredObjectService storage
    ) {
        this.service = service;
        this.access = access;
        this.currentUser = currentUser;
        this.storage = storage;
    }

    @GetMapping
    public List<InvoiceResponse> invoices(
            @RequestParam String obraId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String responsavelId,
            @RequestParam(required = false) String fornecedorId,
            @RequestParam(required = false) String statusId,
            @RequestParam(required = false) String centroCustoId,
            @RequestParam(required = false) String categoriaId,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        access.requirePermission(
                obraId,
                FinancialPermission.FINANCEIRO_VISUALIZAR
        );
        return service.list(new FinanceInvoiceService.InvoiceFilter(
                obraId, de, ate, responsavelId, fornecedorId, statusId,
                centroCustoId, categoriaId, query, limit, offset
        ));
    }

    @GetMapping("/{id}")
    public InvoiceResponse invoice(
            @PathVariable String id,
            @RequestParam String obraId
    ) {
        access.requirePermission(
                obraId,
                FinancialPermission.FINANCEIRO_VISUALIZAR
        );
        InvoiceResponse response = service.get(id);
        requireWorksite(response.obraId(), obraId);
        return response;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceResponse createInvoice(
            @RequestBody InvoiceRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false)
            String correlationId
    ) {
        access.requirePermission(
                request == null ? null : request.obraId(),
                FinancialPermission.FINANCEIRO_OPERAR
        );
        return service.save(request, audit(correlationId));
    }

    @PutMapping("/{id}")
    public InvoiceResponse updateInvoice(
            @PathVariable String id,
            @RequestBody InvoiceRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false)
            String correlationId
    ) {
        access.requirePermission(
                request == null ? null : request.obraId(),
                FinancialPermission.FINANCEIRO_OPERAR
        );
        if (request == null || !id.equals(request.id())) {
            throw FinanceValidation.badRequest(
                    "O id da rota deve ser igual ao id da nota fiscal."
            );
        }
        return service.save(request, audit(correlationId));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archiveInvoice(
            @PathVariable String id,
            @RequestParam String obraId,
            @RequestParam long baseVersao,
            @RequestParam String clientMutationId,
            @RequestHeader(value = "X-Correlation-Id", required = false)
            String correlationId
    ) {
        access.requirePermission(
                obraId,
                FinancialPermission.FINANCEIRO_OPERAR
        );
        requireWorksite(service.get(id).obraId(), obraId);
        service.archive(
                id, baseVersao, clientMutationId, audit(correlationId)
        );
    }

    @GetMapping("/{id}/anexos")
    public List<InvoiceDocumentResponse> documents(
            @PathVariable String id,
            @RequestParam String obraId
    ) {
        access.requirePermission(
                obraId,
                FinancialPermission.FINANCEIRO_VISUALIZAR
        );
        return service.documents(id, obraId);
    }

    @PostMapping("/{id}/anexos")
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceDocumentResponse linkDocument(
            @PathVariable String id,
            @RequestParam String obraId,
            @RequestBody DocumentLinkRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false)
            String correlationId
    ) {
        access.requirePermission(
                obraId,
                FinancialPermission.FINANCEIRO_OPERAR
        );
        requireWorksite(service.get(id).obraId(), obraId);
        return service.linkDocument(id, request, audit(correlationId));
    }

    @GetMapping("/{id}/anexos/{documentId}/conteudo")
    public ResponseEntity<StreamingResponseBody> downloadDocument(
            @PathVariable String id,
            @PathVariable String documentId,
            @RequestParam String obraId
    ) {
        access.requirePermission(
                obraId,
                FinancialPermission.FINANCEIRO_VISUALIZAR
        );
        String objectId = service.documentObjectId(id, documentId, obraId);
        StoredObjectDownload download = storage.downloadAuthorized(
                objectId,
                object -> {
                    if (!objectId.equals(object.id())
                            || !obraId.equals(object.obraId())) {
                        throw new org.springframework.web.server.ResponseStatusException(
                                HttpStatus.FORBIDDEN,
                                "O documento não pertence a esta obra."
                        );
                    }
                }
        );
        return stream(download);
    }

    private ResponseEntity<StreamingResponseBody> stream(
            StoredObjectDownload download
    ) {
        StoredObjectRecord object = download.object();
        StreamingResponseBody body = output -> {
            try (download) {
                download.content().inputStream().transferTo(output);
            } catch (ObjectStorageException exception) {
                throw new IOException(
                        "Falha ao finalizar o documento fiscal.",
                        exception
                );
            }
        };
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(
                                        object.originalName(),
                                        StandardCharsets.UTF_8
                                )
                                .build()
                                .toString()
                )
                .contentType(MediaType.parseMediaType(
                        object.detectedMediaType()
                ))
                .contentLength(object.size())
                .body(body);
    }

    private void requireWorksite(String actual, String requested) {
        if (requested == null || !requested.equals(actual)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Nota fiscal não encontrada nesta obra."
            );
        }
    }

    private FinanceAuditContext audit(String correlationId) {
        String normalized = correlationId == null || correlationId.isBlank()
                ? null : correlationId.strip();
        if (normalized != null && !CORRELATION.matcher(normalized).matches()) {
            throw FinanceValidation.badRequest("X-Correlation-Id inválido.");
        }
        return FinanceAuditContext.online(
                currentUser.requireUserId(),
                normalized
        );
    }
}
