package com.projeto.cortex.intelligence.stavia.planning;

import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.intelligence.stavia.semantic.rdo.RdoOntology;
import com.projeto.cortex.intelligence.stavia.semantic.rdo.RdoOntologyAttribute;
import com.projeto.cortex.intelligence.stavia.semantic.rdo.RdoOntologyEntity;
import com.projeto.cortex.intelligence.stavia.text.StaviaText;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Plans RDO record questions directly from the declarative ontology: alias
 * matching decides entity/attributes, a small generic lexicon decides the
 * operation and the leftover tokens become the identity filter (for example
 * "cap 30/45"). Header facts stay with the semantic catalog flow; here the
 * header entity only participates in aggregations and rankings.
 */
public class RdoOntologyPlanner {

    private static final Set<String> LIST_WORDS =
            Set.of("quais", "lista", "listar", "liste",
                    "registrados", "usados", "utilizados",
                    "trabalhou", "trabalharam");

    private static final Set<String> AGGREGATE_WORDS =
            Set.of("quanto", "quanta", "quantos", "quantas",
                    "total", "soma", "somando", "media");

    private static final List<String> RANKING_MAX_PHRASES =
            List.of("qual rdo teve mais", "qual dia teve mais");

    private static final List<String> RANKING_MIN_PHRASES =
            List.of("qual rdo teve menos", "qual dia teve menos");

    private static final List<String> COMPARE_MARKERS =
            List.of(" vs ", "versus", "comparar", "comparacao",
                    "diferenca entre");

    private static final Set<String> IDENTITY_STOPWORDS =
            Set.of("qual", "quais", "quanto", "quanta", "quantos",
                    "quantas", "quando", "quem", "onde", "como",
                    "foi", "foram", "e", "o", "a", "os", "as",
                    "de", "da", "do", "das", "dos", "no", "na",
                    "nos", "nas", "em", "um", "uma", "este",
                    "esta", "esse", "essa", "neste", "nesta",
                    "nesse", "nessa", "ultimo", "ultima", "rdo",
                    "obra", "hoje", "ontem", "semana", "passada",
                    "mais", "menos", "recente", "total", "soma",
                    "media", "teve", "tem", "houve", "registrado",
                    "registrados", "registrada", "registradas",
                    "lista", "listar", "liste", "usados",
                    "utilizados", "trabalhou", "trabalharam",
                    "comparar", "comparacao", "vs", "versus",
                    "diferenca", "entre", "tonelada", "toneladas",
                    "t", "kg", "quilos", "litro", "litros",
                    "metro", "metros", "mm", "cm", "m2", "m3",
                    "executado", "executada", "executados",
                    "executadas");

    private final RdoOntology ontology;
    private final TemporalFilterParser temporalParser;

    public RdoOntologyPlanner(
            RdoOntology ontology,
            TemporalFilterParser temporalParser
    ) {
        this.ontology = ontology;
        this.temporalParser = temporalParser;
    }

    private record AttributeMatch(
            RdoOntologyEntity entity,
            RdoOntologyAttribute attribute,
            String alias
    ) {
    }

