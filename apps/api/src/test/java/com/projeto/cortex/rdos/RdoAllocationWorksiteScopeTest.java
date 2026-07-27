package com.projeto.cortex.rdos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

import com.projeto.cortex.financeiro.revenue.RevenueOntologyPublisher;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class RdoAllocationWorksiteScopeTest {

    @Test
    void rejectsUnknownCollaboratorBeforeAnyDetailMutation() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CortexOperationalMemoryService memory =
                mock(CortexOperationalMemoryService.class);
        RdoOperationalDetailService service =
                new RdoOperationalDetailService(
                        jdbc,
                        memory,
                        mock(RevenueOntologyPublisher.class)
                );

        assertThatThrownBy(() -> service.substituirDetalhes(
                "6c224086-43a3-4a3a-a429-53bcf5cafaf3",
                "a01fcada-62d9-47ff-bfbe-29529d493063",
                null,
                LocalDate.of(2026, 7, 23),
                "DIURNO",
                List.of(),
                List.of(new RdoCreateRequest.AlocacaoColaboradorItem(
                        "0e5c853a-0b45-45f8-8583-88543bfba9a8",
                        "f30ca81d-f1ac-414c-afca-58257cad42b2",
                        "Equipe",
                        null,
                        LocalTime.of(8, 0),
                        LocalTime.of(12, 0),
                        new BigDecimal("0.500000"),
                        "DIURNO",
                        "OPERACIONAL",
                        null,
                        "TRABALHO",
                        "RDO",
                        "REGISTRADA",
                        null
                ))
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining(
                        "Colaborador não encontrado."
                );

        assertThat(mockingDetails(jdbc).getInvocations())
                .noneMatch(invocation ->
                        invocation.getMethod().getName().equals("update"));
        assertThat(mockingDetails(memory).getInvocations()).isEmpty();
    }
}
