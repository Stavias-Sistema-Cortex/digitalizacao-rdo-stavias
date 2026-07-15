package com.projeto.cortex.equipes;

import com.projeto.cortex.memory.CortexOperationalMemoryService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class EquipeMemoryPublisher {

    private static final String SOURCE = "GESTAO_EQUIPE";

    private final CortexOperationalMemoryService memoryService;

    public EquipeMemoryPublisher(CortexOperationalMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    public void equipeCriada(EquipeResponse equipe, String actorId) {
        memoryService.registrarObjeto(
                "EQUIPE",
                equipe.id(),
                equipe.id(),
                equipe.nome(),
                equipe.status(),
                SOURCE,
                "equipe",
                Map.of("obraPrincipalId", equipe.obraPrincipalId())
        );
        memoryService.registrarRelacaoAtiva(
                "EQUIPE",
                equipe.id(),
                "OBRA",
                equipe.obraPrincipalId(),
                "ATUA_EM",
                SOURCE,
                "Equipe vinculada temporalmente à obra."
        );

        Map<String, Object> after = teamState(equipe);
        publishEvent(
                "EQUIPE",
                equipe.id(),
                "EQUIPE_CRIADA",
                equipe.obraPrincipalId(),
                null,
                actorId,
                "CRIAR",
                Map.of(),
                after,
                List.copyOf(after.keySet()),
                null,
                equipe.versaoEntidade(),
                null,
                List.of(Map.of("tipo", "OBRA", "id", equipe.obraPrincipalId()))
        );
    }

    public void equipeAtualizada(
            EquipeResponse before,
            EquipeResponse after,
            String actorId,
            long baseVersion,
            String reason
    ) {
        memoryService.registrarObjeto(
                "EQUIPE",
                after.id(),
                after.id(),
                after.nome(),
                after.status(),
                SOURCE,
                "equipe",
                Map.of("obraPrincipalId", after.obraPrincipalId())
        );
        Map<String, Object> beforeState = teamState(before);
        Map<String, Object> afterState = teamState(after);
        publishEvent(
                "EQUIPE",
                after.id(),
                "EQUIPE_ATUALIZADA",
                after.obraPrincipalId(),
                null,
                actorId,
                "ATUALIZAR",
                beforeState,
                afterState,
                changedFields(beforeState, afterState),
                baseVersion,
                after.versaoEntidade(),
                reason,
                List.of(Map.of("tipo", "OBRA", "id", after.obraPrincipalId()))
        );
    }

    public void obraAssociada(
            EquipeResponse equipe,
            EquipeWorksiteResponse link,
            String actorId
    ) {
        memoryService.registrarObjeto(
                "ALOCACAO_EQUIPE_OBRA",
                link.id(),
                link.id(),
                equipe.nome() + " em " + link.obraNome(),
                link.status(),
                SOURCE,
                "equipe_obra",
                Map.of("equipeId", equipe.id(), "obraId", link.obraId())
        );
        memoryService.registrarRelacaoAtiva(
                "ALOCACAO_EQUIPE_OBRA", link.id(),
                "EQUIPE", equipe.id(),
                "ALOCA_EQUIPE", SOURCE,
                "Associação temporal da equipe com a obra."
        );
        memoryService.registrarRelacaoAtiva(
                "ALOCACAO_EQUIPE_OBRA", link.id(),
                "OBRA", link.obraId(),
                "OCORRE_EM", SOURCE,
                "Obra da associação temporal."
        );
        memoryService.registrarRelacaoAtiva(
                "EQUIPE", equipe.id(),
                "OBRA", link.obraId(),
                "ATUA_EM", SOURCE,
                "Equipe vinculada temporalmente à obra."
        );

        Map<String, Object> after = worksiteState(link);
        publishEvent(
                "ALOCACAO_EQUIPE_OBRA",
                link.id(),
                "EQUIPE_VINCULADA_OBRA",
                link.obraId(),
                null,
                actorId,
                "ASSOCIAR_OBRA",
                Map.of(),
                after,
                List.copyOf(after.keySet()),
                null,
                link.versaoEntidade(),
                null,
                List.of(
                        Map.of("tipo", "EQUIPE", "id", equipe.id()),
                        Map.of("tipo", "OBRA", "id", link.obraId())
                )
        );
    }

    public void obraDesassociada(
            EquipeResponse equipe,
            EquipeWorksiteResponse before,
            EquipeWorksiteResponse after,
            String actorId,
            long baseVersion,
            String reason
    ) {
        memoryService.registrarObjeto(
                "ALOCACAO_EQUIPE_OBRA",
                after.id(),
                after.id(),
                equipe.nome() + " em " + after.obraNome(),
                after.status(),
                SOURCE,
                "equipe_obra",
                Map.of("equipeId", equipe.id(), "obraId", after.obraId())
        );
        memoryService.encerrarRelacaoAtiva(
                "ALOCACAO_EQUIPE_OBRA", after.id(),
                "EQUIPE", equipe.id(), "ALOCA_EQUIPE"
        );
        memoryService.encerrarRelacaoAtiva(
                "ALOCACAO_EQUIPE_OBRA", after.id(),
                "OBRA", after.obraId(), "OCORRE_EM"
        );
        memoryService.encerrarRelacaoAtiva(
                "EQUIPE", equipe.id(),
                "OBRA", after.obraId(), "ATUA_EM"
        );

        Map<String, Object> beforeState = worksiteState(before);
        Map<String, Object> afterState = worksiteState(after);
        publishEvent(
                "ALOCACAO_EQUIPE_OBRA",
                after.id(),
                "EQUIPE_DESVINCULADA_OBRA",
                after.obraId(),
                null,
                actorId,
                "ENCERRAR_ASSOCIACAO_OBRA",
                beforeState,
                afterState,
                changedFields(beforeState, afterState),
                baseVersion,
                after.versaoEntidade(),
                reason,
                List.of(
                        Map.of("tipo", "EQUIPE", "id", equipe.id()),
                        Map.of("tipo", "OBRA", "id", after.obraId())
                )
        );
    }

    public void membroAdicionado(
            EquipeResponse equipe,
            EquipeMemberResponse member,
            String actorId
    ) {
        memoryService.registrarObjeto(
                "PARTICIPACAO_EQUIPE",
                member.id(),
                member.id(),
                member.colaboradorNome() + " em " + equipe.nome(),
                member.status(),
                SOURCE,
                "equipe_membro",
                Map.of("equipeId", equipe.id(), "colaboradorId", member.colaboradorId())
        );
        memoryService.registrarRelacaoAtiva(
                "PARTICIPACAO_EQUIPE", member.id(),
                "EQUIPE", equipe.id(),
                "MEMBRO_DE", SOURCE,
                "Participação temporal na equipe."
        );
        memoryService.registrarRelacaoAtiva(
                "PARTICIPACAO_EQUIPE", member.id(),
                "COLABORADOR", member.colaboradorId(),
                "REPRESENTA_COLABORADOR", SOURCE,
                "Colaborador participante."
        );
        memoryService.registrarRelacaoAtiva(
                "PARTICIPACAO_EQUIPE", member.id(),
                "FUNCAO_OPERACIONAL", member.funcaoOperacionalId(),
                "EXERCE_FUNCAO", SOURCE,
                "Função exercida durante a participação."
        );
        if (member.responsavel()) {
            memoryService.registrarRelacaoAtiva(
                    "COLABORADOR", member.colaboradorId(),
                    "EQUIPE", equipe.id(),
                    "LIDERA", SOURCE,
                    "Responsável atual pela equipe."
            );
        }

        Map<String, Object> after = memberState(member);
        publishEvent(
                "PARTICIPACAO_EQUIPE",
                member.id(),
                "MEMBRO_EQUIPE_ADICIONADO",
                equipe.obraPrincipalId(),
                member.colaboradorId(),
                actorId,
                "ADICIONAR_MEMBRO",
                Map.of(),
                after,
                List.copyOf(after.keySet()),
                null,
                member.versaoEntidade(),
                null,
                List.of(
                        Map.of("tipo", "EQUIPE", "id", equipe.id()),
                        Map.of("tipo", "COLABORADOR", "id", member.colaboradorId()),
                        Map.of("tipo", "FUNCAO_OPERACIONAL", "id", member.funcaoOperacionalId())
                )
        );
    }

    public void membroEncerrado(
            EquipeResponse equipe,
            EquipeMemberResponse before,
            EquipeMemberResponse after,
            String actorId,
            long baseVersion,
            String reason
    ) {
        memoryService.registrarObjeto(
                "PARTICIPACAO_EQUIPE",
                after.id(),
                after.id(),
                after.colaboradorNome() + " em " + equipe.nome(),
                after.status(),
                SOURCE,
                "equipe_membro",
                Map.of("equipeId", equipe.id(), "colaboradorId", after.colaboradorId())
        );
        memoryService.encerrarRelacaoAtiva(
                "PARTICIPACAO_EQUIPE", after.id(),
                "EQUIPE", equipe.id(), "MEMBRO_DE"
        );
        memoryService.encerrarRelacaoAtiva(
                "PARTICIPACAO_EQUIPE", after.id(),
                "COLABORADOR", after.colaboradorId(), "REPRESENTA_COLABORADOR"
        );
        memoryService.encerrarRelacaoAtiva(
                "PARTICIPACAO_EQUIPE", after.id(),
                "FUNCAO_OPERACIONAL", after.funcaoOperacionalId(), "EXERCE_FUNCAO"
        );
        if (before.responsavel()) {
            memoryService.encerrarRelacaoAtiva(
                    "COLABORADOR", after.colaboradorId(),
                    "EQUIPE", equipe.id(), "LIDERA"
            );
        }

        Map<String, Object> beforeState = memberState(before);
        Map<String, Object> afterState = memberState(after);
        publishEvent(
                "PARTICIPACAO_EQUIPE",
                after.id(),
                "MEMBRO_EQUIPE_ENCERRADO",
                equipe.obraPrincipalId(),
                after.colaboradorId(),
                actorId,
                "ENCERRAR_MEMBRO",
                beforeState,
                afterState,
                changedFields(beforeState, afterState),
                baseVersion,
                after.versaoEntidade(),
                reason,
                List.of(
                        Map.of("tipo", "EQUIPE", "id", equipe.id()),
                        Map.of("tipo", "COLABORADOR", "id", after.colaboradorId())
                )
        );
    }

    public void membroAtualizado(
            EquipeResponse equipe,
            EquipeMemberResponse before,
            EquipeMemberResponse after,
            String actorId,
            long baseVersion,
            String reason
    ) {
        memoryService.registrarObjeto(
                "PARTICIPACAO_EQUIPE",
                after.id(),
                after.id(),
                after.colaboradorNome() + " em " + equipe.nome(),
                after.status(),
                SOURCE,
                "equipe_membro",
                Map.of("equipeId", equipe.id(), "colaboradorId", after.colaboradorId())
        );
        if (!before.funcaoOperacionalId().equals(after.funcaoOperacionalId())) {
            memoryService.encerrarRelacaoAtiva(
                    "PARTICIPACAO_EQUIPE", after.id(),
                    "FUNCAO_OPERACIONAL", before.funcaoOperacionalId(), "EXERCE_FUNCAO"
            );
            memoryService.registrarRelacaoAtiva(
                    "PARTICIPACAO_EQUIPE", after.id(),
                    "FUNCAO_OPERACIONAL", after.funcaoOperacionalId(),
                    "EXERCE_FUNCAO", SOURCE,
                    "Função exercida durante a participação."
            );
        }
        if (before.responsavel() && !after.responsavel()) {
            memoryService.encerrarRelacaoAtiva(
                    "COLABORADOR", after.colaboradorId(),
                    "EQUIPE", equipe.id(), "LIDERA"
            );
        } else if (!before.responsavel() && after.responsavel()) {
            memoryService.registrarRelacaoAtiva(
                    "COLABORADOR", after.colaboradorId(),
                    "EQUIPE", equipe.id(), "LIDERA", SOURCE,
                    "Responsável atual pela equipe."
            );
        }

        Map<String, Object> beforeState = memberState(before);
        Map<String, Object> afterState = memberState(after);
        publishEvent(
                "PARTICIPACAO_EQUIPE",
                after.id(),
                "MEMBRO_EQUIPE_ATUALIZADO",
                equipe.obraPrincipalId(),
                after.colaboradorId(),
                actorId,
                "ATUALIZAR_MEMBRO",
                beforeState,
                afterState,
                changedFields(beforeState, afterState),
                baseVersion,
                after.versaoEntidade(),
                reason,
                List.of(
                        Map.of("tipo", "EQUIPE", "id", equipe.id()),
                        Map.of("tipo", "COLABORADOR", "id", after.colaboradorId()),
                        Map.of("tipo", "FUNCAO_OPERACIONAL", "id", after.funcaoOperacionalId())
                )
        );
    }

    public void equipeArquivada(
            EquipeResponse before,
            EquipeResponse after,
            String actorId,
            long baseVersion,
            String reason
    ) {
        memoryService.registrarObjeto(
                "EQUIPE",
                after.id(),
                after.id(),
                after.nome(),
                after.status(),
                SOURCE,
                "equipe",
                Map.of("obraPrincipalId", after.obraPrincipalId())
        );
        memoryService.encerrarRelacaoAtiva(
                "EQUIPE", after.id(),
                "OBRA", after.obraPrincipalId(), "ATUA_EM"
        );
        Map<String, Object> beforeState = teamState(before);
        Map<String, Object> afterState = teamState(after);
        publishEvent(
                "EQUIPE",
                after.id(),
                "EQUIPE_ARQUIVADA",
                after.obraPrincipalId(),
                null,
                actorId,
                "ARQUIVAR",
                beforeState,
                afterState,
                changedFields(beforeState, afterState),
                baseVersion,
                after.versaoEntidade(),
                reason,
                List.of(Map.of("tipo", "OBRA", "id", after.obraPrincipalId()))
        );
    }

    public void funcaoCriada(FuncaoOperacionalResponse function, String actorId) {
        memoryService.registrarObjeto(
                "FUNCAO_OPERACIONAL",
                function.id(),
                function.codigo(),
                function.nome(),
                function.ativo() ? "ATIVA" : "INATIVA",
                SOURCE,
                "funcao_operacional",
                Map.of("ordemExibicao", function.ordemExibicao())
        );
        Map<String, Object> after = functionState(function);
        publishEvent(
                "FUNCAO_OPERACIONAL",
                function.id(),
                "FUNCAO_OPERACIONAL_CRIADA",
                null,
                null,
                actorId,
                "CRIAR_FUNCAO",
                Map.of(),
                after,
                List.copyOf(after.keySet()),
                null,
                function.versaoEntidade(),
                null,
                List.of()
        );
    }

    public void funcaoAtualizada(
            FuncaoOperacionalResponse before,
            FuncaoOperacionalResponse after,
            String actorId,
            long baseVersion
    ) {
        memoryService.registrarObjeto(
                "FUNCAO_OPERACIONAL",
                after.id(),
                after.codigo(),
                after.nome(),
                after.ativo() ? "ATIVA" : "INATIVA",
                SOURCE,
                "funcao_operacional",
                Map.of("ordemExibicao", after.ordemExibicao())
        );
        Map<String, Object> beforeState = functionState(before);
        Map<String, Object> afterState = functionState(after);
        publishEvent(
                "FUNCAO_OPERACIONAL",
                after.id(),
                "FUNCAO_OPERACIONAL_ATUALIZADA",
                null,
                null,
                actorId,
                "ATUALIZAR_FUNCAO",
                beforeState,
                afterState,
                changedFields(beforeState, afterState),
                baseVersion,
                after.versaoEntidade(),
                null,
                List.of()
        );
    }

    private Map<String, Object> teamState(EquipeResponse equipe) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", equipe.id());
        state.put("obraId", equipe.obraPrincipalId());
        state.put("nome", equipe.nome());
        state.put("descricao", equipe.descricao());
        state.put("status", equipe.status());
        state.put("inicioValidadeEm", equipe.inicioValidadeEm());
        state.put("fimValidadeEm", equipe.fimValidadeEm());
        return state;
    }

    private Map<String, Object> memberState(EquipeMemberResponse member) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", member.id());
        state.put("equipeId", member.equipeId());
        state.put("colaboradorId", member.colaboradorId());
        state.put("papelAcesso", member.papelAcesso());
        state.put("funcaoOperacionalId", member.funcaoOperacionalId());
        state.put("responsavel", member.responsavel());
        state.put("status", member.status());
        state.put("inicioEm", member.inicioEm());
        state.put("fimEm", member.fimEm());
        state.put("motivoEncerramento", member.motivoEncerramento());
        return state;
    }

    private Map<String, Object> worksiteState(EquipeWorksiteResponse link) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", link.id());
        state.put("equipeId", link.equipeId());
        state.put("obraId", link.obraId());
        state.put("status", link.status());
        state.put("inicioEm", link.inicioEm());
        state.put("fimEm", link.fimEm());
        state.put("motivoEncerramento", link.motivoEncerramento());
        return state;
    }

    private Map<String, Object> functionState(FuncaoOperacionalResponse function) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", function.id());
        state.put("codigo", function.codigo());
        state.put("nome", function.nome());
        state.put("descricao", function.descricao());
        state.put("ativo", function.ativo());
        state.put("ordemExibicao", function.ordemExibicao());
        return state;
    }

    private List<String> changedFields(
            Map<String, Object> before,
            Map<String, Object> after
    ) {
        return after.keySet().stream()
                .filter(key -> !java.util.Objects.equals(before.get(key), after.get(key)))
                .toList();
    }

    private void publishEvent(
            String entityType,
            String entityId,
            String eventType,
            String worksiteId,
            String collaboratorId,
            String actorId,
            String operation,
            Map<String, Object> beforeState,
            Map<String, Object> afterState,
            List<String> changedFields,
            Long baseVersion,
            long entityVersion,
            String reason,
            List<Map<String, Object>> relatedEntities
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("actorId", actorId);
        payload.put("operation", operation);
        payload.put("beforeState", beforeState);
        payload.put("afterState", afterState);
        payload.put("changedFields", changedFields);
        payload.put("baseVersion", baseVersion);
        payload.put("entityVersion", entityVersion);
        payload.put("reason", reason);
        payload.put("worksiteId", worksiteId);

        LocalDateTime now = LocalDateTime.now();
        memoryService.registrarEventoDetalhado(
                UUID.randomUUID().toString(),
                entityType,
                entityId,
                eventType,
                SOURCE,
                worksiteId,
                null,
                collaboratorId,
                relatedEntities,
                "ONLINE",
                "SYNCED",
                now,
                now,
                1,
                payload
        );
    }
}
