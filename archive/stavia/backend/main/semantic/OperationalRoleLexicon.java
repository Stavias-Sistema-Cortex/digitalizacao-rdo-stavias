package com.projeto.cortex.intelligence.stavia.semantic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.projeto.cortex.intelligence.stavia.text.StaviaText;

/**
 * Explicit vocabulary for operational workforce roles.
 *
 * <p>The intent classifier decides whether a question is about the team. This
 * vocabulary then resolves a role filter that can be applied safely to the
 * Córtex records. New business aliases belong here rather than in scattered
 * keyword checks.
 */
@Component
public class OperationalRoleLexicon {

    private static final Map<String, List<String>> ROLE_ALIASES =
            roleAliases();

    private static final List<String> TEAM_SIGNALS = List.of(
            "equipe",
            "colaborador",
            "colaboradores",
            "funcionario",
            "funcionarios",
            "profissional",
            "profissionais",
            "pessoa",
            "pessoas",
            "trabalhador",
            "trabalhadores",
            "mao de obra"
    );

    /**
     * Returns canonical role labels mentioned in a natural-language question.
     * A generic term such as "colaboradores" intentionally returns no role:
     * it means the whole team, not an invented subset of it.
     */
    public List<String> rolesMentioned(String question) {
        String normalized = StaviaText.normalize(question);

        if (normalized.isBlank()) {
            return List.of();
        }

        return ROLE_ALIASES.entrySet()
                .stream()
                .filter(entry -> entry.getValue()
                        .stream()
                        .anyMatch(alias -> contains(normalized, alias)))
                .map(Map.Entry::getKey)
                .toList();
    }

    public boolean mentionsTeam(String question) {
        String normalized = StaviaText.normalize(question);

        return !normalized.isBlank()
                && (TEAM_SIGNALS.stream()
                        .anyMatch(signal -> contains(normalized, signal))
                        || !rolesMentioned(normalized).isEmpty());
    }

    private static Map<String, List<String>> roleAliases() {
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        aliases.put(
                "operador",
                List.of(
                        "operador",
                        "operadores",
                        "operador de maquina",
                        "operador de maquinas",
                        "maquinista",
                        "maquinistas"
                )
        );
        aliases.put(
                "apontador",
                List.of("apontador", "apontadores")
        );
        aliases.put(
                "encarregado",
                List.of("encarregado", "encarregados")
        );
        aliases.put(
                "motorista",
                List.of("motorista", "motoristas")
        );
        aliases.put(
                "servente",
                List.of("servente", "serventes")
        );
        aliases.put(
                "pedreiro",
                List.of("pedreiro", "pedreiros")
        );
        return Map.copyOf(aliases);
    }

    private boolean contains(String normalized, String alias) {
        return alias.contains(" ")
                ? normalized.contains(alias)
                : StaviaText.containsWord(normalized, alias);
    }
}
