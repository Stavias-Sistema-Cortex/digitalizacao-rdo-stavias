package com.projeto.cortex.financeiro.unit;

public final class FinancialUnitDtos {

    private FinancialUnitDtos() {
    }

    public record FinancialUnitResponse(
            String id,
            FinancialUnitType tipo,
            String obraId,
            String ativoId,
            String codigo,
            String nome,
            String status,
            long versao
    ) {
        static FinancialUnitResponse from(FinancialUnitRecord record) {
            return new FinancialUnitResponse(
                    record.id(),
                    record.type(),
                    record.worksiteId(),
                    record.assetId(),
                    record.code(),
                    record.name(),
                    record.status(),
                    record.version()
            );
        }
    }

    public record FinancialUnitFilter(
            FinancialUnitType tipo,
            String status,
            String busca
    ) {
    }

    public record CreateFinancialUnitRequest(
            String codigo,
            String nome
    ) {
    }
}
