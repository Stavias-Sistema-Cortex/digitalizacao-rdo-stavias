package com.projeto.cortex.intelligence.stavia.knowledge.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.intelligence.stavia.StaviaEngine;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.obras.ObraRepository;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class FinancialForecastKnowledgeSourceAuthorizationTest {

    @Test
    void missingFinancialCapabilityReturnsBeforeAnyRepositoryQuery() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ObraRepository obras = mock(ObraRepository.class);
        FinancialForecastKnowledgeSource source =
                new FinancialForecastKnowledgeSource(
                        jdbc,
                        obras,
                        new ObjectMapper()
                );
        StaviaKnowledgeRequest request = new StaviaKnowledgeRequest(
                new StaviaQuestion(
                        "Qual é a previsão financeira?",
                        "usuario-1",
                        "obra-1"
                ),
                StaviaIntent.CONSULTAR_PREVISAO_FINANCEIRA,
                "obra-1",
                Set.of(StaviaEngine.REQUIRED_PERMISSION)
        );

        assertThat(source.supports(request)).isFalse();
        assertThat(source.retrieve(request)).isEmpty();
        verifyNoInteractions(jdbc, obras);
    }
}
