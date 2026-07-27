package com.projeto.cortex.intelligence.stavia.interpret;

import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.llm.OllamaChatClient;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.intelligence.stavia.semantic.rdo.RdoOntology;
import com.projeto.cortex.intelligence.stavia.semantic.rdo.RdoOntologyEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class StaviaInterpretationPromptBuilder {

    private static final String TEMPLATE = """
            Você classifica perguntas operacionais de obras e extrai entidades.
            Responda EXCLUSIVAMENTE com um JSON único, sem texto fora dele, no formato:
            {"intent":"<INTENT>","entities":[{"type":"<TIPO>","value":"<texto>"}],
             "attributes":["..."],"operation":"<OPERACAO>","identity":"<texto>",
             "aggregation":{"function":"<FN>","attribute":"...","groupBy":"rdo"},
             "confidence":<0..1>}

            INTENT deve ser um destes valores exatos:
            %s

            TIPO de entidade deve ser um destes: COLABORADOR, ROLE, EQUIPAMENTO, RDO, OBRA.
            - COLABORADOR: nome de pessoa citado.
            - ROLE: cargo/função (ex.: apontador, encarregado, engenheiro).
            - EQUIPAMENTO: máquina/veículo/prefixo.
            Não invente entidades que não aparecem na pergunta. Se não houver, use [].

            Exemplos:
            Pergunta: "Tem apontador?"
            {"intent":"CONSULTAR_EQUIPE","entities":[{"type":"ROLE","value":"apontador"}],"attributes":[],"confidence":0.9}
            Pergunta: "Quem é o apontador da obra?"
            {"intent":"CONSULTAR_EQUIPE","entities":[{"type":"ROLE","value":"apontador"}],"attributes":[],"confidence":0.95}
            Pergunta: "Onde o Abner trabalhou?"
            {"intent":"CONSULTAR_ALOCACAO_COLABORADOR","entities":[{"type":"COLABORADOR","value":"Abner"}],"attributes":[],"confidence":0.9}
            Pergunta: "Qual a condição de clima mais recente?"
            {"intent":"CONSULTAR_RDO","entities":[],"attributes":["condicaoManha","condicaoTarde","condicaoNoite"],"confidence":0.85}
            Pergunta: "Qual a quantidade prevista de CAP 30/45?"
            {"intent":"CONSULTAR_RDO","entities":[],"attributes":["material.quantidadePrevista"],"operation":"READ","identity":"cap 30/45","confidence":0.9}
            """;

    private final String system;

    public StaviaInterpretationPromptBuilder() {
        this(RdoOntology.load());
    }

    @Autowired
    public StaviaInterpretationPromptBuilder(RdoOntology ontology) {
        this.system = TEMPLATE.formatted(intentList())
                + ontologyVocabulary(ontology);
    }

    public List<OllamaChatClient.ChatMessage> build(StaviaQuestion question) {
        return List.of(
                new OllamaChatClient.ChatMessage("system", system),
                new OllamaChatClient.ChatMessage("user",
                        "Pergunta: \"" + question.text() + "\""));
    }

    private static String ontologyVocabulary(RdoOntology ontology) {
        StringBuilder text = new StringBuilder(
                "\nVocabulário de registros do RDO "
                        + "(use \"entidade.atributo\" em attributes):\n"
        );

        for (RdoOntologyEntity entity : ontology.entities()) {
            text.append("- ")
                    .append(entity.name())
                    .append(" (")
                    .append(entity.table())
                    .append("): ")
                    .append(
                            entity.attributes().stream()
                                    .map(attribute ->
                                            entity.name()
                                                    + "."
                                                    + attribute.name()
                                    )
                                    .collect(Collectors.joining(", "))
                    );

            if (!entity.aliases().isEmpty()) {
                text.append(" — aliases: ")
                        .append(String.join(", ", entity.aliases()));
            }

            text.append("\n");
        }

        text.append("""
                "operation" deve ser READ, LIST, AGGREGATE ou COMPARE.
                "aggregation" (opcional): {"function":"SUM|COUNT|AVG|MAX|MIN","attribute":"entidade.atributo","groupBy":"rdo" ou null}.
                "identity" (opcional): termo que identifica o item citado (ex.: "cap 30/45").
                """);

        return text.toString();
    }

    private static String intentList() {
        return Arrays.stream(StaviaIntent.values())
                .map(Enum::name)
                .collect(Collectors.joining(", "));
    }
}
