package com.projeto.cortex.memory;

/**
 * Observação recém-gravada no grafo operacional do Córtex.
 *
 * Publicada a cada evento novo (não em replays idempotentes), para que
 * consumidores como o PDOR reajam a mudanças reais da ontologia.
 */
public record CortexObservacaoRegistrada(
        String eventoId,
        String tipoEntidade,
        String entidadeId,
        String tipoEvento,
        String fonte,
        String obraId
) {
    public boolean vinculadaAObra() {
        return obraIdResolvido() != null;
    }

    public String obraIdResolvido() {
        if (obraId != null && !obraId.isBlank()) {
            return obraId.trim();
        }

        if ("OBRA".equalsIgnoreCase(tipoEntidade)
                && entidadeId != null
                && !entidadeId.isBlank()) {
            return entidadeId.trim();
        }

        return null;
    }
}
