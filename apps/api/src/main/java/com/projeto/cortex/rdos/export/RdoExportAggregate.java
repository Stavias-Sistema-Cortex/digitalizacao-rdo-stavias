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
        /*
         * O comprimento é conta, não coluna: a V69 deixou explícito que área e
         * volume não se gravam ao lado das parcelas, e o mesmo vale aqui — o
         * trecho já diz a extensão, e recalculá-la na leitura evita duas
         * versões da mesma verdade divergindo no primeiro acerto de uma delas.
         */
        BigDecimal length = extensionMeters(
                value.trechoInicial(),
                value.trechoFinal()
        );
        return new WorkedRow(
                value.trechoInicial(),
                value.trechoFinal(),
                null,
                length,
                value.larguraM(),
                value.espessuraCm(),
                firstNonBlank(value.pista(), value.localizacao()),
                value.faixa(),
                null,
                activity
        );
    }

    /**
     * A extensão entre dois km, em metros, sem se importar com o sentido.
     *
     * <p>Espelha `extensionMeters` do formulário de propósito: a pista Sul tem
     * quilometragem decrescente — começa no km 400 e termina no 398 —, e uma
     * subtração com sinal devolveria extensão negativa ou vazia para metade da
     * rodovia. Distância entre dois pontos não tem sinal; quem tem sentido é a
     * pista, e isso é assunto de outro campo.
     */
    private static BigDecimal extensionMeters(String start, String end) {
        BigDecimal from = parseKm(start);
        BigDecimal to = parseKm(end);
        if (from == null || to == null) {
            return null;
        }
        return to.subtract(from).abs().multiply(BigDecimal.valueOf(1000));
    }

    private static BigDecimal parseKm(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim().replace(",", "."));
        } catch (NumberFormatException ignored) {
            return null;
        }
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