    public StaviaQueryPlan plan(
            StaviaQuestion question,
            String normalized
    ) {
        if (normalized == null || normalized.isBlank()) {
            return StaviaQueryPlan.empty();
        }

        List<AttributeMatch> matches =
                attributeMatches(normalized);
        RdoOntologyEntity entity =
                targetEntity(matches, normalized);

        if (entity == null) {
            return StaviaQueryPlan.empty();
        }

        List<AttributeMatch> entityMatches = matches.stream()
                .filter(match ->
                        match.entity().name().equals(entity.name())
                )
                .sorted(
                        Comparator.comparingInt(
                                (AttributeMatch match) ->
                                        match.alias().length()
                        ).reversed()
                )
                .toList();

        AttributeMatch best = entityMatches.isEmpty()
                ? null
                : entityMatches.getFirst();

        boolean rankingMax =
                containsAnyPhrase(normalized, RANKING_MAX_PHRASES);
        boolean rankingMin =
                containsAnyPhrase(normalized, RANKING_MIN_PHRASES);
        boolean compareMarker =
                containsAnyPhrase(normalized, COMPARE_MARKERS);
        boolean aggregateWord =
                containsAnyWord(normalized, AGGREGATE_WORDS);
        boolean listWord =
                containsAnyWord(normalized, LIST_WORDS);

        QueryOperation operation;
        List<AggregationSpec> aggregations = List.of();
        List<String> requestedAttributes;

        List<AttributeMatch> aggregable = entityMatches.stream()
                .filter(match -> match.attribute().aggregable())
                .toList();

        if (best != null
                && best.attribute().aggregable()
                && (rankingMax || rankingMin)) {
            operation = QueryOperation.COMPARE;
            String qualified = qualified(entity, best.attribute());
            aggregations = List.of(
                    new AggregationSpec(
                            rankingMax ? "MAX" : "MIN",
                            qualified,
                            "rdo"
                    )
            );
            requestedAttributes = List.of(qualified);
        } else if (compareMarker
                && distinctQualified(entity, aggregable).size() >= 2) {
            operation = QueryOperation.COMPARE;
            requestedAttributes =
                    distinctQualified(entity, aggregable);
        } else if (best != null
                && best.attribute().aggregable()
                && aggregateWord) {
            operation = QueryOperation.AGGREGATE;
            String qualified = qualified(entity, best.attribute());
            aggregations = List.of(
                    new AggregationSpec(
                            aggregateFunction(
                                    normalized,
                                    entity,
                                    best.attribute()
                            ),
                            qualified,
                            null
                    )
            );
            requestedAttributes = List.of(qualified);
        } else if (best != null) {
            operation = QueryOperation.READ_ATTRIBUTE;
            requestedAttributes =
                    distinctQualified(entity, entityMatches);
        } else if (listWord) {
            operation = QueryOperation.LIST_OBJECTS;
            requestedAttributes = listingAttributes(entity);
        } else {
            return StaviaQueryPlan.empty();
        }

        if (entity.header()
                && operation != QueryOperation.AGGREGATE
                && operation != QueryOperation.COMPARE) {
            return StaviaQueryPlan.empty();
        }

        boolean latestFallback =
                operation == QueryOperation.READ_ATTRIBUTE
                        || operation == QueryOperation.LIST_OBJECTS;
        TemporalFilter temporal =
                temporalParser.parse(normalized, latestFallback);

        List<ResolvedEntity> entities = new ArrayList<>();
        if (question.obraId() != null
                && !question.obraId().isBlank()) {
            entities.add(
                    ResolvedEntity.worksiteById(question.obraId())
            );
        }

        String identity =
                identityTerm(normalized, entity, entityMatches);
        if (identity != null) {
            entities.add(
                    new ResolvedEntity(
                            entity.name().toUpperCase(Locale.ROOT),
                            null,
                            "NOME",
                            identity,
                            false,
                            List.of()
                    )
            );
        }

        return new StaviaQueryPlan(
                QueryDomain.RDO,
                operation,
                entities,
                temporal,
                requestedAttributes,
                List.of(),
                aggregations,
                List.of(entity.source()),
                temporal.latestCriterion() != null,
                temporal.startDate() != null,
                operation == QueryOperation.COMPARE
        );
    }

    private List<AttributeMatch> attributeMatches(String normalized) {
        List<AttributeMatch> matches = new ArrayList<>();

        for (RdoOntologyEntity entity : ontology.entities()) {
            for (RdoOntologyAttribute attribute : entity.attributes()) {
                for (String alias : attribute.aliases()) {
                    if (matchesAlias(normalized, alias)) {
                        matches.add(
                                new AttributeMatch(
                                        entity,
                                        attribute,
                                        StaviaText.normalize(alias)
                                )
                        );
                    }
                }
            }
        }

        return matches;
    }

