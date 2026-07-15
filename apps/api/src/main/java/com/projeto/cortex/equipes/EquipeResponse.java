package com.projeto.cortex.equipes;

import java.time.LocalDateTime;
import java.util.List;

public record EquipeResponse(
        String id,
        String obraPrincipalId,
        String obraNome,
        String nome,
        String descricao,
        String status,
        LocalDateTime inicioValidadeEm,
        LocalDateTime fimValidadeEm,
        long versaoEntidade,
        LocalDateTime criadoEm,
        LocalDateTime atualizadoEm,
        List<EquipeMemberResponse> membros
) {
    public EquipeResponse {
        membros = membros == null ? List.of() : List.copyOf(membros);
    }

    public EquipeResponse withMembros(List<EquipeMemberResponse> novosMembros) {
        return new EquipeResponse(
                id,
                obraPrincipalId,
                obraNome,
                nome,
                descricao,
                status,
                inicioValidadeEm,
                fimValidadeEm,
                versaoEntidade,
                criadoEm,
                atualizadoEm,
                novosMembros
        );
    }
}
