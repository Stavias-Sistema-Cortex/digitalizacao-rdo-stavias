package com.projeto.cortex.colaboradores;

/**
 * Colaborador operacionalmente ligado a uma obra (alocado ou lançado em RDO
 * dela). Escopo restrito à obra — não expõe o catálogo global de colaboradores.
 * Carrega apenas o necessário para a operação offline (identificação e perfil),
 * sem dados sensíveis além do CPF já mascarado.
 */
public record ColaboradorDaObraResponse(
        String id,
        String nome,
        String cpfMascarado,
        String nomePerfil,
        String nomeGrupo
) {
}
