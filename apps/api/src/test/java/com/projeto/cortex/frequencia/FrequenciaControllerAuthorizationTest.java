package com.projeto.cortex.frequencia;

import com.projeto.cortex.auth.CurrentUserService;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class FrequenciaControllerAuthorizationTest {

    @Test
    void requiresAlfaForRecordsThatChangeAttendanceAndBankedHours() {
        FrequenciaService service = mock(FrequenciaService.class);
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        FrequenciaController controller = new FrequenciaController(service, currentUserService);
        org.mockito.Mockito.when(currentUserService.requireUserId()).thenReturn("alfa-1");

        controller.registrarPresenca("colaborador-1", mock(RegistroPresencaRequest.class));
        controller.registrarOcorrencia("colaborador-1", mock(OcorrenciaFrequenciaRequest.class));
        controller.ajustarBancoHoras("colaborador-1", mock(AjusteBancoHorasRequest.class));

        verify(currentUserService, org.mockito.Mockito.times(3)).requireAlfa();
        verify(currentUserService, never()).requireSelfOrAdmin(any());
        verify(service).registrarOcorrencia(
                eq("colaborador-1"),
                eq("alfa-1"),
                any(OcorrenciaFrequenciaRequest.class)
        );
    }
}
