package com.projeto.cortex.obras.mapa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.obras.Obra;
import com.projeto.cortex.obras.ObraOperabilityGuard;
import com.projeto.cortex.obras.ObraRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class ObraMapaService {

    private static final Set<String> CATEGORIES = Set.of(
            "LOCALIZACAO_OBRA", "PERIMETRO_OBRA", "TRECHO", "PONTO_OPERACIONAL",
            "FRENTE_TRABALHO", "EQUIPAMENTO", "EVENTO", "RDO", "OCORRENCIA",
            "PROGRAMACAO"
    );
    private static final Set<String> POINT_CATEGORIES = Set.of(
            "LOCALIZACAO_OBRA", "PONTO_OPERACIONAL", "EQUIPAMENTO", "EVENTO",
            "RDO", "OCORRENCIA"
    );
    /**
     * Categorias que a equipe de campo pode registrar sem perfil Alfa. Trecho e
     * perímetro definem o contorno contratual da obra e continuam restritos.
     */
    private static final Set<String> FIELD_CAPTURE_CATEGORIES = Set.of(
            "PONTO_OPERACIONAL", "FRENTE_TRABALHO"
    );
    private static final String FIELD_CAPTURE_SOURCE = "CAPTURA_CAMPO";

    private final ObraRepository obraRepository;
    private final ObraGeometriaRepository featureRepository;
    private final CurrentUserService currentUserService;
    private final ObraGeometriaMemoryPublisher memoryPublisher;
    private final ObjectMapper objectMapper;
    private final ObraOperabilityGuard operabilityGuard;

    public ObraMapaService(
            ObraRepository obraRepository,
            ObraGeometriaRepository featureRepository,
            CurrentUserService currentUserService,
            ObraGeometriaMemoryPublisher memoryPublisher,
            ObjectMapper objectMapper,
            ObraOperabilityGuard operabilityGuard
    ) {
        this.obraRepository = obraRepository;
        this.featureRepository = featureRepository;
        this.currentUserService = currentUserService;
        this.memoryPublisher = memoryPublisher;
        this.objectMapper = objectMapper;
        this.operabilityGuard = operabilityGuard;
    }

    @Transactional(readOnly = true)
    public ObraMapaResponse buscarMapa(String obraId) {
        currentUserService.requireWorksiteAccess(obraId);
        Obra obra = requireWorksiteForRead(obraId);
        return new ObraMapaResponse(
                new ObraMapaResponse.ObraLocalizacaoResponse(
                        obra.getId(), obra.getNome(), obra.getLatitude(), obra.getLongitude()
                ),
                featureRepository
                        .findByObraIdAndStatusOrderByValidoDesdeAscIdAsc(obraId, "ATIVA")
                        .stream()
                        .map(this::toResponse)
                        .toList()
        );
    }

    @Transactional
    public ObraGeometriaResponse criar(String obraId, ObraGeometriaRequest request) {
        return criar(obraId, request, null);
    }

    /**
     * Variante do sync: {@code idDoCliente} é a identidade cunhada no
     * dispositivo e precisa sobreviver à travessia — o contrato canônico
     * confere que a aplicação devolve exatamente o id enviado, e o IndexedDB
     * reconcilia o registro local por ele. Cunhar id novo aqui fazia toda
     * criação de geometria vinda do campo falhar o contrato e retentar para
     * sempre.
     */
    @Transactional
    public ObraGeometriaResponse criar(
            String obraId,
            ObraGeometriaRequest request,
            String idDoCliente
    ) {
        currentUserService.requireAlfa();
        return persistirNova(obraId, normalize(request, obraId, false), idDoCliente);
    }

    /**
     * Registra uma geometria observada em campo pela PWA.
     *
     * <p>Autoriza por acesso à obra em vez de perfil Alfa, porque quem produz o
     * dado é o apontador no canteiro. Em troca o escopo é estreito: só ponto
     * operacional e frente de trabalho, sempre com a origem
     * {@code CAPTURA_CAMPO}, sempre referenciando o objeto ontológico
     * correspondente e sempre sujeito ao guarda de operabilidade da obra.</p>
     */
    @Transactional
    public ObraGeometriaResponse registrarCapturaCampo(
            String obraId,
            ObraGeometriaRequest request
    ) {
        return registrarCapturaCampo(obraId, request, null);
    }

    @Transactional
    public ObraGeometriaResponse registrarCapturaCampo(
            String obraId,
            ObraGeometriaRequest request,
            String idDoCliente
    ) {
        currentUserService.requireWorksiteAccess(obraId);
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Geometria obrigatória.");
        }
        String category = requiredText(request.categoria(), "Categoria geoespacial obrigatória.")
                .toUpperCase(Locale.ROOT);
        if (!FIELD_CAPTURE_CATEGORIES.contains(category)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "A captura de campo registra apenas ponto operacional ou frente de trabalho."
            );
        }
        return persistirNova(
                obraId,
                normalize(comOrigemDeCampo(request), obraId, false),
                idDoCliente
        );
    }

    private ObraGeometriaRequest comOrigemDeCampo(ObraGeometriaRequest request) {
        return new ObraGeometriaRequest(
                request.categoria(), request.objetoTipo(), request.objetoId(),
                request.geometry(), request.properties(), FIELD_CAPTURE_SOURCE,
                request.validoDesde(), request.baseVersao(), request.motivo()
        );
    }

    private ObraGeometriaResponse persistirNova(
            String obraId,
            NormalizedRequest normalized,
            String idDoCliente
    ) {
        operabilityGuard.requireWritable(obraId);
        String actorId = currentUserService.requireUserId();
        if (idDoCliente != null && !idDoCliente.isBlank()) {
            ObraGeometria existente = featureRepository
                    .findById(idDoCliente)
                    .orElse(null);
            if (existente != null) {
                // Replay: a identidade é do cliente, então recriá-la é reenviar
                // o mesmo registro. Devolver o que já existe — sem regravar e
                // sem republicar evidência — deixa a retentativa da fila
                // offline convergir em vez de morrer em chave duplicada.
                if (!obraId.equals(existente.getObraId())) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Já existe uma geometria com este identificador em outra obra."
                    );
                }
                return toResponse(existente);
            }
        }
        ObraGeometria saved = featureRepository.saveAndFlush(ObraGeometria.criar(
                idDoCliente,
                obraId, normalized.category(), normalized.objectType(), normalized.objectId(),
                normalized.geometryType(), normalized.geometryJson(), normalized.propertiesJson(),
                normalized.source(), normalized.validFrom(), actorId
        ));
        ObraGeometriaResponse response = toResponse(saved);
        memoryPublisher.criada(response, obraId, actorId);
        return response;
    }

    @Transactional
    public ObraGeometriaResponse atualizar(
            String obraId,
            String featureId,
            ObraGeometriaRequest request
    ) {
        currentUserService.requireAlfa();
        ObraGeometria feature = requireFeature(obraId, featureId);
        long baseVersion = requireBaseVersion(request == null ? null : request.baseVersao());
        requireMatchingVersion(feature, baseVersion);
        String reason = requiredText(request.motivo(), "Motivo da alteração geográfica obrigatório.");
        NormalizedRequest normalized = normalize(request, obraId, true);
        operabilityGuard.requireWritable(obraId);
        String actorId = currentUserService.requireUserId();
        ObraGeometriaResponse before = toResponse(feature);
        feature.atualizar(
                normalized.category(), normalized.objectType(), normalized.objectId(),
                normalized.geometryType(), normalized.geometryJson(), normalized.propertiesJson(),
                normalized.source(), normalized.validFrom(), actorId
        );
        ObraGeometriaResponse after = toResponse(featureRepository.saveAndFlush(feature));
        memoryPublisher.atualizada(before, after, obraId, actorId, baseVersion, reason);
        return after;
    }

    @Transactional
    public ObraGeometriaResponse encerrar(
            String obraId,
            String featureId,
            ObraGeometriaEndRequest request
    ) {
        currentUserService.requireAlfa();
        ObraGeometria feature = requireFeature(obraId, featureId);
        if (!"ATIVA".equals(feature.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Geometria já encerrada.");
        }
        long baseVersion = requireBaseVersion(request == null ? null : request.baseVersao());
        requireMatchingVersion(feature, baseVersion);
        String reason = requiredText(request.motivo(), "Motivo do encerramento obrigatório.");
        LocalDateTime validUntil = request.validoAte();
        if (validUntil != null && validUntil.isBefore(feature.getValidoDesde())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Fim da vigência não pode anteceder seu início."
            );
        }
        operabilityGuard.requireWritable(obraId);
        String actorId = currentUserService.requireUserId();
        ObraGeometriaResponse before = toResponse(feature);
        feature.encerrar(validUntil, reason, actorId);
        ObraGeometriaResponse after = toResponse(featureRepository.saveAndFlush(feature));
        memoryPublisher.encerrada(before, after, obraId, actorId, baseVersion, reason);
        return after;
    }

    private NormalizedRequest normalize(
            ObraGeometriaRequest request,
            String obraId,
            boolean update
    ) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Geometria obrigatória.");
        }
        String category = requiredText(request.categoria(), "Categoria geoespacial obrigatória.")
                .toUpperCase(Locale.ROOT);
        if (!CATEGORIES.contains(category)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Categoria geoespacial inválida.");
        }
        String geometryType = GeoJsonValidator.validate(request.geometry());
        validateCategoryGeometry(category, geometryType);

        String objectType = optionalUpper(request.objetoTipo());
        String objectId = optional(request.objetoId());
        if (category.equals("LOCALIZACAO_OBRA") || category.equals("PERIMETRO_OBRA")) {
            objectType = "OBRA";
            objectId = obraId;
        } else if ((objectType == null) != (objectId == null)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "objetoTipo e objetoId devem ser informados juntos."
            );
        } else if (objectType == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Camadas operacionais devem referenciar seu objeto ontológico."
            );
        }

        String source = optionalUpper(request.fonte());
        if (source == null) {
            source = "GESTAO_MAPA";
        }
        try {
            return new NormalizedRequest(
                    category, objectType, objectId, geometryType,
                    objectMapper.writeValueAsString(request.geometry()),
                    objectMapper.writeValueAsString(
                            request.properties() == null ? Map.of() : request.properties()
                    ),
                    source, request.validoDesde()
            );
        } catch (JsonProcessingException error) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "GeoJSON ou propriedades inválidas.", error
            );
        }
    }

    private void validateCategoryGeometry(String category, String geometryType) {
        if (POINT_CATEGORIES.contains(category) && !geometryType.equals("POINT")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "A categoria " + category + " exige geometria Point."
            );
        }
        if (category.equals("PERIMETRO_OBRA")
                && !Set.of("POLYGON", "MULTIPOLYGON").contains(geometryType)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Perímetro de obra exige Polygon ou MultiPolygon."
            );
        }
        if (Set.of("TRECHO", "FRENTE_TRABALHO").contains(category)
                && !Set.of("LINESTRING", "MULTILINESTRING", "POLYGON", "MULTIPOLYGON")
                .contains(geometryType)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Trechos e frentes exigem linha ou polígono."
            );
        }
    }

    private Obra requireWorksiteForRead(String obraId) {
        return obraRepository.findById(obraId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Obra não encontrada."));
    }

    private ObraGeometria requireFeature(String obraId, String featureId) {
        return featureRepository.findByIdAndObraId(featureId, obraId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Geometria da obra não encontrada."
                ));
    }

    private long requireBaseVersion(Long value) {
        if (value == null || value < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "baseVersao obrigatória.");
        }
        return value;
    }

    private void requireMatchingVersion(ObraGeometria feature, long baseVersion) {
        if (versaoCanonica(feature) != baseVersion) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A geometria foi alterada por outra operação. Recarregue antes de continuar."
            );
        }
    }

    /**
     * A versão pública da geometria é a versão canônica da entidade na
     * memória operacional — a mesma régua que o funil de sync confere antes de
     * qualquer atualização. A linha nasce em zero pelo {@code @Version} do
     * JPA enquanto o evento de criação já conta um; publicar a versão da linha
     * deixava cliente e funil permanentemente defasados em um, e toda edição
     * feita offline morreria em conflito de versão.
     */
    private long versaoCanonica(ObraGeometria feature) {
        return feature.getVersaoLinha() + 1;
    }

    private ObraGeometriaResponse toResponse(ObraGeometria feature) {
        try {
            JsonNode geometry = objectMapper.readTree(feature.getGeometriaJson());
            Map<String, Object> properties = objectMapper.readValue(
                    feature.getPropriedadesJson(), new TypeReference<>() { }
            );
            return new ObraGeometriaResponse(
                    feature.getId(), feature.getCategoria(), feature.getObjetoTipo(),
                    feature.getObjetoId(), geometry, properties, feature.getFonte(),
                    feature.getStatus(), feature.getValidoDesde(), feature.getValidoAte(),
                    feature.getMotivoEncerramento(), versaoCanonica(feature),
                    feature.getCriadoPor(), feature.getAtualizadoPor(),
                    feature.getCriadoEm(), feature.getAtualizadoEm()
            );
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Geometria persistida contém JSON inválido.", error);
        }
    }

    private String requiredText(String value, String message) {
        String normalized = optional(value);
        if (normalized == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return normalized;
    }

    private String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String optionalUpper(String value) {
        String normalized = optional(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private record NormalizedRequest(
            String category,
            String objectType,
            String objectId,
            String geometryType,
            String geometryJson,
            String propertiesJson,
            String source,
            LocalDateTime validFrom
    ) {
    }
}
