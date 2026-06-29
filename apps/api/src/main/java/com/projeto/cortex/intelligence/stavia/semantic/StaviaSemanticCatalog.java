package com.projeto.cortex.intelligence.stavia.semantic;

import com.projeto.cortex.intelligence.stavia.planning.QueryDomain;
import com.projeto.cortex.intelligence.stavia.text.StaviaText;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class StaviaSemanticCatalog {

    private final Map<QueryDomain, List<SemanticAttribute>> attributesByDomain;

    public StaviaSemanticCatalog() {
        attributesByDomain = buildAttributes();
    }

    public List<SemanticAttribute> matchAttributes(
            QueryDomain domain,
            String question
    ) {
        String normalized =
                StaviaText.normalize(question);

        if (normalized.isBlank()) {
            return List.of();
        }

        List<SemanticAttribute> attributes =
                attributesByDomain.getOrDefault(domain, List.of());

        List<SemanticAttribute> matched =
                new ArrayList<>();

        for (SemanticAttribute attribute : attributes) {
            if (
                    attribute.aliases()
                            .stream()
                            .map(StaviaText::normalize)
                            .anyMatch(alias ->
                                    containsAlias(normalized, alias)
                            )
            ) {
                matched.add(attribute);
            }
        }

        return matched.stream()
                .filter(SemanticAttribute::visible)
                .distinct()
                .toList();
    }

    public List<SemanticAttribute> attributesFor(
            QueryDomain domain,
            List<String> names
    ) {
        Set<String> requested =
                names == null
                        ? Set.of()
                        : names.stream()
                                .map(value -> value.toLowerCase(Locale.ROOT))
                                .collect(java.util.stream.Collectors.toSet());

        return attributesByDomain
                .getOrDefault(domain, List.of())
                .stream()
                .filter(attribute ->
                        requested.contains(
                                attribute.name()
                                        .toLowerCase(Locale.ROOT)
                        )
                )
                .toList();
    }

    public List<String> aliasesFor(
            QueryDomain domain,
            String attributeName
    ) {
        return attributesByDomain
                .getOrDefault(domain, List.of())
                .stream()
                .filter(attribute ->
                        attribute.name().equals(attributeName)
                )
                .findFirst()
                .map(SemanticAttribute::aliases)
                .orElseGet(List::of);
    }

    private boolean containsAlias(String normalized, String alias) {
        if (alias.isBlank()) {
            return false;
        }

        if (alias.contains(" ")) {
            return normalized.contains(alias);
        }

        return StaviaText.containsWord(normalized, alias);
    }

    private Map<QueryDomain, List<SemanticAttribute>> buildAttributes() {
        Map<QueryDomain, List<SemanticAttribute>> catalog =
                new LinkedHashMap<>();

        catalog.put(
                QueryDomain.OBRA,
                List.of(
                        attribute(
                                "OBRA",
                                "cidade",
                                List.of(
                                        "cidade",
                                        "municipio",
                                        "município",
                                        "cidade da obra"
                                ),
                                "TEXT",
                                null,
                                "cadastro-de-obras",
                                false
                        ),
                        attribute(
                                "OBRA",
                                "uf",
                                List.of(
                                        "uf",
                                        "estado",
                                        "estado da obra"
                                ),
                                "TEXT",
                                null,
                                "cadastro-de-obras",
                                false
                        ),
                        attribute(
                                "OBRA",
                                "rodovia",
                                List.of(
                                        "rodovia",
                                        "estrada",
                                        "localizacao",
                                        "localização",
                                        "onde fica"
                                ),
                                "TEXT",
                                null,
                                "cadastro-de-obras",
                                false
                        ),
                        attribute(
                                "OBRA",
                                "cliente",
                                List.of(
                                        "cliente",
                                        "contratante"
                                ),
                                "TEXT",
                                null,
                                "cadastro-de-obras",
                                false
                        ),
                        attribute(
                                "OBRA",
                                "nome",
                                List.of(
                                        "nome",
                                        "nome da obra",
                                        "qual obra",
                                        "que obra"
                                ),
                                "TEXT",
                                null,
                                "cadastro-de-obras",
                                false
                        ),
                        attribute(
                                "OBRA",
                                "status",
                                List.of(
                                        "status",
                                        "situacao",
                                        "situação",
                                        "como esta",
                                        "como está"
                                ),
                                "TEXT",
                                null,
                                "cadastro-de-obras",
                                false
                        ),
                        attribute(
                                "OBRA",
                                "codigo",
                                List.of(
                                        "codigo",
                                        "código",
                                        "codigo cw",
                                        "código cw",
                                        "contrato",
                                        "codigo da obra",
                                        "código da obra"
                                ),
                                "TEXT",
                                null,
                                "cadastro-de-obras",
                                false
                        )
                )
        );

        catalog.put(
                QueryDomain.RDO,
                List.of(
                        attribute(
                                "RDO",
                                "turno",
                                List.of(
                                        "turno",
                                        "turno da obra",
                                        "turno do rdo",
                                        "diurno",
                                        "noturno"
                                ),
                                "ENUM",
                                null,
                                "cadastro-rdos",
                                false
                        ),
                        attribute(
                                "RDO",
                                "condicaoManha",
                                List.of(
                                        "clima",
                                        "tempo",
                                        "condicao de clima",
                                        "condicao climatica",
                                        "clima da manha",
                                        "tempo de manha",
                                        "condicao climatica da manha",
                                        "manha"
                                ),
                                "ENUM",
                                null,
                                "cadastro-rdos",
                                false
                        ),
                        attribute(
                                "RDO",
                                "condicaoTarde",
                                List.of(
                                        "clima",
                                        "tempo",
                                        "condicao de clima",
                                        "condicao climatica",
                                        "clima da tarde",
                                        "tempo da tarde",
                                        "condicao climatica da tarde",
                                        "tarde"
                                ),
                                "ENUM",
                                null,
                                "cadastro-rdos",
                                false
                        ),
                        attribute(
                                "RDO",
                                "condicaoNoite",
                                List.of(
                                        "clima",
                                        "tempo",
                                        "condicao de clima",
                                        "condicao climatica",
                                        "clima da noite",
                                        "tempo da noite",
                                        "condicao climatica da noite",
                                        "noite"
                                ),
                                "ENUM",
                                null,
                                "cadastro-rdos",
                                false
                        ),
                        attribute(
                                "RDO",
                                "pluviometriaMm",
                                List.of(
                                        "chuva",
                                        "choveu",
                                        "pluviometria",
                                        "milimetros de chuva",
                                        "mm de chuva",
                                        "quanto choveu"
                                ),
                                "DECIMAL",
                                "mm",
                                "cadastro-rdos",
                                false
                        ),
                        attribute(
                                "RDO",
                                "numeroRdo",
                                List.of("rdo", "relatorio diario", "ultimo rdo"),
                                "TEXT",
                                null,
                                "cadastro-rdos",
                                false
                        )
                )
        );

        catalog.put(
                QueryDomain.COLABORADOR,
                List.of(
                        attribute(
                                "COLABORADOR",
                                "nome",
                                List.of("colaborador", "funcionario", "nome"),
                                "TEXT",
                                null,
                                "cadastro-colaboradores",
                                false
                        ),
                        attribute(
                                "COLABORADOR",
                                "equipe",
                                List.of("equipe", "time", "turma"),
                                "TEXT",
                                null,
                                "alocacoes-colaboradores",
                                false
                        ),
                        attribute(
                                "COLABORADOR",
                                "horas",
                                List.of("horas", "banco de horas", "frequencia", "falta"),
                                "DECIMAL",
                                "h",
                                "frequencia-banco-horas",
                                false
                        )
                )
        );

        catalog.put(
                QueryDomain.EQUIPAMENTO,
                List.of(
                        attribute(
                                "EQUIPAMENTO",
                                "descricao",
                                List.of(
                                        "equipamento",
                                        "maquina",
                                        "maquinario",
                                        "frota",
                                        "patrimonio",
                                        "placa"
                                ),
                                "TEXT",
                                null,
                                "equipamentos-dos-rdos",
                                false
                        )
                )
        );

        catalog.put(
                QueryDomain.FINANCEIRO,
                List.of(
                        attribute("SNAPSHOT_FINANCEIRO", "receita", List.of("receita"), "DECIMAL", "BRL", "previsao-financeira", false),
                        attribute("SNAPSHOT_FINANCEIRO", "margem", List.of("margem"), "DECIMAL", "BRL", "previsao-financeira", false),
                        attribute("EXECUCAO_SERVICO", "producao", List.of("producao", "servico", "execucao"), "DECIMAL", null, "previsao-financeira", false)
                )
        );

        return Map.copyOf(catalog);
    }

    private SemanticAttribute attribute(
            String objectType,
            String name,
            List<String> aliases,
            String dataType,
            String unit,
            String source,
            boolean sensitive
    ) {
        return new SemanticAttribute(
                objectType,
                name,
                aliases,
                dataType,
                unit,
                source,
                true,
                sensitive,
                "STAVIA_CONSULTAR"
        );
    }
}
