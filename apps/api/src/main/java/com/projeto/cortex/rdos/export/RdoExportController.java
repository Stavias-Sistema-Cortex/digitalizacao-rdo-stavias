package com.projeto.cortex.rdos.export;

import com.projeto.cortex.auth.CurrentUserService;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RdoExportController {

    static final MediaType XLSX_MEDIA_TYPE = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    );
    static final MediaType PDF_MEDIA_TYPE = MediaType.APPLICATION_PDF;

    private final RdoXlsxExportService exportService;
    private final RdoPdfExportService pdfExportService;
    private final CurrentUserService currentUserService;

    public RdoExportController(
            RdoXlsxExportService exportService,
            RdoPdfExportService pdfExportService,
            CurrentUserService currentUserService
    ) {
        this.exportService = exportService;
        this.pdfExportService = pdfExportService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/api/rdos/{id}/export.xlsx")
    public ResponseEntity<byte[]> export(@PathVariable String id) {
        currentUserService.requireRdoAccess(id);
        RdoExportFile exported = exportService.export(id);
        return attachment(
                exported.content(),
                exported.filename(),
                XLSX_MEDIA_TYPE
        );
    }

    @GetMapping("/api/rdos/{id}/export.pdf")
    public ResponseEntity<byte[]> exportPdf(@PathVariable String id) {
        currentUserService.requireRdoAccess(id);
        RdoExportFile exported = pdfExportService.export(id);
        return attachment(
                exported.content(),
                exported.filename(),
                PDF_MEDIA_TYPE
        );
    }

    private ResponseEntity<byte[]> attachment(
            byte[] content,
            String filename,
            MediaType mediaType
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(filename)
                                .build()
                                .toString()
                )
                .contentType(mediaType)
                .contentLength(content.length)
                .body(content);
    }
}
