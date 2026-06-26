package com.projeto.cortex.intelligence.stavia.interpret;

import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.llm.OllamaChatClient;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class StaviaInterpretationPromptBuilder {

    private static final String SYSTEM = """
            Você classifica perguntas operacionais de obras e extrai entidades.
            Responda EXCLUSIVAMENTE com um JSON único, sem texto fora dele, no formato:
            {"intent":"<INTENT>","entities":[{"type":"<TIPO>","value":"<texto>"}],
             "attributes":["..."],"confidence":<0..1>}

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
            """.formatted(intentList());

    public List<OllamaChatClient.ChatMessage> build(StaviaQuestion question) {
        return List.of(
                new OllamaChatClient.ChatMessage("system", SYSTEM),
                new OllamaChatClient.ChatMessage("user",
                        "Pergunta: \"" + question.text() + "\""));
    }

    private static String intentList() {
        return Arrays.stream(StaviaIntent.values())
                .map(Enum::name)
                .collect(Collectors.joining(", "));
    }
}
