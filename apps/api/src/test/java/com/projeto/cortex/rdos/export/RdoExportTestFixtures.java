package com.projeto.cortex.rdos.export;

import com.projeto.cortex.rdos.RdoResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

final class RdoExportTestFixtures {

    private RdoExportTestFixtures() {
    }

    static RdoResponse populatedRdo(String id, String numero) {
        return new RdoResponse(
                id, "obra-7", null, numero, LocalDate.of(2026, 7, 22),
                "rdo-41", 9L, "mutation-42", 4L, "col-ana", "quarta-feira",
                "Cliente Rodovias", "CTR-9", "BR-101", "Joinville", "SC",
                "10+000", "11+000", "10+200", "10+800", "DIURNO",
                LocalTime.of(7, 30), LocalTime.of(17, 15), "BOM", "CHUVA",
                "IMPOSSIBILITADO", new BigDecimal("3.25"), "RASCUNHO",
                "Execução conferida", "encarregado-7", "Ana Apontadora",
                "Enzo Encarregado", "Fiscal de Campo",
                List.of(
                        new RdoResponse.MaoObraItem(
                                "mo-1", "col-ana", "Ana Apontadora", "Apontador",
                                "CONTRATADO", BigDecimal.ONE, LocalTime.of(7, 30),
                                LocalTime.of(17, 15), null, "mo-anterior"
                        ),
                        new RdoResponse.MaoObraItem(
                                "mo-2", "col-op", "Otávio Operador", "Operador",
                                "SUBCONTRATADO", new BigDecimal("2"),
                                LocalTime.of(8, 0), LocalTime.of(16, 0), null, null
                        )
                ),
                List.of(new RdoResponse.EquipamentoItem(
                        "eq-1", "asset-7", "EQ-7", "Escavadeira", "ESCAVADEIRA",
                        "PROPRIO", BigDecimal.ONE, LocalTime.of(8, 0),
                        LocalTime.of(16, 0), "Operação normal"
                )),
                List.of(new RdoResponse.MaterialItem(
                        "mat-1", "CAP", "t", new BigDecimal("16"),
                        new BigDecimal("15.50"), new BigDecimal("14.25"),
                        new BigDecimal("1.25"), "NF-88", "Fornecedor", null
                )),
                List.of(control("cg-1")),
                List.of(new RdoResponse.ServicoExecutadoItem(
                        "svc-1", null, null, "Fresagem", "item-1",
                        new BigDecimal("125.50"),
                        "m²", "10+000", "10+500", "Faixa direita", "DIURNO",
                        "VALIDADO", "ESTIMADA", "HISTORICAL_UNPRICED",
                        null, null, null,
                        false, false, "Sem intercorrências"
                )),
                List.of(),
                List.of(new RdoResponse.AttachmentItem(
                        "att-1", id, "obra-7", "FOTO", "foto", "foto.jpg",
                        "image/jpeg", 10L, 8L, 8L, "SINCRONIZADO", null, null,
                        null, Map.of("email", "must-not-be-exported@example.com")
                ))
        );
    }

    static RdoResponse.ControleGeometricoItem control(String id) {
        return new RdoResponse.ControleGeometricoItem(
                id, "km 10 ao km 11", "1", "10+000", "11+000", "10", "11",
                "Pista norte", "Direita", "OS-7", "Regularização",
                new BigDecimal("1000"), new BigDecimal("3.5"),
                new BigDecimal("4"), new BigDecimal("5"), new BigDecimal("6"),
                new BigDecimal("5"), new BigDecimal("3500"),
                new BigDecimal("175"), new BigDecimal("2.45"),
                new BigDecimal("12.75"), "Controle aprovado"
        );
    }

    static RdoResponse emptyRdo(String id, String numero) {
        return new RdoResponse(
                id, "obra-7", null, numero, LocalDate.of(2026, 7, 22),
                null, null, null, 0L, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, "RASCUNHO", null, null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of()
        );
    }

    static RdoResponse.ControleGeometricoItem parityControl() {
        return new RdoResponse.ControleGeometricoItem(
                "cg-parity", "km 10 ao km 11", "1", "10+000", "11+000",
                "10", "11", "Pista norte", "Direita", "OS-7",
                "Regularização", new BigDecimal("1000"), new BigDecimal("3.5"),
                new BigDecimal("4"), new BigDecimal("5"), new BigDecimal("6"),
                new BigDecimal("5"), new BigDecimal("3500"),
                new BigDecimal("175"), new BigDecimal("2.45"),
                new BigDecimal("428.75"), "Controle aprovado"
        );
    }
}
