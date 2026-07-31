package com.projeto.cortex.obras.trecho;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Projeção linear do trecho de rodovia de uma obra.
 *
 * <p>Todo o conteúdo é derivado de linhas persistidas em
 * {@code programacao_operacional}, {@code rdo_controle_geometrico} e
 * {@code execucao_servico_rdo}. Nenhum segmento é sintetizado: quando a obra não
 * possui produção lançada a coleção volta vazia e o resumo fica zerado.</p>
 */
public record ObraTrechoResponse(
        String obraId,
        String obraNome,
        String rodovia,
        boolean operavel,
        List<String> sentidos,
        List<String> faixas,
        BigDecimal kmMin,
        BigDecimal kmMax,
        LocalDateTime atualizadoEm,
        Periodo periodoDisponivel,
        Periodo periodoAplicado,
        List<SegmentoTrecho> segmentos,
        List<DiaExecutado> diasExecutados,
        ResumoTrecho resumo
) {

    /**
     * Intervalo de datas. `de` e `ate` são nulos quando não há limite, e
     * `periodoDisponivel` descreve a extensão real do que a obra registrou.
     */
    public record Periodo(LocalDate de, LocalDate ate) {
    }

    /**
     * Um dia em que a obra teve produção lançada.
     *
     * Permite percorrer o período e saber exatamente em que data cada parte do
     * trecho foi executada, sem recalcular nada no cliente.
     */
    public record DiaExecutado(
            LocalDate data,
            int totalSegmentos,
            int totalRdos,
            BigDecimal extensaoM,
            BigDecimal massaTonelada,
            BigDecimal kmMin,
            BigDecimal kmMax
    ) {
    }

    /** Origem autoritativa de um segmento da projeção. */
    public enum Origem {
        /** Trecho planejado em {@code programacao_operacional}. */
        PROGRAMACAO,
        /** Controle geométrico executado, lançado no verso do RDO. */
        RDO_CONTROLE,
        /** Serviço executado com trecho inicial/final declarado no RDO. */
        EXECUCAO_SERVICO
    }

    public record SegmentoTrecho(
            String id,
            Origem origem,
            String rdoId,
            String numeroRdo,
            LocalDate data,
            String servicoNome,
            String subtrecho,
            String sentido,
            String pista,
            String faixa,
            BigDecimal kmInicial,
            BigDecimal kmFinal,
            String estacaInicial,
            String estacaFinal,
            BigDecimal extensaoM,
            BigDecimal larguraM,
            BigDecimal areaM2,
            BigDecimal massaTonelada,
            String status,
            /**
             * Situação do RDO que originou o lançamento: `RASCUNHO` enquanto o
             * apontador ainda preenche, `ENVIADO` depois de fechado. Nulo para
             * o que veio da programação.
             */
            String rdoStatus,
            /**
             * Verdadeiro quando a pista (e, junto dela, a faixa) não veio no
             * próprio lançamento e foi herdada do controle geométrico do mesmo
             * RDO. A projeção posiciona o segmento, mas quem lê precisa saber
             * que a pista é conclusão e não declaração.
             */
            boolean pistaInferida
    ) {
    }

    /**
     * Consolidado da produção confirmada.
     *
     * Lançamento de RDO ainda em rascunho não entra nos totais: ele descreve o
     * que a equipe está fazendo agora, não o que a obra já fechou. O
     * {@code totalRascunhos} deixa essa parcela visível em vez de escondida.
     */
    public record ResumoTrecho(
            int totalSegmentos,
            int totalRdos,
            int totalRascunhos,
            BigDecimal extensaoTotalM,
            BigDecimal areaTotalM2,
            BigDecimal massaTotalTonelada,
            LocalDate primeiraExecucao,
            LocalDate ultimaExecucao
    ) {
    }
}
