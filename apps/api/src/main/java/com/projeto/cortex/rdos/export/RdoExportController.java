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

    private final RdoXlsxExportService exportService;
    private final CurrentUserService currentUserService;

    public RdoExportController(
            RdoXlsxExportService exportService,
            CurrentUserService currentUserService
    ) {
        this.exportService = exportService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/api/rdos/{id}/export.xlsx")
    public ResponseEntity<byte[]> export(@PathVariable String id) {
        currentUserService.requireRdoAccess(id);
        RdoXlsxExportService.ExportedRdo exported = exportService.export(id);
        byte[] content = exported.content();

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(exported.filename())
                                .build()
                                .toString()
                )
                .contentType(XLSX_MEDIA_TYPE)
                .contentLength(content.length)
                .body(content);
    }
}
