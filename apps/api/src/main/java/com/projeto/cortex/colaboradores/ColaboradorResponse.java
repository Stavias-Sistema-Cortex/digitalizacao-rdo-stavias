package com.projeto.cortex.colaboradores;

import java.time.LocalDateTime;

public record ColaboradorResponse(
        String id,
        String codigoColaborador,
        String cpfMascarado,
        String nome,
        String email,
        String nomeGrupo,
        String nomePerfil,
        boolean ativo,
        LocalDateTime atualizadoEm
) {
    public static ColaboradorResponse from(Colaborador colaborador) {
        return new ColaboradorResponse(
                colaborador.getId(),
                colaborador.getCodigoColaborador(),
                colaborador.getCpfMascarado(),
                colaborador.getNome(),
                colaborador.getEmail(),
                colaborador.getNomeGrupo(),
                colaborador.getNomePerfil(),
                colaborador.isAtivo(),
                colaborador.getAtualizadoEm()
        );
    }
}