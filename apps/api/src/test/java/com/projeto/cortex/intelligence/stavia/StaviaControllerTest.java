package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.api.StaviaConsultaRequest;
import com.projeto.cortex.intelligence.stavia.api.StaviaController;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.model.StaviaAnswer;
import com.projeto.cortex.intelligence.stavia.model.StaviaAnswerType;
import com.projeto.cortex.intelligence.stavia.model.StaviaConfidence;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StaviaControllerTest {

    @Test
    void shouldDelegateAuthorizedLocalQuery() {
        StaviaQueryService queryService =
                mock(StaviaQueryService.class);

        StaviaQueryResult expected =
                new StaviaQueryResult(
                        new StaviaAnswer(
                                "Resposta validada.",
                                StaviaConfidence.ALTA,
                                StaviaAnswerType.FATO,
                                List.of(),
                                false,
                                List.of()
                        ),
                        StaviaIntent.CONSULTAR_RDO,
                        Map.of(),
                        List.of()
                );

        when(
                queryService.query(
                        any(),
                        eq(Set.of(
                                StaviaEngine.REQUIRED_PERMISSION
                        ))
                )
        ).thenReturn(expected);

        StaviaController controller =
                new StaviaController(queryService);

        StaviaQueryResult result =
                controller.consultar(
                        new StaviaConsultaRequest(
                                "Quais RDOs pertencem à obra?",
                                "validacao-local",
                                "obra-1"
                        )
                );

        assertThat(result).isSameAs(expected);

        verify(queryService).query(
                any(),
                eq(Set.of(
                        StaviaEngine.REQUIRED_PERMISSION
                ))
        );
    }
}
