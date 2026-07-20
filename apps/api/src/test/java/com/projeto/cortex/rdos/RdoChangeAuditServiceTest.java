package com.projeto.cortex.rdos;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class RdoChangeAuditServiceTest {

    private final RdoChangeAuditService service =
            new RdoChangeAuditService(null);

    @Test
    void shouldCalculateOnlyOperationalChanges() {
        RdoChangeAuditService.RdoAuditSnapshot before =
                snapshot(
                        2,
                        Map.of(
                                "condicaoManha",
                                value("NUBLADO", "Nublado"),
                                "pluviometria",
                                value("0", "0 mm"),
                                "observacoes",
                                value("Sem ocorrências", "Sem ocorrências"),
                                "status",
                                value("RASCUNHO", "Rascunho")
                        ),
                        Map.of(
                                "equipamentos",
                                List.of()
                        )
                );

        RdoChangeAuditService.RdoAuditSnapshot after =
                snapshot(
                        3,
                        Map.of(
                                "condicaoManha",
                                value("CHUVA", "Chuva"),
                                "pluviometria",
                                value("12.5", "12.5 mm"),
                                "observacoes",
                                value("Sem ocorrências", "Sem ocorrências"),
                                "status",
                                value("RASCUNHO", "Rascunho")
                        ),
                        Map.of(
                                "equipamentos",
                                List.of(
                                        new RdoChangeAuditService.AuditItem(
                                                "eq-1",
                                                "EQ-1 - Rolo compactador (1)",
                                                Map.of(
                                                        "prefixo",
                                                        "EQ-1",
                                                        "descricao",
                                                        "Rolo compactador",
                                                        "quantidade",
                                                        "1"
                                                )
                                        )
                                )
                        )
                );

        List<Map<String, Object>> changes =
                service.calcularAlteracoes(before, after);

        assertThat(changes)
                .extracting(change -> change.get("campo"))
                .containsExactly(
                        "condicaoManha",
                        "pluviometria",
                        "equipamentos"
                );

        assertThat(changes.getFirst())
                .containsEntry("valorAnterior", "Nublado")
                .containsEntry("valorNovo", "Chuva");

        assertThat(changes.get(2))
                .containsEntry("operacao", "ADICIONADO")
                .containsEntry(
                        "valorNovo",
                        "EQ-1 - Rolo compactador (1)"
                );
    }

    private RdoChangeAuditService.RdoAuditSnapshot snapshot(
            long version,
            Map<String, RdoChangeAuditService.AuditValue> fields,
            Map<String, List<RdoChangeAuditService.AuditItem>> collections
    ) {
        return new RdoChangeAuditService.RdoAuditSnapshot(
                "rdo-1",
                "obra-1",
                null,
                "RDO-1",
                "RASCUNHO",
                version,
                fields,
                collections
        );
    }

    private RdoChangeAuditService.AuditValue value(
            String normalized,
            String display
    ) {
        return new RdoChangeAuditService.AuditValue(
                normalized,
                display
        );
    }
}
