package com.projeto.cortex.intelligence.stavia.ontology.api;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.intelligence.stavia.ontology.model.OntologyEvent;
import com.projeto.cortex.intelligence.stavia.ontology.model.OntologyRelation;
import com.projeto.cortex.intelligence.stavia.ontology.model.OperationalEvidence;
import com.projeto.cortex.intelligence.stavia.ontology.model.OperationalState;
import com.projeto.cortex.intelligence.stavia.ontology.service.StaviaOntologyService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Acesso ao grafo ontológico em formato bruto (entidades, relações, eventos,
 * estados e evidências, por id, sem escopo de obra). É uma ferramenta
 * administrativa: exclusiva do papel Alfa (§8/§9). Um usuário Beta não pode ver
 * JSON bruto nem consultar entidades por id fora do seu escopo — todo endpoint
 * exige Alfa antes de qualquer leitura.
 */
@RestController
public class OntologyController {

    private final StaviaOntologyService ontologyService;
    private final CurrentUserService currentUserService;

    public OntologyController(
            StaviaOntologyService ontologyService,
            CurrentUserService currentUserService
    ) {
        this.ontologyService = ontologyService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/api/ontology/entities")
    public List<OntologyEntityResponse> entities(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer limit
    ) {
        currentUserService.requireAdmin();
        return ontologyService.listEntities(type, q, limit)
                .stream()
                .map(OntologyEntityResponse::from)
                .toList();
    }

    @GetMapping("/api/ontology/search")
    public List<OntologyEntityResponse> search(
            @RequestParam String q,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer limit
    ) {
        currentUserService.requireAdmin();
        return entities(type, q, limit);
    }

    @GetMapping("/api/ontology/entities/{id}")
    public OntologyEntityResponse entity(
            @PathVariable String id
    ) {
        currentUserService.requireAdmin();
        return ontologyService.findEntity(id)
                .map(OntologyEntityResponse::from)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Entidade ontológica não encontrada."
                ));
    }

    @GetMapping("/api/ontology/entities/{id}/relations")
    public List<OntologyRelation> relations(
            @PathVariable String id
    ) {
        currentUserService.requireAdmin();
        return ontologyService.relationsForEntity(id);
    }

    @GetMapping("/api/ontology/entities/{id}/events")
    public List<OntologyEvent> events(
            @PathVariable String id
    ) {
        currentUserService.requireAdmin();
        return ontologyService.eventsForEntity(id);
    }

    @GetMapping("/api/ontology/entities/{id}/states")
    public List<OperationalState> states(
            @PathVariable String id
    ) {
        currentUserService.requireAdmin();
        return ontologyService.statesForEntity(id);
    }

    @GetMapping("/api/ontology/entities/{id}/evidences")
    public List<OperationalEvidence> evidences(
            @PathVariable String id
    ) {
        currentUserService.requireAdmin();
        return ontologyService.evidencesForEntity(id);
    }
}
