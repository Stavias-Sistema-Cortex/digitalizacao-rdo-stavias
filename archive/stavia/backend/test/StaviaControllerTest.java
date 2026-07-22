package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.intelligence.stavia.api.StaviaConsultaRequest;
import com.projeto.cortex.intelligence.stavia.api.StaviaController;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.model.StaviaAnswer;
import com.projeto.cortex.intelligence.stavia.model.StaviaAnswerType;
import com.projeto.cortex.intelligence.stavia.model.StaviaConfidence;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StaviaControllerTest {

    @Test
    void shouldDelegateAuthorizedLocalQuery() {
        StaviaQueryService queryService =
                mock(StaviaQueryService.class);
        CurrentUserService currentUserService =
                mock(CurrentUserService.class);

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
                        1.0,
                        Map.of(),
                        List.of()
                );

        when(
                queryService.query(any())
        ).thenReturn(expected);
        when(currentUserService.requireUserId())
                .thenReturn("usuario-autenticado");

        StaviaController controller =
                new StaviaController(queryService, currentUserService);

        StaviaQueryResult result =
                controller.consultar(
                        new StaviaConsultaRequest(
                                "Quais RDOs pertencem à obra?",
                                "usuario-forjado",
                                "obra-1"
                        )
                );

        assertThat(result).isSameAs(expected);

        var questionCaptor =
                forClass(com.projeto.cortex.intelligence.stavia.model.StaviaQuestion.class);
        verify(queryService).query(questionCaptor.capture());
        assertThat(questionCaptor.getValue().userId())
                .isEqualTo("usuario-autenticado");
    }

    @Test
    void shouldSendSelectedRdoContextToQueryService() {
        StaviaQueryService queryService =
                mock(StaviaQueryService.class);
        CurrentUserService currentUserService =
                mock(CurrentUserService.class);
        StaviaQueryResult expected =
                new StaviaQueryResult(
                        new StaviaAnswer(
                                "01/07/2026",
                                StaviaConfidence.ALTA,
                                StaviaAnswerType.FATO,
                                List.of(),
                                false,
                                List.of()
                        ),
                        StaviaIntent.CONSULTAR_RDO,
                        1.0,
                        Map.of(),
                        List.of()
                );

        when(queryService.query(any())).thenReturn(expected);
        when(currentUserService.requireUserId())
                .thenReturn("usuario-autenticado");

        StaviaController controller =
                new StaviaController(queryService, currentUserService);

        controller.consultar(
                new StaviaConsultaRequest(
                        "Qual a data?",
                        "usuario-forjado",
                        "obra-ativa",
                        "rdo-ativo",
                        "Obra X · RDO 123",
                        "obra-antiga",
                        "rdo-antigo"
                )
        );

        var questionCaptor =
                forClass(com.projeto.cortex.intelligence.stavia.model.StaviaQuestion.class);
        verify(queryService).query(questionCaptor.capture());

        assertThat(questionCaptor.getValue().text())
                .contains(
                        "Contexto ontológico selecionado: "
                                + "obraId=obra-ativa rdoId=rdo-ativo"
                )
                .contains("Contexto informado: Obra X · RDO 123")
                .doesNotContain("rdo-antigo")
                .doesNotContain("obra-antiga");
        assertThat(questionCaptor.getValue().obraId())
                .isEqualTo("obra-ativa");
        assertThat(questionCaptor.getValue().userId())
                .isEqualTo("usuario-autenticado");
    }
}
