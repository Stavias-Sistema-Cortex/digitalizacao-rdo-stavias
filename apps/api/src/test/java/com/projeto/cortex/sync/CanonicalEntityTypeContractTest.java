package com.projeto.cortex.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Contrato entre o que a PWA consegue emitir e o que o sync aceita.
 *
 * <p>As duas pontas mantinham listas literais separadas de tipos canônicos.
 * Quando a camada geoespacial ganhou {@code GEOMETRIA_OBRA}, só o lado do
 * dispositivo foi atualizado: cada trecho desenhado subia, era recusado com
 * "entityType canônico não suportado" e virava um registro em revisão que
 * travava a fila inteira — com o handler correspondente já registrado e pronto
 * no servidor.</p>
 *
 * <p>A divergência não aparecia em teste algum porque cada lado se media pela
 * própria lista. Este teste lê a lista da PWA e exige que o servidor aceite
 * todos os tipos dela.</p>
 */
class CanonicalEntityTypeContractTest {

    private static final Path REPOSITORY_ROOT = Path.of("../..");
    private static final Path ENVELOPE_SOURCE = REPOSITORY_ROOT.resolve(
            "apps/web/src/lib/sync/mutationEnvelope.ts"
    );
    private static final Pattern DECLARACAO = Pattern.compile(
            "const SYNC_ENTITY_TYPES = \\[(.*?)\\]", Pattern.DOTALL
    );
    private static final Pattern TIPO = Pattern.compile("\"([A-Z][A-Z0-9_]*)\"");
    private static final Pattern DECLARACAO_OPERACOES = Pattern.compile(
            "const TRANSPORT_OPERATION_TO_CANONICAL = \\{(.*?)\\} as const",
            Pattern.DOTALL
    );
    private static final Pattern PAR_OPERACAO = Pattern.compile(
            "([A-Z][A-Z0-9_]*):\\s*\"([A-Z]+)\""
    );

    @Test
    void servidorAceitaTodoTipoCanonicoQueAPwaConsegueEmitir() throws IOException {
        Set<String> tiposDaPwa = tiposDeclaradosNaPwa();

        assertThat(tiposDaPwa)
                .as("lista de tipos canônicos lida de mutationEnvelope.ts")
                .isNotEmpty()
                .contains("GEOMETRIA_OBRA");
        assertThat(SyncService.canonicalEntityTypes())
                .as(
                        "todo tipo que a PWA enfileira precisa ser aceito no push;"
                                + " senão a mutação é recusada e trava a fila"
                )
                .containsAll(tiposDaPwa);
    }


    @Test
    void servidorEsperaAMesmaOperacaoCanonicaQueAPwaEmite() throws IOException {
        // Segunda lista literal do mesmo validador. A primeira recusava a
        // geometria pelo tipo; corrigido o tipo, a mesma mutação passou a ser
        // recusada pela operação — com o handler pronto dos dois lados.
        Map<String, String> daPwa = operacoesDeclaradasNaPwa();
        Map<String, String> doServidor = SyncService.canonicalOperationByTransport();

        assertThat(daPwa)
                .as("mapa de operações lido de mutationEnvelope.ts")
                .isNotEmpty()
                .containsKey("REGISTRAR_GEOMETRIA_OBRA");
        assertThat(doServidor)
                .as(
                        "toda operação de transporte que a PWA enfileira precisa"
                                + " existir no servidor com a mesma operação"
                                + " canônica; divergir recusa a mutação e trava"
                                + " a fila"
                )
                .containsAllEntriesOf(daPwa);
    }

    private Map<String, String> operacoesDeclaradasNaPwa() throws IOException {
        String fonte = Files.readString(ENVELOPE_SOURCE);
        Matcher declaracao = DECLARACAO_OPERACOES.matcher(fonte);
        assertThat(declaracao.find())
                .as("TRANSPORT_OPERATION_TO_CANONICAL declarado")
                .isTrue();

        Map<String, String> operacoes = new LinkedHashMap<>();
        Matcher par = PAR_OPERACAO.matcher(declaracao.group(1));
        while (par.find()) {
            operacoes.put(par.group(1), par.group(2));
        }
        return operacoes;
    }

    private Set<String> tiposDeclaradosNaPwa() throws IOException {
        String fonte = Files.readString(ENVELOPE_SOURCE);
        Matcher declaracao = DECLARACAO.matcher(fonte);
        assertThat(declaracao.find())
                .as("SYNC_ENTITY_TYPES declarado em mutationEnvelope.ts")
                .isTrue();

        Set<String> tipos = new LinkedHashSet<>();
        Matcher tipo = TIPO.matcher(declaracao.group(1));
        while (tipo.find()) {
            tipos.add(tipo.group(1));
        }
        return tipos;
    }
}
