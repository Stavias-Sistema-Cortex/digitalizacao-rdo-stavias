package com.projeto.cortex.intelligence.stavia.ontology.api;

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

@RestController
public class OntologyController {

    private final StaviaOntologyService ontologyService;

    public OntologyController(StaviaOntologyService ontologyService) {
        this.ontologyService = ontologyService;
    }

    @GetMapping("/api/ontology/entities")
    public List<OntologyEntityResponse> entities(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer limit
    ) {
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
        return entities(type, q, limit);
    }

    @GetMapping("/api/ontology/entities/{id}")
    public OntologyEntityResponse entity(
            @PathVariable String id
    ) {
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
        return ontologyService.relationsForEntity(id);
    }

    @GetMapping("/api/ontology/entities/{id}/events")
    public List<OntologyEvent> events(
            @PathVariable String id
    ) {
        return ontologyService.eventsForEntity(id);
    }

    @GetMapping("/api/ontology/entities/{id}/states")
    public List<OperationalState> states(
            @PathVariable String id
    ) {
        return ontologyService.statesForEntity(id);
    }

    @GetMapping("/api/ontology/entities/{id}/evidences")
    public List<OperationalEvidence> evidences(
            @PathVariable String id
    ) {
        return ontologyService.evidencesForEntity(id);
    }
}
