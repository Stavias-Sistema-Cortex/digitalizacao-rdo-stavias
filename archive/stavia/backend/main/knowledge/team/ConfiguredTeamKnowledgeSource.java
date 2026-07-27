package com.projeto.cortex.intelligence.stavia.knowledge.team;

import com.projeto.cortex.intelligence.stavia.StaviaEngine;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.interpret.StaviaEntityFilters;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.knowledge.registry.StaviaSourceDescriptor;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.intelligence.stavia.planning.QueryDomain;
import com.projeto.cortex.intelligence.stavia.planning.QueryOperation;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ConfiguredTeamKnowledgeSource implements StaviaKnowledgeSource {

    private final ConfiguredTeamReader reader;

    public ConfiguredTeamKnowledgeSource(ConfiguredTeamReader reader) {
        this.reader = reader;
    }

    @Override
    public String sourceName() {
        return "equipes-configuradas";
    }

    @Override
    public String sourceVersion() {
        return "STAVIA-CONFIGURED-TEAM-SOURCE-0.1.0";
    }

    @Override
    public StaviaSourceDescriptor descriptor() {
        return new StaviaSourceDescriptor(
                sourceName(), sourceVersion(), Set.of(QueryDomain.EQUIPE),
                Set.of("EQUIPE", "EQUIPE_MEMBRO"),
                Set.of("nome", "membro", "funcao", "responsavel", "atribuidoPor"),
                Set.of("ATUA_EM", "MEMBRO_DE", "EXERCE_FUNCAO"),
                Set.of(QueryOperation.READ_ATTRIBUTE, QueryOperation.LIST_OBJECTS),
                Set.of(StaviaEngine.REQUIRED_PERMISSION), "Sob demanda", 10, 200
        );
    }

    @Override
    public boolean supports(StaviaKnowledgeRequest request) {
        return request != null
                && request.permissions().contains(StaviaEngine.REQUIRED_PERMISSION)
                && request.intent() == StaviaIntent.CONSULTAR_EQUIPE;
    }

    @Override
    public List<StaviaEvidence> retrieve(StaviaKnowledgeRequest request) {
        StaviaEntityFilters filters = StaviaEntityFilters.from(
                request.plan().entities()
        );
        List<ConfiguredTeamRecord> records = reader
                .findCurrentByWorksiteId(request.worksiteId())
                .stream()
                .filter(record -> filters.matchesRole(record.functionName()))
                .filter(record -> filters.matchesCollaborator(record.collaboratorName()))
                .toList();
        if (records.isEmpty()) {
            return List.of(new StaviaEvidence(
                    StaviaEvidenceTypes.EQUIPE,
                    "EQUIPE:NENHUMA:" + request.worksiteId(),
                    "Nenhuma equipe configurada vigente foi encontrada para a obra.",
                    null,
                    false,
                    Map.of(
                            "obraId", request.worksiteId(),
                            "equipeConfigurada", true,
                            "encontrado", false
                    )
            ));
        }
        return records.stream().map(this::toEvidence).toList();
    }

    private StaviaEvidence toEvidence(ConfiguredTeamRecord record) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("obraId", record.worksiteId());
        attributes.put("equipeId", record.teamId());
        attributes.put("equipeNome", record.teamName());
        attributes.put("equipeConfigurada", true);
        attributes.put("encontrado", true);
        attributes.put("permissaoAplicada", StaviaEngine.REQUIRED_PERMISSION);
        attributes.put("linkInterno", "/equipes?equipe=" + record.teamId());
        putText(attributes, "membroId", record.membershipId());
        putText(attributes, "colaboradorId", record.collaboratorId());
        putText(attributes, "colaboradorNome", record.collaboratorName());
        putText(attributes, "funcaoId", record.functionId());
        putText(attributes, "funcaoNome", record.functionName());
        putText(attributes, "atribuidoPorId", record.assignedById());
        putText(attributes, "atribuidoPorNome", record.assignedByName());
        attributes.put("responsavel", record.responsible());
        if (record.assignedAt() != null) {
            attributes.put("atribuidoEm", record.assignedAt().toString());
        }

        String summary = record.membershipId() == null
                ? "A equipe " + record.teamName() + " está vigente na obra e não possui membros vigentes."
                : "A equipe " + record.teamName() + " possui "
                        + record.collaboratorName() + " na função "
                        + record.functionName()
                        + (record.responsible() ? " como responsável." : ".");
        String evidenceId = record.membershipId() == null
                ? record.teamId()
                : record.membershipId();
        return new StaviaEvidence(
                StaviaEvidenceTypes.EQUIPE,
                evidenceId,
                summary,
                record.updatedAt() == null
                        ? null
                        : record.updatedAt().toInstant(ZoneOffset.UTC),
                true,
                attributes
        );
    }

    private void putText(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) target.put(key, value);
    }
}
