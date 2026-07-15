package com.projeto.cortex.obras.mapa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.obras.Obra;
import com.projeto.cortex.obras.ObraRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ObraMapaServiceTest {

    private final ObraRepository obraRepository = mock(ObraRepository.class);
    private final ObraGeometriaRepository featureRepository =
            mock(ObraGeometriaRepository.class);
    private final CurrentUserService currentUserService = mock(CurrentUserService.class);
    private final ObraGeometriaMemoryPublisher memoryPublisher =
            mock(ObraGeometriaMemoryPublisher.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ObraMapaService service = new ObraMapaService(
            obraRepository,
            featureRepository,
            currentUserService,
            memoryPublisher,
            objectMapper
    );

    @Test
    void readRequiresWorksiteScopeAndReturnsOnlyPersistedFeatures() {
        Obra obra = mock(Obra.class);
        when(obra.getId()).thenReturn("obra-1");
        when(obra.getNome()).thenReturn("Obra Norte");
        when(obra.getLatitude()).thenReturn(new BigDecimal("-20.4428000"));
        when(obra.getLongitude()).thenReturn(new BigDecimal("-54.6464000"));
        when(obraRepository.findById("obra-1")).thenReturn(Optional.of(obra));
        when(featureRepository.findByObraIdAndStatusOrderByValidoDesdeAscIdAsc(
                "obra-1", "ATIVA"
        )).thenReturn(List.of());

        ObraMapaResponse response = service.buscarMapa("obra-1");

        verify(currentUserService).requireWorksiteAccess("obra-1");
        assertThat(response.obra().latitude()).isEqualByComparingTo("-20.4428000");
        assertThat(response.features()).isEmpty();
    }

    @Test
    void createPersistsValidatedGeoJsonAndPublishesOntology() throws Exception {
        Obra obra = mock(Obra.class);
        when(obraRepository.findById("obra-1")).thenReturn(Optional.of(obra));
        when(currentUserService.requireUserId()).thenReturn("alfa-1");
        when(featureRepository.saveAndFlush(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ObraGeometriaResponse response = service.criar(
                "obra-1",
                new ObraGeometriaRequest(
                        "trecho", "TRECHO", "trecho-1",
                        objectMapper.readTree("""
                                {"type":"LineString","coordinates":[[-54.65,-20.44],[-54.63,-20.43]]}
                                """),
                        Map.of("nome", "Trecho Norte"),
                        "levantamento_campo", null, null, null
                )
        );

        ArgumentCaptor<ObraGeometria> captor =
                ArgumentCaptor.forClass(ObraGeometria.class);
        verify(featureRepository).saveAndFlush(captor.capture());
        verify(memoryPublisher).criada(response, "obra-1", "alfa-1");
        assertThat(captor.getValue().getCategoria()).isEqualTo("TRECHO");
        assertThat(captor.getValue().getFonte()).isEqualTo("LEVANTAMENTO_CAMPO");
        assertThat(response.geometry().path("type").asText()).isEqualTo("LineString");
    }

    @Test
    void updateRejectsStaleBaseVersionBeforeWriting() throws Exception {
        ObraGeometria existing = ObraGeometria.criar(
                "obra-1", "TRECHO", "TRECHO", "trecho-1", "LINESTRING",
                "{\"type\":\"LineString\",\"coordinates\":[[-54.65,-20.44],[-54.63,-20.43]]}",
                "{}", "CAMPO", null, "alfa-1"
        );
        when(featureRepository.findByIdAndObraId(existing.getId(), "obra-1"))
                .thenReturn(Optional.of(existing));

        ObraGeometriaRequest request = new ObraGeometriaRequest(
                "TRECHO", "TRECHO", "trecho-1",
                objectMapper.readTree("""
                        {"type":"LineString","coordinates":[[-54.66,-20.45],[-54.62,-20.42]]}
                        """),
                Map.of(), "CAMPO", null, 4L, "Correção topográfica"
        );

        assertThatThrownBy(() -> service.atualizar("obra-1", existing.getId(), request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("alterada por outra operação");
    }
}
