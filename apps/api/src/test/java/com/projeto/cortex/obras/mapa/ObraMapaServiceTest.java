package com.projeto.cortex.obras.mapa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.obras.Obra;
import com.projeto.cortex.obras.ObraOperabilityGuard;
import com.projeto.cortex.obras.ObraRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ObraMapaServiceTest {

    private final ObraRepository obraRepository = mock(ObraRepository.class);
    private final ObraGeometriaRepository featureRepository =
            mock(ObraGeometriaRepository.class);
    private final CurrentUserService currentUserService = mock(CurrentUserService.class);
    private final ObraGeometriaMemoryPublisher memoryPublisher =
            mock(ObraGeometriaMemoryPublisher.class);
    private final ObraOperabilityGuard operabilityGuard =
            mock(ObraOperabilityGuard.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ObraMapaService service = new ObraMapaService(
            obraRepository,
            featureRepository,
            currentUserService,
            memoryPublisher,
            objectMapper,
            operabilityGuard
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
    void readKeepsArchivedWorksiteHistoryAvailable() {
        Obra obra = mock(Obra.class);
        when(obra.getId()).thenReturn("obra-archived");
        when(obra.getNome()).thenReturn("Obra arquivada");
        when(obra.getArquivadoEm()).thenReturn(LocalDateTime.now());
        when(obraRepository.findById("obra-archived"))
                .thenReturn(Optional.of(obra));
        when(featureRepository.findByObraIdAndStatusOrderByValidoDesdeAscIdAsc(
                "obra-archived",
                "ATIVA"
        )).thenReturn(List.of());

        ObraMapaResponse response = service.buscarMapa("obra-archived");

        assertThat(response.obra().id()).isEqualTo("obra-archived");
        verify(operabilityGuard, never()).requireWritable(any());
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

        verify(operabilityGuard).requireWritable("obra-1");
        ArgumentCaptor<ObraGeometria> captor =
                ArgumentCaptor.forClass(ObraGeometria.class);
        verify(featureRepository).saveAndFlush(captor.capture());
        verify(memoryPublisher).criada(response, "obra-1", "alfa-1");
        assertThat(captor.getValue().getCategoria()).isEqualTo("TRECHO");
        assertThat(captor.getValue().getFonte()).isEqualTo("LEVANTAMENTO_CAMPO");
        assertThat(response.geometry().path("type").asText()).isEqualTo("LineString");
    }

    @Test
    void fieldCaptureAuthorizesByWorksiteAccessInsteadOfAlfa() throws Exception {
        when(currentUserService.requireUserId()).thenReturn("apontador-1");
        when(featureRepository.saveAndFlush(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ObraGeometriaResponse response = service.registrarCapturaCampo(
                "obra-1",
                new ObraGeometriaRequest(
                        "ponto_operacional", "RDO", "rdo-1",
                        objectMapper.readTree(
                                "{\"type\":\"Point\",\"coordinates\":[-54.65,-20.44]}"
                        ),
                        Map.of("precisaoM", 4.5),
                        null, null, null, null
                )
        );

        verify(currentUserService).requireWorksiteAccess("obra-1");
        verify(currentUserService, never()).requireAlfa();
        verify(operabilityGuard).requireWritable("obra-1");
        verify(memoryPublisher).criada(response, "obra-1", "apontador-1");
        assertThat(response.categoria()).isEqualTo("PONTO_OPERACIONAL");
        assertThat(response.fonte()).isEqualTo("CAPTURA_CAMPO");
    }

    @Test
    void fieldCaptureOverridesAnyClientSuppliedSource() throws Exception {
        when(currentUserService.requireUserId()).thenReturn("apontador-1");
        when(featureRepository.saveAndFlush(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ObraGeometriaResponse response = service.registrarCapturaCampo(
                "obra-1",
                new ObraGeometriaRequest(
                        "FRENTE_TRABALHO", "FRENTE_TRABALHO", "frente-1",
                        objectMapper.readTree("""
                                {"type":"LineString","coordinates":[[-54.65,-20.44],[-54.63,-20.43]]}
                                """),
                        Map.of(),
                        "GESTAO_MAPA", null, null, null
                )
        );

        assertThat(response.fonte()).isEqualTo("CAPTURA_CAMPO");
    }

    /**
     * O id da criação vinda do sync é o que o dispositivo cunhou. O contrato
     * canônico confere a identidade devolvida contra a enviada, e o IndexedDB
     * reconcilia o registro local por ela — o servidor não pode trocá-la.
     */
    @Test
    void syncCreatePreservesTheDeviceMintedIdentity() throws Exception {
        String clientId = "60000000-0000-4000-8000-000000000006";
        when(currentUserService.requireUserId()).thenReturn("apontador-1");
        when(featureRepository.findById(clientId)).thenReturn(Optional.empty());
        when(featureRepository.saveAndFlush(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ObraGeometriaResponse response = service.registrarCapturaCampo(
                "obra-1",
                new ObraGeometriaRequest(
                        "PONTO_OPERACIONAL", "RDO", "rdo-1",
                        objectMapper.readTree(
                                "{\"type\":\"Point\",\"coordinates\":[-54.65,-20.44]}"
                        ),
                        Map.of(), null, null, null, null
                ),
                clientId
        );

        ArgumentCaptor<ObraGeometria> captor =
                ArgumentCaptor.forClass(ObraGeometria.class);
        verify(featureRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(clientId);
        assertThat(response.id()).isEqualTo(clientId);
    }

    /** Reenviar a mesma identidade é replay: devolve o que existe, sem regravar. */
    @Test
    void syncCreateReplayReturnsTheExistingRowWithoutRewriting() {
        String clientId = "60000000-0000-4000-8000-000000000006";
        ObraGeometria existing = ObraGeometria.criar(
                clientId, "obra-1", "PONTO_OPERACIONAL", "RDO", "rdo-1", "POINT",
                "{\"type\":\"Point\",\"coordinates\":[-54.65,-20.44]}",
                "{}", "CAPTURA_CAMPO", null, "apontador-1"
        );
        when(currentUserService.requireUserId()).thenReturn("apontador-1");
        when(featureRepository.findById(clientId))
                .thenReturn(Optional.of(existing));

        ObraGeometriaResponse response = service.registrarCapturaCampo(
                "obra-1",
                new ObraGeometriaRequest(
                        "PONTO_OPERACIONAL", "RDO", "rdo-1",
                        objectMapper.valueToTree(Map.of(
                                "type", "Point",
                                "coordinates", List.of(-54.65, -20.44)
                        )),
                        Map.of(), null, null, null, null
                ),
                clientId
        );

        assertThat(response.id()).isEqualTo(clientId);
        verify(featureRepository, never()).saveAndFlush(any());
        verify(memoryPublisher, never()).criada(any(), any(), any());
    }

    @Test
    void syncCreateRejectsAnIdentityAlreadyUsedInAnotherWorksite() {
        String clientId = "60000000-0000-4000-8000-000000000006";
        ObraGeometria existing = ObraGeometria.criar(
                clientId, "obra-2", "PONTO_OPERACIONAL", "RDO", "rdo-1", "POINT",
                "{\"type\":\"Point\",\"coordinates\":[-54.65,-20.44]}",
                "{}", "CAPTURA_CAMPO", null, "apontador-1"
        );
        when(currentUserService.requireUserId()).thenReturn("apontador-1");
        when(featureRepository.findById(clientId))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.registrarCapturaCampo(
                "obra-1",
                new ObraGeometriaRequest(
                        "PONTO_OPERACIONAL", "RDO", "rdo-1",
                        objectMapper.valueToTree(Map.of(
                                "type", "Point",
                                "coordinates", List.of(-54.65, -20.44)
                        )),
                        Map.of(), null, null, null, null
                ),
                clientId
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("outra obra");

        verify(featureRepository, never()).saveAndFlush(any());
    }

    @Test
    void fieldCaptureCannotRedrawTheContractualStretch() throws Exception {
        ObraGeometriaRequest request = new ObraGeometriaRequest(
                "TRECHO", "TRECHO", "trecho-1",
                objectMapper.readTree("""
                        {"type":"LineString","coordinates":[[-54.65,-20.44],[-54.63,-20.43]]}
                        """),
                Map.of(), null, null, null, null
        );

        assertThatThrownBy(() -> service.registrarCapturaCampo("obra-1", request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("ponto operacional ou frente de trabalho");

        verify(featureRepository, never()).saveAndFlush(any());
        verify(operabilityGuard, never()).requireWritable(any());
    }

    @Test
    void fieldCaptureStillRequiresTheOntologicalObjectReference() throws Exception {
        ObraGeometriaRequest request = new ObraGeometriaRequest(
                "PONTO_OPERACIONAL", null, null,
                objectMapper.readTree(
                        "{\"type\":\"Point\",\"coordinates\":[-54.65,-20.44]}"
                ),
                Map.of(), null, null, null, null
        );

        assertThatThrownBy(() -> service.registrarCapturaCampo("obra-1", request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("objeto ontológico");

        verify(featureRepository, never()).saveAndFlush(any());
    }

    @Test
    void fieldCaptureIsBlockedOnAnArchivedWorksite() throws Exception {
        doThrow(new ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND,
                "Obra não encontrada ou arquivada."
        )).when(operabilityGuard).requireWritable("obra-arquivada");
        ObraGeometriaRequest request = new ObraGeometriaRequest(
                "PONTO_OPERACIONAL", "RDO", "rdo-1",
                objectMapper.readTree(
                        "{\"type\":\"Point\",\"coordinates\":[-54.65,-20.44]}"
                ),
                Map.of(), null, null, null, null
        );

        assertThatThrownBy(() ->
                service.registrarCapturaCampo("obra-arquivada", request))
                .isInstanceOf(ResponseStatusException.class);

        verify(featureRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateRejectsStaleBaseVersionBeforeWriting() throws Exception {
        ObraGeometria existing = ObraGeometria.criar(
                null, "obra-1", "TRECHO", "TRECHO", "trecho-1", "LINESTRING",
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

    @Test
    void archivedWorksiteRejectsCreateBeforePersistence() throws Exception {
        doThrow(new ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND
        )).when(operabilityGuard).requireWritable("obra-1");

        assertThatThrownBy(() -> service.criar(
                "obra-1",
                validRequest(null)
        )).isInstanceOf(ResponseStatusException.class);

        verify(featureRepository, never()).saveAndFlush(any());
        verify(memoryPublisher, never()).criada(any(), any(), any());
    }

    @Test
    void archivedWorksiteRejectsUpdateBeforePersistence() throws Exception {
        ObraGeometria existing = existingFeature();
        when(featureRepository.findByIdAndObraId(existing.getId(), "obra-1"))
                .thenReturn(Optional.of(existing));
        doThrow(new ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND
        )).when(operabilityGuard).requireWritable("obra-1");

        assertThatThrownBy(() -> service.atualizar(
                "obra-1",
                existing.getId(),
                validRequest(1L)
        )).isInstanceOf(ResponseStatusException.class);

        verify(featureRepository, never()).saveAndFlush(any());
        verify(memoryPublisher, never()).atualizada(
                any(), any(), any(), any(), anyLong(), any()
        );
    }

    @Test
    void archivedWorksiteRejectsEndBeforePersistence() {
        ObraGeometria existing = existingFeature();
        when(featureRepository.findByIdAndObraId(existing.getId(), "obra-1"))
                .thenReturn(Optional.of(existing));
        doThrow(new ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND
        )).when(operabilityGuard).requireWritable("obra-1");

        assertThatThrownBy(() -> service.encerrar(
                "obra-1",
                existing.getId(),
                new ObraGeometriaEndRequest(
                        1L,
                        "Encerramento",
                        null
                )
        )).isInstanceOf(ResponseStatusException.class);

        verify(featureRepository, never()).saveAndFlush(any());
        verify(memoryPublisher, never()).encerrada(
                any(), any(), any(), any(), anyLong(), any()
        );
    }

    private ObraGeometria existingFeature() {
        return ObraGeometria.criar(
                null,
                "obra-1",
                "TRECHO",
                "TRECHO",
                "trecho-1",
                "LINESTRING",
                "{\"type\":\"LineString\",\"coordinates\":[[-54.65,-20.44],[-54.63,-20.43]]}",
                "{}",
                "CAMPO",
                null,
                "alfa-1"
        );
    }

    private ObraGeometriaRequest validRequest(Long baseVersion)
            throws Exception {
        return new ObraGeometriaRequest(
                "TRECHO",
                "TRECHO",
                "trecho-1",
                objectMapper.readTree("""
                        {"type":"LineString","coordinates":[[-54.66,-20.45],[-54.62,-20.42]]}
                        """),
                Map.of(),
                "CAMPO",
                null,
                baseVersion,
                "Correção topográfica"
        );
    }
}
