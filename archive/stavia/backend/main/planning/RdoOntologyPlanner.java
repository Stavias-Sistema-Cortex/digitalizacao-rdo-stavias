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
import java.util.regex.Pattern;

/**
 * Plans RDO record questions directly from the declarative ontology: alias
 * matching decides entity/attributes, a small generic lexicon decides the
 * operation and the leftover tokens become the identity filter (for example
 * "cap 30/45"). Atomic header facts stay with the semantic catalog flow; a
 * full-record request such as "dados do RDO" can still use this ontology path
 * to read all declared header cells consistently with child-table records.
 */
public class RdoOntologyPlanner {

    private static final String RDO_RECORD_SOURCE =
            "registros-rdo";

    private static final Set<String> LIST_WORDS =
            Set.of("quais", "dados", "campos", "detalhes",
                    "detalhe", "informacoes", "informações",
                    "mostre", "mostrar", "lista", "listar", "liste",
                    "registrados", "usados", "utilizados",
                    "trabalhou", "trabalharam");

    private static final Set<String> FULL_RECORD_WORDS =
            Set.of("dados", "campos", "detalhes", "detalhe",
                    "informacoes", "informações", "tudo", "todo",
                    "toda", "todos", "todas", "completo", "completa");

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
                    "lista", "listar", "liste", "dados", "campos",
                    "detalhes", "detalhe", "informacoes", "informações",
                    "mostre", "mostrar", "tudo", "todo", "toda",
                    "todos", "todas", "completo", "completa", "usados",
                    "utilizados", "trabalhou", "trabalharam",
                    "comparar", "comparacao", "vs", "versus",
                    "diferenca", "entre", "tonelada", "toneladas",
                    "t", "kg", "quilos", "litro", "litros",
                    "metro", "metros", "mm", "cm", "m2", "m3",
                    "executado", "executada", "executados",
                    "executadas");

    private static final Pattern SELECTED_RDO_CONTEXT =
            Pattern.compile("(?iu)\\brdoId\\s*=");
    private static final Pattern RDO_CODE_TOKEN =
            Pattern.compile("(?iu)^rdo[-_].+");

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

        boolean selectedRdoContext =
                SELECTED_RDO_CONTEXT.matcher(question.text()).find();
        List<AttributeMatch> matches =
                attributeMatches(normalized);
        boolean listWord =
                containsAnyWord(normalized, LIST_WORDS);
        RdoOntologyEntity entity =
                targetEntity(matches, normalized, listWord);

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
        boolean fullRecord =
                wantsFullRecord(normalized);
        Integer ordinal = rowOrdinal(normalized, entity);
        boolean ordinalRecord = ordinal != null;

        QueryOperation operation;
        List<AggregationSpec> aggregations = List.of();
        List<String> requestedAttributes;

        List<AttributeMatch> aggregable = entityMatches.stream()
                .filter(match -> match.attribute().aggregable())
                .toList();

        if (listWord && fullRecord) {
            operation = QueryOperation.LIST_OBJECTS;
            requestedAttributes = listingAttributes(entity, normalized);
        } else if (!ordinalRecord
                && best != null
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
        } else if (!ordinalRecord
                && best != null
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
                    distinctQualified(
                            entity,
                            preferRequestedMatches(
                                    entity,
                                    entityMatches
                            )
                    );
        } else if (listWord) {
            operation = QueryOperation.LIST_OBJECTS;
            requestedAttributes = listingAttributes(entity, normalized);
        } else {
            return StaviaQueryPlan.empty();
        }

        if (entity.header()
                && operation != QueryOperation.AGGREGATE
                && operation != QueryOperation.COMPARE
                && !(operation == QueryOperation.LIST_OBJECTS
                        && fullRecord)
                && !selectedRdoContext) {
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

        if (ordinal != null) {
            entities.add(
                    new ResolvedEntity(
                            entity.name().toUpperCase(Locale.ROOT),
                            null,
                            "ORDINAL",
                            String.valueOf(ordinal),
                            false,
                            List.of()
                    )
            );
        }

        String identity = ordinal == null
                ? identityTerm(
                        normalized,
                        entity,
                        entityMatches,
                        selectedRdoContext
                )
                : null;
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
                List.of(sourceFor(entity, operation, fullRecord)),
                temporal.latestCriterion() != null,
                temporal.startDate() != null,
                operation == QueryOperation.COMPARE
        );
    }

    /**
     * Same behaviour the hardcoded {@code rdoDatePlan} used to provide in
     * the main planner: a date question about the (selected) RDO reads
     * {@code dataRdo} from the existing header source. Called by the main
     * planner at the original early position, before the document/segment
     * plans that could otherwise capture the selected-RDO follow-up text.
     */
    public StaviaQueryPlan rdoDatePlan(
            StaviaQuestion question,
            String normalized
    ) {
        boolean asksDate =
                StaviaText.containsWord(normalized, "data")
                        || StaviaText.containsWord(normalized, "dia")
                        || StaviaText.containsWord(normalized, "quando");

        if (!asksDate
                || asksForWorksiteDate(normalized)
                || asksWeekday(normalized)
                || asksDifferentRdoDateAttribute(normalized)) {
            return StaviaQueryPlan.empty();
        }

        boolean selectedRdoContext =
                SELECTED_RDO_CONTEXT.matcher(question.text()).find();
        boolean explicitRdoDate =
                containsAnyPhrase(
                        normalized,
                        List.of(
                                "data do rdo",
                                "data rdo",
                                "dia do rdo",
                                "quando foi o rdo",
                                "quando esse rdo",
                                "quando este rdo",
                                "quando foi esse relatorio diario",
                                "quando foi este relatorio diario"
                        )
                )
                        || (
                                mentionsRdo(normalized)
                                        && !StaviaText.containsWord(
                                                normalized,
                                                "obra"
                                        )
                        );

        if (!selectedRdoContext && !explicitRdoDate) {
            return StaviaQueryPlan.empty();
        }

        List<ResolvedEntity> entities = new ArrayList<>();

        if (question.obraId() != null
                && !question.obraId().isBlank()) {
            entities.add(
                    ResolvedEntity.worksiteById(question.obraId())
            );
        }

        return new StaviaQueryPlan(
                QueryDomain.RDO,
                QueryOperation.READ_ATTRIBUTE,
                entities,
                TemporalFilter.latest("RDO_STATUS_E_DATA_OPERACIONAL"),
                List.of("rdo.dataRdo"),
                List.of(),
                List.of(),
                List.of(RDO_RECORD_SOURCE),
                true,
                false,
                false
        );
    }

    private boolean asksWeekday(String normalized) {
        return containsAnyPhrase(
                normalized,
                List.of(
                        "dia da semana",
                        "dia semanal"
                )
        );
    }

    private boolean asksDifferentRdoDateAttribute(String normalized) {
        return attributeMatches(normalized).stream()
                .anyMatch(match ->
                        "rdo".equals(match.entity().name())
                                && !"dataRdo".equals(
                                        match.attribute().name()
                                )
                );
    }

    private boolean asksForWorksiteDate(String normalized) {
        return containsAnyPhrase(
                normalized,
                List.of(
                        "data da obra",
                        "data desta obra",
                        "data dessa obra",
                        "quando esta obra",
                        "quando essa obra",
                        "obra comec",
                        "obra iniciou",
                        "inicio da obra",
                        "fim da obra",
                        "termino da obra"
                )
        );
    }

    private boolean mentionsRdo(String normalized) {
        return StaviaText.containsWord(normalized, "rdo")
                || containsAnyPhrase(
                        normalized,
                        List.of(
                                "relatorio diario",
                                "relatorios diarios"
                        )
                );
    }

    private List<AttributeMatch> attributeMatches(String normalized) {
        List<AttributeMatch> matches = new ArrayList<>();

        for (RdoOntologyEntity entity : ontology.entities()) {
            if (!entity.rdoScoped()) {
                continue;
            }
            for (RdoOntologyAttribute attribute : entity.attributes()) {
                List<String> aliases = new ArrayList<>();
                aliases.add(attribute.label());
                aliases.addAll(attribute.aliases());

                for (String alias : aliases) {
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
            String normalized,
            boolean allowHeaderAlias
    ) {
        if (allowHeaderAlias && wantsFullRecord(normalized)) {
            RdoOntologyEntity explicitEntity =
                    entityByAlias(normalized, allowHeaderAlias);

            if (explicitEntity != null) {
                return explicitEntity;
            }
        }

        AttributeMatch best = matches.stream()
                .max(
                        Comparator.comparingInt(
                                match -> match.alias().length()
                                        + entityContextScore(
                                                match.entity(),
                                                normalized
                                        )
                        )
                )
                .orElse(null);

        if (best != null) {
            return best.entity();
        }

        return entityByAlias(normalized, allowHeaderAlias);
    }

    private RdoOntologyEntity entityByAlias(
            String normalized,
            boolean allowHeaderAlias
    ) {
        RdoOntologyEntity byAlias = null;
        int bestLength = 0;

        for (RdoOntologyEntity entity : ontology.entities()) {
            if (!entity.rdoScoped()) {
                continue;
            }
            for (String alias : entity.aliases()) {
                String normalizedAlias = StaviaText.normalize(alias);
                if (matchesAlias(normalized, alias)
                        && normalizedAlias.length() > bestLength) {
                    byAlias = entity;
                    bestLength = normalizedAlias.length();
                }
            }
        }

        return byAlias != null
                && (!byAlias.header() || allowHeaderAlias)
                ? byAlias
                : null;
    }

    private int entityContextScore(
            RdoOntologyEntity entity,
            String normalized
    ) {
        int aliasScore = entityAliasScore(entity, normalized);
        int ordinalScore = rowOrdinalContextScore(entity, normalized);

        return Math.max(aliasScore, ordinalScore);
    }

    private int entityAliasScore(
            RdoOntologyEntity entity,
            String normalized
    ) {
        int bestLength = 0;

        for (String alias : entity.aliases()) {
            String normalizedAlias = StaviaText.normalize(alias);

            if (matchesAlias(normalized, alias)
                    && normalizedAlias.length() > bestLength) {
                bestLength = normalizedAlias.length();
            }
        }

        return bestLength == 0 ? 0 : 1_000 + bestLength;
    }

    private int rowOrdinalContextScore(
            RdoOntologyEntity entity,
            String normalized
    ) {
        int termLength = matchingRowOrdinalTermLength(normalized, entity);

        if (termLength == 0) {
            return 0;
        }

        int score = 2_000 + termLength;

        if ("maoObra".equals(entity.name())
                && mentionsLaborOrdinal(normalized)
                && !mentionsAllocationContext(normalized)) {
            score += 500;
        }

        if ("alocacaoColaborador".equals(entity.name())
                && mentionsAllocationContext(normalized)) {
            score += 500;
        }

        return score;
    }

    private int matchingRowOrdinalTermLength(
            String normalized,
            RdoOntologyEntity entity
    ) {
        if (entity.header()) {
            return 0;
        }

        int bestLength = 0;

        for (String term : rowOrdinalTerms(entity)) {
            if (matchesOrdinalTerm(normalized, term)
                    && term.length() > bestLength) {
                bestLength = term.length();
            }
        }

        return bestLength;
    }

    private boolean matchesOrdinalTerm(
            String normalized,
            String term
    ) {
        String escaped = Pattern.quote(term);

        if (Pattern.compile(
                "\\b" + escaped + "\\s+(?:n(?:umero)?\\s*)?(\\d+)\\b",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
        ).matcher(normalized).find()) {
            return true;
        }

        for (String word : List.of(
                "primeiro", "primeira", "segundo", "segunda",
                "terceiro", "terceira", "quarto", "quarta",
                "quinto", "quinta", "sexto", "sexta", "setimo",
                "setima", "oitavo", "oitava", "nono", "nona",
                "decimo", "decima"
        )) {
            if (Pattern.compile(
                    "\\b" + word + "\\s+" + escaped + "\\b",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
            ).matcher(normalized).find()
                    || Pattern.compile(
                            "\\b" + escaped + "\\s+" + word + "\\b",
                            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
                    ).matcher(normalized).find()) {
                return true;
            }
        }

        return false;
    }

    private boolean mentionsLaborOrdinal(String normalized) {
        return containsAnyWord(
                normalized,
                Set.of("colaborador", "funcionario", "trabalhador", "pessoa")
        );
    }

    private boolean mentionsAllocationContext(String normalized) {
        return containsAnyWord(
                normalized,
                Set.of("alocacao", "alocacoes", "apontamento", "apontamentos")
        );
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

    private List<AttributeMatch> preferRequestedMatches(
            RdoOntologyEntity entity,
            List<AttributeMatch> matches
    ) {
        if (matches.size() <= 1) {
            return matches;
        }

        Set<String> contextAliases = rowOrdinalTerms(entity).stream()
                .collect(java.util.stream.Collectors.toSet());

        List<AttributeMatch> nonContextMatches = matches.stream()
                .filter(match ->
                        !(
                                match.attribute().identity()
                                        && contextAliases.contains(
                                                match.alias()
                                        )
                        )
                )
                .toList();

        return nonContextMatches.isEmpty()
                ? matches
                : nonContextMatches;
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

    private List<String> listingAttributes(
            RdoOntologyEntity entity,
            String normalized
    ) {
        if (wantsFullRecord(normalized)) {
            return entity.attributes()
                    .stream()
                    .map(attribute -> qualified(entity, attribute))
                    .toList();
        }

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

    private boolean wantsFullRecord(String normalized) {
        return containsAnyWord(normalized, FULL_RECORD_WORDS);
    }

    private String sourceFor(
            RdoOntologyEntity entity,
            QueryOperation operation,
            boolean fullRecord
    ) {
        if (entity.header()
                && (
                        fullRecord
                                || operation == QueryOperation.READ_ATTRIBUTE
                                || operation == QueryOperation.AGGREGATE
                                || operation == QueryOperation.COMPARE
                )) {
            return RDO_RECORD_SOURCE;
        }

        if ("operationalEvent".equals(entity.name())) {
            return RDO_RECORD_SOURCE;
        }

        return entity.source();
    }

    private String identityTerm(
            String normalized,
            RdoOntologyEntity entity,
            List<AttributeMatch> matches,
            boolean selectedRdoContext
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
                    || isSelectedContextToken(
                            token,
                            selectedRdoContext
                    )
                    || isSelectedRdoNumberToken(
                            token,
                            entity,
                            selectedRdoContext
                    )
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

    private boolean isSelectedContextToken(
            String token,
            boolean selectedRdoContext
    ) {
        return selectedRdoContext
                && (
                        token.equals("contexto")
                                || token.equals("ontologico")
                                || token.equals("selecionado")
                                || token.equals("informado")
                                || token.startsWith("obraid=")
                                || token.startsWith("rdoid=")
                );
    }

    private boolean isSelectedRdoNumberToken(
            String token,
            RdoOntologyEntity entity,
            boolean selectedRdoContext
    ) {
        return selectedRdoContext
                && !entity.header()
                && RDO_CODE_TOKEN.matcher(token).matches();
    }

    private Integer rowOrdinal(
            String normalized,
            RdoOntologyEntity entity
    ) {
        if (entity.header()) {
            return null;
        }

        for (String term : rowOrdinalTerms(entity)) {
            String escaped = Pattern.quote(term);
            java.util.regex.Matcher numeric = Pattern.compile(
                    "\\b" + escaped + "\\s+(?:n(?:umero)?\\s*)?(\\d+)\\b",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
            ).matcher(normalized);

            if (numeric.find()) {
                return Integer.parseInt(numeric.group(1));
            }
        }

        for (String word : List.of(
                "primeiro", "primeira", "segundo", "segunda",
                "terceiro", "terceira", "quarto", "quarta",
                "quinto", "quinta", "sexto", "sexta", "setimo",
                "setima", "oitavo", "oitava", "nono", "nona",
                "decimo", "decima"
        )) {
            int ordinal = ordinalWordValue(word);

            for (String term : rowOrdinalTerms(entity)) {
                String escaped = Pattern.quote(term);

                if (Pattern.compile(
                        "\\b" + word + "\\s+" + escaped + "\\b",
                        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
                ).matcher(normalized).find()
                        || Pattern.compile(
                                "\\b" + escaped + "\\s+" + word + "\\b",
                                Pattern.CASE_INSENSITIVE
                                        | Pattern.UNICODE_CASE
                        ).matcher(normalized).find()) {
                    return ordinal;
                }
            }
        }

        return null;
    }

    private int ordinalWordValue(String word) {
        return switch (word) {
            case "primeiro", "primeira" -> 1;
            case "segundo", "segunda" -> 2;
            case "terceiro", "terceira" -> 3;
            case "quarto", "quarta" -> 4;
            case "quinto", "quinta" -> 5;
            case "sexto", "sexta" -> 6;
            case "setimo", "setima" -> 7;
            case "oitavo", "oitava" -> 8;
            case "nono", "nona" -> 9;
            case "decimo", "decima" -> 10;
            default -> 0;
        };
    }

    private List<String> rowOrdinalTerms(RdoOntologyEntity entity) {
        Set<String> terms = new LinkedHashSet<>();
        terms.add("linha");
        terms.add("item");
        terms.add("registro");

        entity.aliases().stream()
                .map(StaviaText::normalize)
                .filter(term -> !term.isBlank() && !term.contains("/"))
                .forEach(terms::add);

        switch (entity.name()) {
            case "material" -> {
                terms.add("material");
                terms.add("insumo");
            }
            case "maoObra" -> {
                terms.add("mao de obra");
                terms.add("colaborador");
                terms.add("trabalhador");
                terms.add("funcionario");
                terms.add("pessoa");
            }
            case "equipamento" -> {
                terms.add("equipamento");
                terms.add("maquina");
                terms.add("ativo");
            }
            case "controleGeometrico" -> {
                terms.add("trecho");
                terms.add("controle");
                terms.add("medicao");
                terms.add("medicao geometrica");
            }
            case "execucaoServico" -> {
                terms.add("servico");
                terms.add("atividade");
                terms.add("execucao");
                terms.add("producao");
            }
            case "attachment" -> {
                terms.add("foto");
                terms.add("anexo");
            }
            case "alocacaoColaborador" -> {
                terms.add("alocacao");
                terms.add("apontamento");
                terms.add("colaborador");
            }
            case "operationalEvent" -> {
                terms.add("evento");
                terms.add("evento operacional");
                terms.add("evento ontologico");
            }
            default -> {
            }
        }

        return terms.stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
    }
}
