package com.projeto.cortex.rdos.export;

import com.projeto.cortex.rdos.RdoQueryService;
import com.projeto.cortex.rdos.RdoResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RdoExportAggregateFactory {

    private static final int MAX_WORKFORCE_GROUPS = 26;
    private static final int MAX_EQUIPMENT = 32;
    private static final int MAX_WORKED_ROWS = 21;
    private static final int MAX_MATERIAL_ROWS = 30;
    private static final int MAX_GEOMETRY_ROWS = 36;
    private static final int MAX_CELL_TEXT_LENGTH = 32_767;
    private static final Pattern UUID_TEXT = Pattern.compile(
            "^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$",
            Pattern.CASE_INSENSITIVE
    );

    private final RdoQueryService queryService;
    private final RdoExportWorksiteReader worksiteReader;
    private final RdoExportTextSanitizer sanitizer =
            new RdoExportTextSanitizer();

    public RdoExportAggregateFactory(
            RdoQueryService queryService,
            RdoExportWorksiteReader worksiteReader
    ) {
        this.queryService = queryService;
        this.worksiteReader = worksiteReader;
    }

    RdoExportAggregate load(String rdoId) {
        RdoResponse rdo = queryService.buscarPorId(rdoId);
        String previousRdoNumber = resolvePreviousRdoNumber(rdo);
        RdoExportWorksiteReader.Worksite worksite =
                worksiteReader.read(rdo.obraId());
        List<WorkforceGroup> workforce = groupWorkforce(rdo.maoObra());
        List<RdoResponse.EquipamentoItem> equipment = copy(rdo.equipamentos());
        List<WorkedRow> worked = buildWorkedRows(rdo);
        List<MaterialRow> materials = buildMaterialRows(rdo);
        List<RdoResponse.ControleGeometricoItem> geometry =
                copy(rdo.controlesGeometricos());

        validateCoverage(workforce, equipment, worked, materials, geometry);
        validateDomainValues(rdo, equipment);
        String apontadorName = selectedApontadorName(rdo);
        String observations = validatePrintableText(
                rdo,
                worksite,
                previousRdoNumber,
                workforce,
                equipment,
                worked,
                materials,
                geometry,
                apontadorName
        );

        return new RdoExportAggregate(
                rdo,
                worksite,
                previousRdoNumber,
                List.copyOf(workforce),
                List.copyOf(equipment),
                List.copyOf(worked),
                List.copyOf(materials),
                List.copyOf(geometry),
                observations,
                apontadorName
        );
    }

    private List<WorkedRow> buildWorkedRows(RdoResponse rdo) {
        List<WorkedRow> worked = new ArrayList<>();
        for (RdoResponse.ControleGeometricoItem control
                : copy(rdo.controlesGeometricos())) {
            worked.add(WorkedRow.fromControl(control));
        }
        for (RdoResponse.ServicoExecutadoItem service
                : copy(rdo.servicosExecutados())) {
            worked.add(WorkedRow.fromService(service));
        }
        return worked;
    }

    private List<MaterialRow> buildMaterialRows(RdoResponse rdo) {
        List<MaterialRow> materials = new ArrayList<>();
        for (RdoResponse.MaterialItem material : copy(rdo.materiais())) {
            addMaterialRows(materials, material);
        }
        return materials;
    }

    private void validateCoverage(
            List<WorkforceGroup> workforce,
            List<RdoResponse.EquipamentoItem> equipment,
            List<WorkedRow> worked,
            List<MaterialRow> materials,
            List<RdoResponse.ControleGeometricoItem> geometry
    ) {
        rejectOverflow(
                "grupos de mão de obra",
                workforce.size(),
                MAX_WORKFORCE_GROUPS
        );
        rejectOverflow(
                "equipamentos/veículos",
                equipment.size(),
                MAX_EQUIPMENT
        );
        rejectOverflow(
                "trechos/serviços",
                worked.size(),
                MAX_WORKED_ROWS
        );
        rejectOverflow(
                "linhas de materiais",
                materials.size(),
                MAX_MATERIAL_ROWS
        );
        rejectOverflow(
                "controles geométricos",
                geometry.size(),
                MAX_GEOMETRY_ROWS
        );
    }

    private void validateDomainValues(
            RdoResponse rdo,
            List<RdoResponse.EquipamentoItem> equipment
    ) {
        validateWeather(rdo.condicaoManha());
        validateWeather(rdo.condicaoTarde());
        validateWeather(rdo.condicaoNoite());
        for (RdoResponse.EquipamentoItem item : equipment) {
            validateEquipmentOwnership(item.tipoVinculo());
            requireDescription(item.descricao(), "equipamento/veículo");
        }
    }

    private void validateWeather(String value) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            return;
        }
        switch (normalized) {
            case "BOM", "NUBLADO", "CHUVA", "IMPOSSIBILITADO",
                    "NAO_APLICAVEL" -> {
                return;
            }
            default -> throw invalidWeather(value);
        }
    }

    private ResponseStatusException invalidWeather(String value) {
        return new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "condição climática não reconhecida: " + value
                        + ". Nenhuma condição 'Bom' foi inventada."
        );
    }

    private String validatePrintableText(
            RdoResponse rdo,
            RdoExportWorksiteReader.Worksite worksite,
            String previousRdoNumber,
            List<WorkforceGroup> workforce,
            List<RdoResponse.EquipamentoItem> equipment,
            List<WorkedRow> worked,
            List<MaterialRow> materials,
            List<RdoResponse.ControleGeometricoItem> geometry,
            String apontadorName
    ) {
        printable("nome da obra", worksite.name(), 56);
        printable("código da obra", worksite.code(), 18);
        printable("número do RDO", firstNonBlank(rdo.numeroRdo(), rdo.id()), 20);
        printable("rodovia", rdo.rodovia(), 18);
        printable("dia da semana", firstNonBlank(
                rdo.diaSemana(),
                weekday(rdo.dataRdo())
        ), 16);
        printable("km inicial programado", rdo.kmInicialProgramado(), 12);
        printable("km final programado", rdo.kmFinalProgramado(), 12);
        printable("km inicial interditado", rdo.kmInicialInterditado(), 12);
        printable("km final interditado", rdo.kmFinalInterditado(), 12);
        printable("nome do apontador", apontadorName, 40);
        printable("nome do encarregado", rdo.encarregadoObra(), 40);
        printable("nome da fiscalização", rdo.fiscalizacaoCampo(), 40);

        for (WorkforceGroup group : workforce) {
            printable("cargo", group.role(), 18);
        }
        for (RdoResponse.EquipamentoItem item : equipment) {
            printable("descrição do equipamento", item.descricao(), 24);
            printable("prefixo do equipamento", item.prefixo(), 8);
        }
        for (RdoResponse.MaterialItem item : copy(rdo.materiais())) {
            printable("material", item.materialNome(), 24);
            printable("unidade do material", item.unidade(), 5);
            printable("nota fiscal", item.notaFiscal(), 24);
        }
        for (MaterialRow row : materials) {
            printable("descrição da linha de material", row.description(), 28);
        }
        for (WorkedRow row : worked) {
            printable("início do trecho", row.start(), 20);
            printable("fim do trecho", row.end(), 20);
            printable("número do trecho", row.number(), 12);
            printable("pista", row.roadway(), 16);
            printable("faixa", row.lane(), 16);
            printable("ordem de serviço", row.serviceOrder(), 30);
            printable("atividade executada", row.activity(), 80);
        }
        for (RdoResponse.ControleGeometricoItem control : geometry) {
            printable("subtrecho do controle geométrico", control.subtrecho(), 32);
        }
        String observations = allObservations(rdo, previousRdoNumber);
        printableObservations(observations);
        return observations;
    }

    private String resolvePreviousRdoNumber(RdoResponse rdo) {
        if (rdo.previousRdoId() == null || rdo.previousRdoId().isBlank()) {
            return "";
        }
        try {
            RdoResponse previous = queryService.buscarPorId(rdo.previousRdoId());
            if (previous == null
                    || rdo.obraId() == null
                    || !rdo.obraId().equals(previous.obraId())) {
                return "";
            }
            String candidate = previous.numeroRdo() == null
                    ? ""
                    : previous.numeroRdo().trim();
            if (candidate.equals(rdo.previousRdoId())
                    || UUID_TEXT.matcher(candidate).matches()) {
                return "";
            }
            return candidate;
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode().equals(HttpStatus.NOT_FOUND)) {
                return "";
            }
            throw exception;
        }
    }

    private void printable(String section, String value, int maxCodePoints) {
        if (value == null || value.isBlank()) {
            return;
        }
        String sanitized = safeText(value);
        int length = sanitized.codePointCount(0, sanitized.length());
        if (sanitized.contains("\n")
                || sanitized.contains("\r")
                || length > maxCodePoints) {
            throw printOverflow(
                    section,
                    "limite de " + maxCodePoints + " caracteres em uma linha"
            );
        }
    }

    private void printableObservations(String observations) {
        if (observations == null || observations.isBlank()) {
            return;
        }
        List<String> lines = observations.lines().toList();
        if (lines.size() > 6) {
            throw printOverflow(
                    "observações gerais",
                    "limite de 6 linhas"
            );
        }
        for (String line : lines) {
            int length = line.codePointCount(0, line.length());
            if (length > 100) {
                throw printOverflow(
                        "observações gerais",
                        "limite de 100 caracteres por linha"
                );
            }
        }
    }

    private ResponseStatusException printOverflow(String section, String limit) {
        return new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "O conteúdo de " + section + " não permanece legível no RDO ("
                        + limit + "); nenhum conteúdo foi truncado."
        );
    }

    private void rejectOverflow(String section, int received, int capacity) {
        if (received <= capacity) {
            return;
        }
        throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "O template RDO v1 comporta " + capacity + " " + section
                        + ", mas o RDO possui " + received
                        + "; nenhum item foi truncado."
        );
    }

    private String allObservations(
            RdoResponse rdo,
            String previousRdoNumber
    ) {
        List<String> entries = new ArrayList<>();
        boolean carriesPreviousWorkforce = rdo.previousRdoId() != null
                && copy(rdo.maoObra()).stream()
                        .anyMatch(item -> item.origemItemId() != null
                                && !item.origemItemId().isBlank());
        if (carriesPreviousWorkforce
                && previousRdoNumber != null
                && !previousRdoNumber.isBlank()) {
            entries.add("Continuidade da equipe: mão de obra importada do RDO "
                    + safeText(previousRdoNumber));
        }
        addCloudObservation(entries, "manhã", rdo.condicaoManha());
        addCloudObservation(entries, "tarde", rdo.condicaoTarde());
        addCloudObservation(entries, "noite", rdo.condicaoNoite());
        addObservation(entries, "RDO", rdo.observacoes());
        for (RdoResponse.MaoObraItem item : copy(rdo.maoObra())) {
            addObservation(
                    entries,
                    "Mão de obra " + safeText(item.cargo()),
                    item.observacoes()
            );
        }
        for (RdoResponse.EquipamentoItem item : copy(rdo.equipamentos())) {
            addObservation(
                    entries,
                    "Equipamento " + safeText(item.descricao()),
                    item.observacoes()
            );
        }
        for (RdoResponse.MaterialItem item : copy(rdo.materiais())) {
            addObservation(
                    entries,
                    "Material " + safeText(item.materialNome()),
                    item.observacoes()
            );
        }
        for (RdoResponse.ControleGeometricoItem item
                : copy(rdo.controlesGeometricos())) {
            addObservation(
                    entries,
                    "Controle " + safeText(item.subtrecho()),
                    item.observacoes()
            );
        }
        for (RdoResponse.ServicoExecutadoItem item
                : copy(rdo.servicosExecutados())) {
            addObservation(
                    entries,
                    "Serviço " + safeText(item.servicoNome()),
                    item.observacoes()
            );
        }
        String result = String.join("\n", entries);
        if (result.length() > MAX_CELL_TEXT_LENGTH) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "As observações do RDO excedem " + MAX_CELL_TEXT_LENGTH
                            + " caracteres; nenhum conteúdo foi truncado."
            );
        }
        return result;
    }

    private void addCloudObservation(
            List<String> entries,
            String period,
            String condition
    ) {
        if ("NUBLADO".equals(normalize(condition))) {
            entries.add("Clima " + period + ": Nublado");
        }
    }

    private void addObservation(List<String> entries, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        entries.add(safeText(label) + ": " + safeText(value));
    }

    private String selectedApontadorName(RdoResponse rdo) {
        if (rdo.apontadorRdo() != null && !rdo.apontadorRdo().isBlank()) {
            return rdo.apontadorRdo();
        }
        if (rdo.apontadorColaboradorId() == null) {
            return null;
        }
        return copy(rdo.maoObra()).stream()
                .filter(item -> rdo.apontadorColaboradorId()
                        .equals(item.colaboradorId()))
                .map(RdoResponse.MaoObraItem::nomeColaborador)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
    }

    private void addMaterialRows(
            List<MaterialRow> rows,
            RdoResponse.MaterialItem material
    ) {
        requireDescription(material.materialNome(), "material");
        int initialSize = rows.size();
        addMaterialRow(rows, material, "U", material.quantidadeUsinada());
        addMaterialRow(rows, material, "A", material.quantidadeAplicada());
        addMaterialRow(rows, material, "S", material.quantidadeSobra());
        if (rows.size() == initialSize) {
            addMaterialRow(rows, material, "P", material.quantidadePrevista());
        }
        if (rows.size() == initialSize) {
            rows.add(new MaterialRow(
                    material.materialNome(),
                    null,
                    material.unidade(),
                    material.notaFiscal()
            ));
        }
    }

    private void addMaterialRow(
            List<MaterialRow> rows,
            RdoResponse.MaterialItem material,
            String measure,
            BigDecimal quantity
    ) {
        if (quantity == null) {
            return;
        }
        rows.add(new MaterialRow(
                material.materialNome().trim() + " (" + measure + ")",
                quantity,
                material.unidade(),
                material.notaFiscal()
        ));
    }

    private List<WorkforceGroup> groupWorkforce(
            List<RdoResponse.MaoObraItem> items
    ) {
        Map<String, WorkforceGroup> grouped = new LinkedHashMap<>();
        for (RdoResponse.MaoObraItem item : copy(items)) {
            String role = firstNonBlank(item.cargo(), item.nomeColaborador());
            if (role == null || role.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "Há mão de obra sem cargo ou nome; nenhum item foi truncado."
                );
            }
            boolean subcontracted = isSubcontracted(item.tipoVinculo());
            String key = normalize(role) + "|" + subcontracted;
            WorkforceGroup previous = grouped.get(key);
            BigDecimal quantity = nonNull(item.quantidade());
            grouped.put(key, previous == null
                    ? new WorkforceGroup(role, subcontracted, quantity)
                    : new WorkforceGroup(
                            previous.role(),
                            subcontracted,
                            previous.quantity().add(quantity)
                    ));
        }
        return List.copyOf(grouped.values());
    }

    private boolean isSubcontracted(String value) {
        String normalized = normalize(value);
        return normalized.contains("SUBCONTRAT")
                || normalized.contains("TERCEIR");
    }

    private void validateEquipmentOwnership(String value) {
        switch (normalize(value)) {
            case "PROPRIO", "LOCADO", "TERCEIRIZADO" -> {
                return;
            }
            default -> throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "vínculo de equipamento não reconhecido: " + value
                            + ". Nenhuma categoria foi inventada."
            );
        }
    }

    private void requireDescription(String value, String section) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Há " + section
                            + " sem descrição; nenhum item foi truncado."
            );
        }
    }

    private String safeText(String value) {
        return sanitizer.cellText(value);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String weekday(LocalDate date) {
        return date == null
                ? null
                : date.getDayOfWeek().getDisplayName(
                        java.time.format.TextStyle.FULL,
                        Locale.forLanguageTag("pt-BR")
                );
    }

    private BigDecimal nonNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
