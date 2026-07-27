package com.projeto.cortex.rdos.export;

import com.projeto.cortex.rdos.RdoResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

record RdoExportAggregate(
        RdoResponse rdo,
        RdoExportWorksiteReader.Worksite worksite,
        String previousRdoNumber,
        List<WorkforceGroup> workforce,
        List<RdoResponse.EquipamentoItem> equipment,
        List<WorkedRow> worked,
        List<MaterialRow> materials,
        List<RdoResponse.ControleGeometricoItem> geometry,
        String observations,
        String apontadorName
) {
}

record WorkforceGroup(
        String role,
        boolean subcontracted,
        BigDecimal quantity
) {
}

record MaterialRow(
        String description,
        BigDecimal quantity,
        String unit,
        String invoice
) {
}

record WorkedRow(
        String start,
        String end,
        String number,
        BigDecimal length,
        BigDecimal width,
        BigDecimal thicknessCm,
        String roadway,
        String lane,
        String serviceOrder,
        String activity
) {

    static WorkedRow fromControl(RdoResponse.ControleGeometricoItem value) {
        return new WorkedRow(
                firstNonBlank(
                        value.estacaInicial(),
                        value.kmInicial(),
                        value.subtrecho()
                ),
                firstNonBlank(value.estacaFinal(), value.kmFinal()),
                value.numero(),
                value.comprimentoM(),
                value.larguraM(),
                value.espessuraMediaCm(),
                value.pista(),
                value.faixa(),
                value.ordemServico(),
                firstNonBlank(value.atividadeObservacoes(), value.observacoes())
        );
    }

    static WorkedRow fromService(RdoResponse.ServicoExecutadoItem value) {
        String quantity = value.quantidadeExecutada() == null
                ? ""
                : value.quantidadeExecutada().stripTrailingZeros().toPlainString()
                        + (value.unidade() == null || value.unidade().isBlank()
                        ? "" : " " + value.unidade());
        String activity = joinNonBlank(
                value.servicoNome(),
                quantity.isBlank() ? null : "Quantidade: " + quantity
        );
        return new WorkedRow(
                value.trechoInicial(),
                value.trechoFinal(),
                null,
                null,
                null,
                null,
                value.localizacao(),
                null,
                null,
                activity
        );
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String joinNonBlank(String... values) {
        List<String> parts = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                parts.add(value.trim());
            }
        }
        return String.join(" | ", parts);
    }
}