    private RdoOntologyEntity targetEntity(
            List<AttributeMatch> matches,
            String normalized
    ) {
        AttributeMatch best = matches.stream()
                .max(
                        Comparator.comparingInt(
                                match -> match.alias().length()
                        )
                )
                .orElse(null);

        if (best != null) {
            return best.entity();
        }

        RdoOntologyEntity byAlias = null;
        int bestLength = 0;

        for (RdoOntologyEntity entity : ontology.entities()) {
            for (String alias : entity.aliases()) {
                String normalizedAlias = StaviaText.normalize(alias);
                if (matchesAlias(normalized, alias)
                        && normalizedAlias.length() > bestLength) {
                    byAlias = entity;
                    bestLength = normalizedAlias.length();
                }
            }
        }

        return byAlias != null && !byAlias.header() ? byAlias : null;
    }

    private boolean matchesAlias(String normalized, String alias) {
        String clean = StaviaText.normalize(alias);

        if (clean.isBlank()) {
            return false;
        }

        if (clean.contains(" ")) {
            return normalized.contains(clean);
        }

        return StaviaText.containsWord(normalized, clean);
    }

    private boolean containsAnyPhrase(
            String normalized,
            List<String> phrases
    ) {
        return phrases.stream().anyMatch(normalized::contains);
    }

    private boolean containsAnyWord(
            String normalized,
            Set<String> words
    ) {
        return words.stream()
                .anyMatch(word ->
                        StaviaText.containsWord(normalized, word)
                );
    }

    private List<String> distinctQualified(
            RdoOntologyEntity entity,
            List<AttributeMatch> matches
    ) {
        return matches.stream()
                .map(match -> qualified(entity, match.attribute()))
                .distinct()
                .toList();
    }

    private String qualified(
            RdoOntologyEntity entity,
            RdoOntologyAttribute attribute
    ) {
        return entity.name() + "." + attribute.name();
    }

    private String aggregateFunction(
            String normalized,
            RdoOntologyEntity entity,
            RdoOntologyAttribute attribute
    ) {
        if (StaviaText.containsWord(normalized, "media")) {
            return "AVG";
        }

        boolean countQuestion =
                StaviaText.containsWord(normalized, "quantos")
                        || StaviaText.containsWord(normalized, "quantas");

        if (countQuestion
                && attribute.name().equals(entity.countAttribute())) {
            return "COUNT";
        }

        return "SUM";
    }

    private List<String> listingAttributes(RdoOntologyEntity entity) {
        List<String> names = new ArrayList<>();

        entity.identityAttributes().forEach(attribute ->
                names.add(qualified(entity, attribute))
        );

        entity.attributes().stream()
                .filter(RdoOntologyAttribute::aggregable)
                .forEach(attribute ->
                        names.add(qualified(entity, attribute))
                );

        return names.stream().distinct().toList();
    }

    private String identityTerm(
            String normalized,
            RdoOntologyEntity entity,
            List<AttributeMatch> matches
    ) {
        Set<String> removable = new LinkedHashSet<>();

        for (AttributeMatch match : matches) {
            removable.addAll(List.of(match.alias().split("\\s+")));
        }

        for (String alias : entity.aliases()) {
            if (matchesAlias(normalized, alias)) {
                removable.addAll(
                        List.of(
                                StaviaText.normalize(alias)
                                        .split("\\s+")
                        )
                );
            }
        }

        List<String> kept = new ArrayList<>();

        for (String rawToken : normalized.split("\\s+")) {
            String token = rawToken.replaceAll(
                    "^[^\\p{L}\\p{Nd}]+|[^\\p{L}\\p{Nd}]+$",
                    ""
            );

            if (token.isBlank()
                    || IDENTITY_STOPWORDS.contains(token)
                    || removable.contains(token)
                    || token.matches(
                            "\\d{2}/\\d{2}/\\d{4}|\\d{4}-\\d{2}-\\d{2}"
                    )) {
                continue;
            }

            kept.add(token);
        }

        String identity =
                String.join(" ", kept).trim();

        if (identity.isBlank()) {
            return null;
        }

        for (String alias : entity.aliases()) {
            if (StaviaText.normalize(alias).equals(identity)) {
                return null;
            }
        }

        return identity;
    }
}
