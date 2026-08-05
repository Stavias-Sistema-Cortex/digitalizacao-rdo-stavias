package com.projeto.cortex.auth;

/**
 * Como um colaborador chega a uma obra — em SQL, num lugar só.
 *
 * <p>Há duas portas de entrada, e por muito tempo o código conhecia uma. O
 * vínculo direto ({@code vinculo_colaborador_obra}) é o registro pessoa a
 * pessoa. A equipe é o caminho que a operação realmente usa: monta-se a frente,
 * aloca-se na obra, e quem está dentro dela trabalha ali. Enxergar só a primeira
 * fazia o Córtex negar obra a quem estava escalado nela.
 *
 * <p>Os fragmentos vivem aqui porque a regra tem três consumidores — a checagem
 * de uma obra, a enumeração das obras do usuário e o filtro da lista — e três
 * cópias envelheceriam diferente. Numa fronteira de autorização, discordar não
 * dá tela estranha: dá pessoa vendo o que não devia, ou impedida do que devia. É
 * a classe de defeito que este arquivo existe para tornar impossível.
 *
 * <p>Vigência é parte da regra, não detalhe: equipe arquivada, alocação
 * encerrada e membro removido não autorizam ninguém. Uma frente que saiu da obra
 * não pode continuar dando acesso a ela, e é justamente esse o caso em que o
 * esquecimento seria silencioso — a linha continua no banco, só que encerrada.
 *
 * <p>A comparação de {@code colaborador_id} é por {@code LOWER} nos dois lados,
 * igual à do vínculo. Do lado da equipe isso não usa
 * {@code idx_equipe_membro_colaborador_status}, que é exato; aceita-se o custo
 * porque autorização que erra por diferença de caixa nega acesso a quem tem
 * direito, e essa é a falha cara. Onde a consulta parte da obra — a checagem
 * pontual e o filtro da lista — o índice de {@code equipe_obra (obra_id,
 * status, ...)} conduz a leitura e o {@code LOWER} recai sobre poucas linhas.
 */
public final class AutorizacaoDeObra {

    /**
     * Existe caminho vivo entre o colaborador do primeiro parâmetro e a obra
     * nomeada por {@code obraExpr}.
     *
     * <p>Recebe a expressão da obra em vez de um parâmetro porque um dos
     * consumidores correlaciona com a tabela externa ({@code o.id}) e os outros
     * passam valor. Os dois {@code ?} de colaborador são posicionais e precisam
     * ser preenchidos na ordem — vínculo primeiro, equipe depois.
     */
    public static String existeCaminhoParaObra(String obraExpr) {
        return """
                (
                    EXISTS (
                        SELECT 1
                        FROM vinculo_colaborador_obra vinculo
                        WHERE vinculo.obra_id = %1$s
                          AND LOWER(vinculo.colaborador_id) = LOWER(?)
                          AND vinculo.status = 'ATIVO'
                    )
                    OR EXISTS (
                        SELECT 1
                        FROM equipe_obra alocacao
                        JOIN equipe
                          ON equipe.id = alocacao.equipe_id
                         AND equipe.status = 'ATIVA'
                         AND equipe.deletado_em IS NULL
                        JOIN equipe_membro membro
                          ON membro.equipe_id = equipe.id
                         AND membro.status = 'ATIVO'
                         AND membro.fim_em IS NULL
                         AND membro.deletado_em IS NULL
                        WHERE alocacao.obra_id = %1$s
                          AND alocacao.status = 'ATIVO'
                          AND alocacao.fim_em IS NULL
                          AND LOWER(membro.colaborador_id) = LOWER(?)
                    )
                )
                """.formatted(obraExpr);
    }

    /**
     * As obras que o colaborador alcança, por qualquer das duas portas.
     *
     * <p>{@code UNION} e não {@code UNION ALL}: quem tem vínculo direto e também
     * está em duas equipes da mesma obra precisa aparecer uma vez. O conjunto
     * que sai daqui vira {@code Set}, então a duplicata não mudaria a decisão —
     * mas mudaria o custo, e a intenção fica dita no lugar onde ela vale.
     *
     * <p>Os dois {@code ?} são posicionais, vínculo primeiro.
     */
    public static final String OBRAS_ALCANCADAS = """
            SELECT vinculo.obra_id
            FROM vinculo_colaborador_obra vinculo
            WHERE LOWER(vinculo.colaborador_id) = LOWER(?)
              AND vinculo.status = 'ATIVO'

            UNION

            SELECT alocacao.obra_id
            FROM equipe_obra alocacao
            JOIN equipe
              ON equipe.id = alocacao.equipe_id
             AND equipe.status = 'ATIVA'
             AND equipe.deletado_em IS NULL
            JOIN equipe_membro membro
              ON membro.equipe_id = equipe.id
             AND membro.status = 'ATIVO'
             AND membro.fim_em IS NULL
             AND membro.deletado_em IS NULL
            WHERE alocacao.status = 'ATIVO'
              AND alocacao.fim_em IS NULL
              AND LOWER(membro.colaborador_id) = LOWER(?)
            """;

    private AutorizacaoDeObra() {
    }
}
