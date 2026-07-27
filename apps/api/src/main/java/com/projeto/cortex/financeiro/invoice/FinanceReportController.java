package com.projeto.cortex.financeiro.invoice;

import static com.projeto.cortex.financeiro.invoice.FinanceInvoiceDtos.*;

import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import java.time.LocalDate;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("legacy-finance")
@RestController
@RequestMapping("/api/financeiro")
public class FinanceReportController {

    private final FinanceReportService service;
    private final FinancialAccessService access;

    public FinanceReportController(
            FinanceReportService service,
            FinancialAccessService access
    ) {
        this.service = service;
        this.access = access;
    }

    @GetMapping("/visao-geral")
    public OverviewResponse overview(
            @RequestParam String obraId,
            @RequestParam String moeda,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String tipo,
            @RequestParam(required = false) String statusId,
            @RequestParam(required = false) String fornecedorId,
            @RequestParam(required = false) String responsavelId,
            @RequestParam(required = false) String centroCustoId,
            @RequestParam(required = false) String categoriaId,
            @RequestParam(required = false) String query,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate referencia
    ) {
        requireView(obraId);
        return service.overview(filter(
                obraId, moeda, de, ate, tipo, statusId, fornecedorId,
                responsavelId, centroCustoId, categoriaId, query, referencia
        ));
    }

    @GetMapping("/relatorios")
    public List<ReportRow> report(
            @RequestParam String obraId,
            @RequestParam String moeda,
            @RequestParam String agruparPor,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String tipo,
            @RequestParam(required = false) String statusId,
            @RequestParam(required = false) String fornecedorId,
            @RequestParam(required = false) String responsavelId,
            @RequestParam(required = false) String centroCustoId,
            @RequestParam(required = false) String categoriaId,
            @RequestParam(required = false) String query,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate referencia
    ) {
        requireView(obraId);
        return service.report(agruparPor, filter(
                obraId, moeda, de, ate, tipo, statusId, fornecedorId,
                responsavelId, centroCustoId, categoriaId, query, referencia
        ));
    }

    @GetMapping("/relatorios/exportacoes")
    public ResponseEntity<byte[]> export(
            @RequestParam String obraId,
            @RequestParam String moeda,
            @RequestParam String agruparPor,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) String tipo,
            @RequestParam(required = false) String statusId,
            @RequestParam(required = false) String fornecedorId,
            @RequestParam(required = false) String responsavelId,
            @RequestParam(required = false) String centroCustoId,
            @RequestParam(required = false) String categoriaId,
            @RequestParam(required = false) String query,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate referencia
    ) {
        requireView(obraId);
        byte[] content = service.exportCsv(agruparPor, filter(
                obraId, moeda, de, ate, tipo, statusId, fornecedorId,
                responsavelId, centroCustoId, categoriaId, query, referencia
        ));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename("relatorio-financeiro.csv")
                                .build().toString()
                )
                .contentType(new MediaType("text", "csv"))
                .contentLength(content.length)
                .body(content);
    }

    private FinanceReportService.ReportFilter filter(
            String obraId,
            String moeda,
            LocalDate de,
            LocalDate ate,
            String tipo,
            String statusId,
            String fornecedorId,
            String responsavelId,
            String centroCustoId,
            String categoriaId,
            String query,
            LocalDate referencia
    ) {
        return new FinanceReportService.ReportFilter(
                obraId, moeda, de, ate, tipo, statusId, fornecedorId,
                responsavelId, centroCustoId, categoriaId, query, referencia
        );
    }

    private void requireView(String worksiteId) {
        access.requirePermission(
                worksiteId,
                FinancialPermission.FINANCEIRO_VISUALIZAR
        );
    }
}
