package com.projeto.cortex.financeiro.core;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.projeto.cortex.auth.CurrentUserService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

class FinanceCatalogAggregateTypeTest {

    private static final String OBRA_ID =
            "00000000-0000-4000-8000-000000000101";

    @Test
    @SuppressWarnings("unchecked")
    void acceptsEveryAggregatePersistedByTheFinanceSchema() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(
                anyString(),
                any(RowMapper.class),
                any(),
                any()
        )).thenReturn(List.of());
        FinanceCatalogService service = new FinanceCatalogService(
                jdbc,
                mock(CurrentUserService.class),
                mock(FinanceOntologyProjector.class)
        );

        for (String aggregate : List.of(
                "SOLICITACAO", "COMPRA", "NOTA_FISCAL", "LANCAMENTO"
        )) {
            service.listStatuses(OBRA_ID, aggregate);
        }

        assertThatThrownBy(() -> service.listStatuses(OBRA_ID, "RDO"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Tipo de agregado inválido");
    }
}
