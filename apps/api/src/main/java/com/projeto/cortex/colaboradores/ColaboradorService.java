package com.projeto.cortex.colaboradores;

import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Service
public class ColaboradorService {

    private static final int MAX_LOOKUP_RESULTS = 50;

    /**
     * Quantas linhas o banco entrega antes do refino por palavra.
     *
     * <p>A consulta ao banco casa uma palavra só; as demais são conferidas
     * aqui. Sem uma folga sobre o limite de resposta, quem digitasse duas
     * palavras poderia perder alguém que a primeira palavra trouxe na posição
     * 51 — recorte cedo demais devolve menos do que existe.
     */
    private static final int LOOKUP_SCAN_LIMIT = MAX_LOOKUP_RESULTS * 20;

    private final ColaboradorRepository colaboradorRepository;

    public ColaboradorService(ColaboradorRepository colaboradorRepository) {
        this.colaboradorRepository = colaboradorRepository;
    }

    public List<ColaboradorResponse> listarColaboradores(String query) {
        List<Colaborador> colaboradores;

        String[] palavras = palavrasDaBusca(query);
        if (palavras.length == 0) {
            colaboradores = colaboradorRepository.findByDeletadoEmIsNullOrderByNomeAsc();
        } else {
            /*
             * O banco reduz por uma palavra; as outras são conferidas aqui.
             *
             * O LIKE '%termo%' original exigia que o digitado fosse um pedaço
             * contíguo do nome, então "paulo neris" não encontrava "PAULO
             * SERGIO DA SILVA NERIS" — essas duas palavras nunca aparecem
             * juntas. Na prática só o nome completo, na ordem exata, achava
             * alguém.
             *
             * A palavra mais longa vai ao banco porque é a que mais restringe.
             * O resto é filtrado em memória, sem acento e sem caixa, o que o
             * LOWER() do SQL sozinho não resolvia: quem digita "jose" precisa
             * achar "JOSÉ".
             */
            colaboradores = colaboradorRepository
                    .buscarColaboradoresAtivos(maisLonga(palavras))
                    .stream()
                    .limit(LOOKUP_SCAN_LIMIT)
                    .filter(colaborador -> casaTodasAsPalavras(colaborador, palavras))
                    .toList();
        }

        return colaboradores.stream()
                .limit(MAX_LOOKUP_RESULTS)
                .map(ColaboradorResponse::from)
                .toList();
    }

    private static String[] palavrasDaBusca(String query) {
        if (query == null || query.isBlank()) {
            return new String[0];
        }
        return Arrays.stream(normalizar(query).split("\\s+"))
                .filter(palavra -> !palavra.isBlank())
                .toArray(String[]::new);
    }

    private static String maisLonga(String[] palavras) {
        String escolhida = palavras[0];
        for (String palavra : palavras) {
            if (palavra.length() > escolhida.length()) {
                escolhida = palavra;
            }
        }
        return escolhida;
    }

    private static boolean casaTodasAsPalavras(
            Colaborador colaborador,
            String[] palavras
    ) {
        String alvo = normalizar(
                String.join(
                        " ",
                        vazioSeNulo(colaborador.getNome()),
                        vazioSeNulo(colaborador.getCodigoColaborador()),
                        vazioSeNulo(colaborador.getEmail()),
                        vazioSeNulo(colaborador.getNomeGrupo()),
                        vazioSeNulo(colaborador.getNomePerfil())
                )
        );
        for (String palavra : palavras) {
            if (!alvo.contains(palavra)) {
                return false;
            }
        }
        return true;
    }

    private static String vazioSeNulo(String valor) {
        return valor == null ? "" : valor;
    }

    /** Sem acento, sem caixa: o acento não pode virar requisito de busca. */
    private static String normalizar(String texto) {
        return Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase(Locale.ROOT)
                .trim();
    }
}
