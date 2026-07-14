package com.projeto.cortex.intelligence.stavia.planning;

import com.projeto.cortex.intelligence.stavia.intent.StaviaClassification;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.intelligence.stavia.semantic.OperationalRoleLexicon;
import com.projeto.cortex.intelligence.stavia.semantic.SemanticAttribute;
import com.projeto.cortex.intelligence.stavia.semantic.StaviaSemanticCatalog;
import com.projeto.cortex.intelligence.stavia.semantic.StaviaBusinessSemanticCatalog;
import com.projeto.cortex.intelligence.stavia.text.StaviaText;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class StaviaQueryPlanner {

    private static final ZoneId BUSINESS_ZONE =
            ZoneId.of("America/Sao_Paulo");
    private static final Pattern KM_REFERENCE =
            Pattern.compile("(?iu)\\bkm\\s*\\d");
    private static final Pattern SELECTED_RDO_CONTEXT =
            Pattern.compile("(?iu)\\brdoId\\s*=");

    private final StaviaSemanticCatalog catalog;
    private final OperationalRoleLexicon roleLexicon;
    private final Clock clock;
    private final TemporalFilterParser temporalParser;
    private final RdoOntologyPlanner ontologyPlanner;

    public StaviaQueryPlanner() {
        this(
                new StaviaSemanticCatalog(),
                new OperationalRoleLexicon(),
                Clock.system(BUSINESS_ZONE)
        );
    }

    @Autowired
    public StaviaQueryPlanner(
            ObjectProvider<StaviaSemanticCatalog> catalog
    ) {
        this(
                catalog == null
                        ? new StaviaSemanticCatalog()
                        : catalog.getIfAvailable(
                                StaviaSemanticCatalog::new
                        ),
                new OperationalRoleLexicon(),
                Clock.system(BUSINESS_ZONE)
        );
    }

    public StaviaQueryPlanner(StaviaSemanticCatalog catalog) {
        this(
                catalog,
                new OperationalRoleLexicon(),
                Clock.system(BUSINESS_ZONE)
        );
    }

    public StaviaQueryPlanner(
            StaviaSemanticCatalog catalog,
            Clock clock
    ) {
        this(catalog, new OperationalRoleLexicon(), clock);
    }

    public StaviaQueryPlanner(
            StaviaSemanticCatalog catalog,
            OperationalRoleLexicon roleLexicon,
            Clock clock
    ) {
        this.catalog = catalog;
        this.roleLexicon = roleLexicon == null
                ? new OperationalRoleLexicon()
                : roleLexicon;
        this.clock = clock == null
                ? Clock.system(BUSINESS_ZONE)
                : clock;
        this.temporalParser = new TemporalFilterParser(this.clock);
        this.ontologyPlanner = new RdoOntologyPlanner(
                com.projeto.cortex.intelligence.stavia.semantic.rdo
                        .RdoOntology.load(),
                this.temporalParser
        );
    }

    public StaviaQueryPlan plan(
            StaviaQuestion question,
            StaviaClassification classification
    ) {
        if (question == null) {
            return StaviaQueryPlan.empty();
        }

        String normalized =
                StaviaText.normalize(question.text());

        if (normalized.isBlank()) {
            return StaviaQueryPlan.empty();
        }

        boolean selectedRdoContext =
                hasSelectedRdoContext(question);

        StaviaQueryPlan businessOperational =
                businessOperationalPlan(question, classification.intent());

        if (businessOperational.planned()) {
            return businessOperational;
        }

        if (isFinancialIntent(classification.intent())
                && isUnambiguousFinancialQuestion(normalized)) {
            return StaviaQueryPlan.empty();
        }

        StaviaQueryPlan combined =
                combinedRainAllocationPlan(question, normalized);

        if (combined.planned()) {
            return combined;
        }

        StaviaQueryPlan crossWorksiteAllocation =
                crossWorksiteAllocationPlan(
                        question,
                        normalized
                );

        if (crossWorksiteAllocation.planned()) {
            return crossWorksiteAllocation;
        }

        StaviaQueryPlan rdoDate =
                ontologyPlanner.rdoDatePlan(question, normalized);

        if (rdoDate.planned()) {
            return rdoDate;
        }

        StaviaQueryPlan ordinalOntologyPlan =
                ontologyPlanner.plan(question, normalized);

        if (hasOrdinalEntity(ordinalOntologyPlan)) {
            return ordinalOntologyPlan;
        }

        StaviaQueryPlan segment = segmentPlan(question, normalized);

        if (segment.planned()) {
            return segment;
        }

        StaviaQueryPlan collaboratorProfile =
                collaboratorProfilePlan(question, normalized);

        if (collaboratorProfile.planned()) {
            return collaboratorProfile;
        }

        StaviaQueryPlan assetCatalog =
                assetCatalogPlan(question, normalized);

        if (assetCatalog.planned()) {
            return assetCatalog;
        }

        StaviaQueryPlan selectedRdoOntologyPlan =
                ontologyPlanner.plan(question, normalized);

        if (selectedRdoContext
                && selectedRdoOntologyPlan.planned()) {
            return selectedRdoOntologyPlan;
        }

        StaviaQueryPlan team = teamPlan(
                question,
                classification
        );

        if (team.planned()) {
            return team;
        }

        StaviaQueryPlan ontologyPlan =
                selectedRdoOntologyPlan.planned()
                        ? selectedRdoOntologyPlan
                        : ontologyPlanner.plan(question, normalized);

        if (ontologyPlan.planned()) {
            return ontologyPlan;
        }

        StaviaQueryPlan contextDocuments =
                contextDocumentPlan(question, normalized);

        if (contextDocuments.planned()) {
            return contextDocuments;
        }

        List<SemanticAttribute> worksiteAttributes =
                catalog.matchAttributes(
                        QueryDomain.OBRA,
                        question.text()
                );

        if (!worksiteAttributes.isEmpty()) {
            return new StaviaQueryPlan(
                    QueryDomain.OBRA,
                    QueryOperation.READ_ATTRIBUTE,
                    entities(question),
                    TemporalFilter.none(),
                    worksiteAttributes.stream()
                            .map(SemanticAttribute::name)
                            .distinct()
                            .toList(),
                    List.of(),
                    List.of(),
                    List.of("cadastro-de-obras"),
                    false,
                    false,
                    false
            );
        }

        List<SemanticAttribute> rdoAttributes =
                catalog.matchAttributes(
                        QueryDomain.RDO,
                        question.text()
                );

        if (!rdoAttributes.isEmpty()) {
            boolean latest =
                    requestsLatest(normalized)
                            || classification == null
                            || classification.intent()
                                    == StaviaIntent.DESCONHECIDA;

            return new StaviaQueryPlan(
                    QueryDomain.RDO,
                    QueryOperation.READ_ATTRIBUTE,
                    entities(question),
                    temporalFilter(normalized, latest),
                    expandRdoAttributeNames(rdoAttributes),
                    List.of(),
                    List.of(),
                    List.of(
                            "cadastro-rdos",
                            "historico-operacional"
                    ),
                    latest,
                    containsAny(normalized, "historico", "dias", "semana"),
                    containsAny(normalized, "comparar", "comparacao", "mudou")
            );
        }

        return StaviaQueryPlan.empty();
    }

    private StaviaQueryPlan contextDocumentPlan(
            StaviaQuestion question,
            String normalized
    ) {
        if (!containsAny(
                normalized,
                "contexto",
                "anexo",
                "arquivo",
                "documento",
                "pdf",
                "imagem",
                "foto"
        )) {
            return StaviaQueryPlan.empty();
        }

        return new StaviaQueryPlan(
                QueryDomain.OBRA,
                QueryOperation.LIST_OBJECTS,
                entities(question),
                TemporalFilter.none(),
                List.of(
                        "descricao",
                        "nomeArquivo",
                        "textoExtraido",
                        "contentType"
                ),
                List.of("CONTEXTUALIZA"),
                List.of(),
                List.of("contexto-da-obra"),
                false,
                true,
                false
        );
    }

    /**
     * A map click can be translated to the same range query as natural
     * language such as "o que aconteceu entre o km 10 e o km 12?". The
     * records are read directly from the local RDO geometric controls.
     */
    private StaviaQueryPlan segmentPlan(
            StaviaQuestion question,
            String normalized
    ) {
        boolean kilometreQuestion =
                KM_REFERENCE.matcher(normalized).find();
        boolean measurementQuestion =
                containsAny(
                        normalized,
                        "area",
                        "área",
                        "m2",
                        "m²",
                        "metro quadrado",
                        "metros quadrados",
                        "volume",
                        "m3",
                        "m³",
                        "metro cubico",
                        "metro cúbico",
                        "metros cubicos",
                        "metros cúbicos"
                );

        if (!kilometreQuestion && !measurementQuestion) {
            return StaviaQueryPlan.empty();
        }

        List<AggregationSpec> aggregations =
                measurementQuestion
                        ? List.of(
                                new AggregationSpec(
                                        "SUM",
                                        "areaM2",
                                        null
                                ),
                                new AggregationSpec(
                                        "SUM",
                                        "volumeM3",
                                        null
                                )
                        )
                        : List.of();

        return new StaviaQueryPlan(
                QueryDomain.RDO,
                QueryOperation.READ_ATTRIBUTE,
                entities(question),
                TemporalFilter.none(),
                List.of(
                        "subtrecho",
                        "kmInicial",
                        "kmFinal",
                        "comprimentoM",
                        "areaM2",
                        "volumeM3"
                ),
                List.of("OCORRE_NO_TRECHO"),
                aggregations,
                List.of("trechos-operacionais"),
                false,
                true,
                false
        );
    }

    /**
     * Plans relationship questions such as "Abner está em outra obra?"
     * without waiting for the local model. The allocation source then resolves
     * the person against both current allocations and the legacy RDO workforce
     * records across the Córtex.
     */
    private StaviaQueryPlan crossWorksiteAllocationPlan(
            StaviaQuestion question,
            String normalized
    ) {
        if (!containsAny(
                normalized,
                "outra obra",
                "outras obras",
                "em qual obra",
                "em quais obras",
                "quais sao as obras que",
                "quais obras que",
                "trabalhou em outra",
                "trabalha em outra",
                "alocado em outra"
        )) {
            return StaviaQueryPlan.empty();
        }

        String collaborator =
                collaboratorNameAfterWorksiteRelationship(question.text());

        if (collaborator == null) {
            collaborator =
                    collaboratorNameBeforeWorksiteRelationship(question.text());
        }

        if (collaborator == null) {
            collaborator =
                    collaboratorNameInWorksiteListQuestion(question.text());
        }

        if (collaborator == null) {
            collaborator =
                    collaboratorNameBeforeRelationship(question.text());
        }

        if (collaborator == null) {
            return StaviaQueryPlan.empty();
        }

        List<ResolvedEntity> relationEntities =
                new ArrayList<>(entities(question));
        relationEntities.add(
                ResolvedEntity.collaboratorByName(collaborator)
        );

        return new StaviaQueryPlan(
                QueryDomain.COLABORADOR,
                QueryOperation.TRAVERSE_RELATIONSHIP,
                relationEntities,
                TemporalFilter.none(),
                List.of(),
                List.of("ALOCADO_EM"),
                List.of(),
                List.of(
                        "alocacao-colaborador",
                        "cadastro-colaboradores-academy"
                ),
                false,
                true,
                false
        );
    }

    private String collaboratorNameAfterWorksiteRelationship(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }

        String candidate = question.replaceFirst(
                "(?iu)^.*?\\bem\\s+qu(?:al|ais)\\s+obras?\\s+"
                        + "(?:está|esta|trabalha|trabalhou|atua|"
                        + "foi\\s+alocado|tem\\s+alocação|tem\\s+alocacao)"
                        + "\\s+(?:(?:o|a)\\s+)?"
                        + "(?:(?:colaborador|funcionário|funcionario|trabalhador)\\s+)?",
                ""
        );

        if (candidate.equals(question)) {
            return null;
        }

        return cleanCollaboratorCandidate(candidate);
    }

    private String collaboratorNameBeforeWorksiteRelationship(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }

        String candidate = question.replaceFirst(
                "(?iu)^.*?\\bem\\s+qu(?:al|ais)\\s+obras?\\s+"
                        + "(?:(?:o|a)\\s+)?"
                        + "(?:(?:colaborador|funcionário|funcionario|trabalhador)\\s+)?",
                ""
        );

        if (candidate.equals(question)) {
            return null;
        }

        candidate = candidate.replaceFirst(
                "(?iu)\\s+(está|esta|trabalha|trabalhou|atua|"
                        + "foi\\s+alocado|tem\\s+alocação|tem\\s+alocacao)"
                        + "(?:\\s|[?!.]|$).*?$",
                ""
        );

        return cleanCollaboratorCandidate(candidate);
    }

    /**
     * Plans a direct person lookup (for example, "Quem é Abner Pereira?").
     * It deliberately uses the same relationship traversal as an allocation
     * query, because a collaborator's historical role and worksite are stored
     * in both current allocations and legacy RDO workforce records.
     */
    private StaviaQueryPlan collaboratorProfilePlan(
            StaviaQuestion question,
            String normalized
    ) {
        if (!normalized.startsWith("quem e ")) {
            return StaviaQueryPlan.empty();
        }

        String collaborator = collaboratorNameAfterProfilePrompt(question.text());
        if (collaborator == null) {
            return StaviaQueryPlan.empty();
        }

        List<ResolvedEntity> profileEntities =
                new ArrayList<>(entities(question));
        profileEntities.add(
                ResolvedEntity.collaboratorByName(collaborator)
        );

        return new StaviaQueryPlan(
                QueryDomain.COLABORADOR,
                QueryOperation.TRAVERSE_RELATIONSHIP,
                profileEntities,
                TemporalFilter.none(),
                List.of(),
                List.of("PERFIL_COLABORADOR"),
                List.of(),
                List.of(
                        "alocacao-colaborador",
                        "cadastro-colaboradores-academy"
                ),
                false,
                true,
                false
        );
    }

    private String collaboratorNameBeforeRelationship(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }

        String candidate = question
                .replaceFirst(
                        "(?iu)\\s+(está|esta|trabalha|trabalhou|atua|foi\\s+alocado|tem\\s+alocação|tem\\s+alocacao).*?$",
                        ""
                )
                .replaceFirst(
                        "(?iu)^\\s*(o|a)?\\s*(colaborador|funcionário|funcionario|trabalhador)\\s+",
                        ""
                )
                .replaceAll("[?!.]+$", "")
                .trim();

        return cleanCollaboratorCandidate(candidate);
    }

    private String collaboratorNameInWorksiteListQuestion(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }

        String candidate = question
                .replaceFirst(
                        "(?iu)^\\s*quais\\s+(?:são|sao)\\s+(?:as\\s+)?obras\\s+que\\s+(?:(?:o|a)\\s+)?",
                        ""
                )
                .replaceFirst(
                        "(?iu)^\\s*quais\\s+obras\\s+que\\s+(?:(?:o|a)\\s+)?",
                        ""
                )
                .replaceFirst(
                        "(?iu)^\\s*em\\s+quais\\s+obras\\s+(?:(?:o|a)\\s+)?",
                        ""
                );

        if (candidate.equals(question)) {
            return null;
        }

        candidate = candidate
                .replaceFirst(
                        "(?iu)\\s+(está|esta|trabalha|trabalhou|atua|foi\\s+alocado|tem\\s+alocação|tem\\s+alocacao).*?$",
                        ""
                )
                .replaceAll("[?!.]+$", "")
                .trim();

        return cleanCollaboratorCandidate(candidate);
    }

    private String collaboratorNameAfterProfilePrompt(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }

        String candidate = question
                .replaceFirst(
                        "(?iu)^\\s*quem\\s+(?:é|e)\\s+(?:(?:o|a)\\s+)?(?:(?:colaborador|funcionário|funcionario|trabalhador)\\s+)?",
                        ""
                )
                .replaceAll("[?!.]+$", "")
                .trim();

        return cleanCollaboratorCandidate(candidate);
    }

    private String cleanCollaboratorCandidate(String candidate) {
        if (candidate == null) {
            return null;
        }

        String cleaned = candidate
                .replaceFirst(
                        "(?is)\\R\\s*Contexto\\s+ontol[oó]gico\\s+selecionado:.*$",
                        ""
                )
                .replaceFirst(
                        "(?is)\\R\\s*Contexto\\s+informado:.*$",
                        ""
                )
                .replaceAll("[?!.]+$", "")
                .trim();

        if (cleaned.split("\\s+").length < 2
                || roleLexicon.mentionsTeam(cleaned)) {
            return null;
        }

        return cleaned;
    }

    private StaviaQueryPlan assetCatalogPlan(
            StaviaQuestion question,
            String normalized
    ) {
        boolean asksCatalog =
                containsAny(
                        normalized,
                        "zeladoria",
                        "cadastro de ativo",
                        "cadastro de ativos",
                        "cadastro de equipamento",
                        "cadastro de equipamentos",
                        "ativos cadastrados",
                        "equipamentos cadastrados",
                        "ativos existem",
                        "equipamentos existem",
                        "quais ativos",
                        "quais equipamentos cadastrados"
                );

        if (!asksCatalog) {
            return StaviaQueryPlan.empty();
        }

        return new StaviaQueryPlan(
                QueryDomain.EQUIPAMENTO,
                QueryOperation.LIST_OBJECTS,
                entities(question),
                TemporalFilter.none(),
                List.of(),
                List.of("CADASTRADO_EM_ZELADORIA"),
                List.of(),
                List.of("cadastro-ativos-zeladoria"),
                false,
                false,
                false
        );
    }

    private StaviaQueryPlan teamPlan(
            StaviaQuestion question,
            StaviaClassification classification
    ) {
        boolean teamIntent = classification != null
                && classification.intent()
                        == StaviaIntent.CONSULTAR_EQUIPE;

        if (!teamIntent && !roleLexicon.mentionsTeam(question.text())) {
            return StaviaQueryPlan.empty();
        }

        List<ResolvedEntity> teamEntities =
                new ArrayList<>(entities(question));

        roleLexicon.rolesMentioned(question.text())
                .stream()
                .map(ResolvedEntity::roleByLabel)
                .forEach(teamEntities::add);

        return new StaviaQueryPlan(
                QueryDomain.EQUIPE,
                QueryOperation.READ_ATTRIBUTE,
                teamEntities,
                TemporalFilter.none(),
                List.of(),
                List.of(),
                List.of(),
                List.of("mao-de-obra-dos-rdos"),
                false,
                false,
                false
        );
    }

    public StaviaIntent effectiveIntent(
            StaviaIntent classifiedIntent,
            StaviaQueryPlan plan
    ) {
        if (plan != null
                && plan.planned()
                && plan.requiredSources().contains("trechos-operacionais")) {
            return StaviaIntent.CONSULTAR_RDO;
        }

        if (plan != null
                && plan.planned()
                && plan.requiredSources().contains("contexto-da-obra")) {
            return StaviaIntent.CONSULTAR_OBRA;
        }

        if (plan != null
                && plan.planned()
                && plan.domain() == QueryDomain.COLABORADOR
                && plan.operation() == QueryOperation.TRAVERSE_RELATIONSHIP) {
            return StaviaIntent.CONSULTAR_ALOCACAO_COLABORADOR;
        }

        if (plan != null
                && plan.planned()
                && plan.domain() == QueryDomain.RDO
                && plan.requiredSources().contains("registros-rdo")) {
            return StaviaIntent.CONSULTAR_RDO;
        }

        if (plan != null
                && plan.planned()
                && plan.domain() == QueryDomain.RDO
                && (
                        plan.operation() == QueryOperation.READ_ATTRIBUTE
                                || plan.operation() == QueryOperation.LIST_OBJECTS
                                || plan.operation() == QueryOperation.AGGREGATE
                                || plan.operation() == QueryOperation.COMPARE
                )
                && (
                        classifiedIntent == null
                                || classifiedIntent == StaviaIntent.DESCONHECIDA
                                || classifiedIntent == StaviaIntent.CONSULTAR_OBRA
                )) {
            return StaviaIntent.CONSULTAR_RDO;
        }

        if (classifiedIntent != null
                && classifiedIntent != StaviaIntent.DESCONHECIDA) {
            return classifiedIntent;
        }

        if (plan == null || !plan.planned()) {
            return StaviaIntent.DESCONHECIDA;
        }

        return switch (plan.domain()) {
            case RDO -> StaviaIntent.CONSULTAR_RDO;
            case COLABORADOR, EQUIPE -> StaviaIntent.CONSULTAR_ALOCACAO_COLABORADOR;
            case EQUIPAMENTO -> StaviaIntent.CONSULTAR_ATIVO;
            case FINANCEIRO -> StaviaIntent.CONSULTAR_PREVISAO_FINANCEIRA;
            case MENSAGENS -> StaviaIntent.CONSULTAR_DOCUMENTOS_MENSAGEM_PENDENTES;
            case FREQUENCIA -> StaviaIntent.CONSULTAR_FREQUENCIA;
            case BANCO_HORAS -> StaviaIntent.CONSULTAR_BANCO_HORAS;
            case OBRA -> StaviaIntent.CONSULTAR_OBRA;
            case PROGRAMACAO -> StaviaIntent.CONSULTAR_PROGRAMACAO;
            default -> StaviaIntent.DESCONHECIDA;
        };
    }

    private boolean isFinancialIntent(StaviaIntent intent) {
        return intent != null && switch (intent) {
            case CONSULTAR_PDOR,
                    CONSULTAR_RECEITA,
                    CONSULTAR_MARGEM,
                    CONSULTAR_PREVISAO_FINANCEIRA,
                    CONSULTAR_PRODUCAO,
                    CONSULTAR_RECEITA_EM_RISCO,
                    CONSULTAR_NOTAS_FISCAIS_VENCIDAS,
                    CONSULTAR_HISTORICO_COMPRA,
                    CONSULTAR_TOTAL_COMPRADO,
                    CONSULTAR_FORNECEDORES_COBRANCA_PENDENTE -> true;
            default -> false;
        };
    }

    private StaviaQueryPlan businessOperationalPlan(
            StaviaQuestion question,
            StaviaIntent intent
    ) {
        if (intent == null) {
            return StaviaQueryPlan.empty();
        }

        return switch (intent) {
            case CONSULTAR_NOTAS_FISCAIS_VENCIDAS -> businessPlan(
                    question,
                    QueryDomain.FINANCEIRO,
                    QueryOperation.LIST_OBJECTS,
                    List.of(
                            "notaFiscalId", "fornecedorId", "fornecedorNome",
                            "numero", "serie", "moeda", "valorAberto",
                            "vencimentoEm", "freshnessAt"
                    ),
                    StaviaBusinessSemanticCatalog.FINANCE_SOURCE
            );
            case CONSULTAR_HISTORICO_COMPRA -> businessPlan(
                    question,
                    QueryDomain.FINANCEIRO,
                    QueryOperation.LIST_OBJECTS,
                    List.of(
                            "compraId", "numeroPedido", "criadoPorId",
                            "criadoPorNome", "statusAnterior", "statusNovo",
                            "atorId", "atorNome", "ocorridoEm", "resultado"
                    ),
                    StaviaBusinessSemanticCatalog.FINANCE_SOURCE
            );
            case CONSULTAR_TOTAL_COMPRADO -> businessPlan(
                    question,
                    QueryDomain.FINANCEIRO,
                    QueryOperation.AGGREGATE,
                    List.of(
                            "moeda", "totalComprado", "quantidadeCompras",
                            "periodoInicio", "periodoFim", "freshnessAt"
                    ),
                    StaviaBusinessSemanticCatalog.FINANCE_SOURCE
            );
            case CONSULTAR_FORNECEDORES_COBRANCA_PENDENTE -> businessPlan(
                    question,
                    QueryDomain.FINANCEIRO,
                    QueryOperation.LIST_OBJECTS,
                    List.of(
                            "fornecedorId", "fornecedorNome",
                            "quantidadeCobrancasPendentes",
                            "primeiraOcorrenciaPrevistaEm", "freshnessAt"
                    ),
                    StaviaBusinessSemanticCatalog.FINANCE_SOURCE
            );
            case CONSULTAR_DOCUMENTOS_MENSAGEM_PENDENTES -> businessPlan(
                    question,
                    QueryDomain.MENSAGENS,
                    QueryOperation.LIST_OBJECTS,
                    List.of(
                            "anexoId", "mensagemId", "conversaId",
                            "storedObjectId", "storageStatus", "syncReceiptId",
                            "syncStatus", "erroCategoria", "freshnessAt"
                    ),
                    StaviaBusinessSemanticCatalog.MESSAGE_SYNC_SOURCE
            );
            default -> StaviaQueryPlan.empty();
        };
    }

    private StaviaQueryPlan businessPlan(
            StaviaQuestion question,
            QueryDomain domain,
            QueryOperation operation,
            List<String> attributes,
            String source
    ) {
        return new StaviaQueryPlan(
                domain,
                operation,
                entities(question),
                TemporalFilter.none(),
                attributes,
                List.of(),
                List.of(),
                List.of(source),
                false,
                operation == QueryOperation.LIST_OBJECTS,
                false
        );
    }

    private boolean isUnambiguousFinancialQuestion(String normalized) {
        return StaviaText.containsWord(normalized, "previsao financeira")
                || StaviaText.containsWord(normalized, "financeiro da obra")
                || StaviaText.containsWord(normalized, "pdor")
                || StaviaText.containsWord(normalized, "margem")
                || StaviaText.containsWord(normalized, "receita em risco")
                || StaviaText.containsWord(normalized, "risco de receita")
                || StaviaText.containsWord(normalized, "previsao de receita")
                || StaviaText.containsWord(normalized, "receita prevista")
                || (
                    StaviaText.containsWord(normalized, "obra")
                            && (
                                StaviaText.containsWord(
                                        normalized,
                                        "custo previsto"
                                )
                                        || StaviaText.containsWord(
                                                normalized,
                                                "custo realizado"
                                        )
                            )
                )
                || StaviaText.containsWord(normalized, "resultado operacional");
    }

    public double effectiveConfidence(
            double classifiedConfidence,
            StaviaIntent classifiedIntent,
            StaviaQueryPlan plan
    ) {
        if (plan != null
                && plan.planned()
                && plan.requiredSources().contains("trechos-operacionais")) {
            return 0.95;
        }

        if (plan != null
                && plan.planned()
                && plan.requiredSources().contains("contexto-da-obra")) {
            return 0.9;
        }

        if (classifiedIntent != StaviaIntent.DESCONHECIDA) {
            if (plan != null
                    && plan.planned()
                    && plan.domain() == QueryDomain.RDO
                    && (
                            plan.operation() == QueryOperation.READ_ATTRIBUTE
                                    || plan.operation()
                                            == QueryOperation.LIST_OBJECTS
                                    || plan.operation()
                                            == QueryOperation.AGGREGATE
                                    || plan.operation()
                                            == QueryOperation.COMPARE
                    )
                    && classifiedIntent == StaviaIntent.CONSULTAR_OBRA) {
                return 0.9;
            }

            return classifiedConfidence;
        }

        return plan != null && plan.planned()
                ? 0.75
                : classifiedConfidence;
    }

    private StaviaQueryPlan combinedRainAllocationPlan(
            StaviaQuestion question,
            String normalized
    ) {
        if (!containsAny(normalized, "ultimo dia de chuva", "dia de chuva")
                || !containsAny(normalized, "colaborador", "colaboradores")
                || !containsAny(normalized, "equipamento", "equipamentos", "maquina", "maquinas")) {
            return StaviaQueryPlan.empty();
        }

        return new StaviaQueryPlan(
                QueryDomain.RDO,
                QueryOperation.TRAVERSE_RELATIONSHIP,
                entities(question),
                TemporalFilter.latest("DATA_RDO_COM_CHUVA"),
                List.of("pluviometriaMm", "condicaoManha", "condicaoTarde", "condicaoNoite"),
                List.of("ALOCACAO_COLABORADOR", "ALOCACAO_EQUIPAMENTO"),
                List.of(),
                List.of("cadastro-rdos", "alocacao-colaborador", "equipamentos-dos-rdos"),
                true,
                true,
                false
        );
    }

    private boolean hasOrdinalEntity(StaviaQueryPlan plan) {
        return plan != null
                && plan.planned()
                && plan.domain() == QueryDomain.RDO
                && plan.requiredSources().contains("registros-rdo")
                && plan.entities().stream()
                        .anyMatch(entity ->
                                "ORDINAL".equals(entity.resolvedBy()));
    }

    private boolean hasSelectedRdoContext(StaviaQuestion question) {
        return question != null
                && question.text() != null
                && SELECTED_RDO_CONTEXT.matcher(question.text()).find();
    }

    private List<String> expandRdoAttributeNames(
            List<SemanticAttribute> matched
    ) {
        List<String> names = new ArrayList<>();

        boolean climate =
                matched.stream()
                        .map(SemanticAttribute::name)
                        .anyMatch(name ->
                                name.startsWith("condicao")
                                        || "pluviometriaMm".equals(name)
                        );

        if (climate) {
            names.add("condicaoManha");
            names.add("condicaoTarde");
            names.add("condicaoNoite");
            names.add("pluviometriaMm");
        }

        matched.stream()
                .map(SemanticAttribute::name)
                .forEach(names::add);

        return names.stream()
                .distinct()
                .toList();
    }

    private List<ResolvedEntity> entities(StaviaQuestion question) {
        if (question.obraId() == null || question.obraId().isBlank()) {
            return List.of();
        }

        return List.of(
                ResolvedEntity.worksiteById(question.obraId())
        );
    }

    private TemporalFilter temporalFilter(
            String normalized,
            boolean latest
    ) {
        return temporalParser.parse(normalized, latest);
    }

    private boolean requestsLatest(String normalized) {
        return temporalParser.requestsLatest(normalized);
    }

    private boolean containsAny(
            String value,
            String... candidates
    ) {
        String lower =
                value.toLowerCase(Locale.ROOT);

        for (String candidate : candidates) {
            if (lower.contains(candidate)) {
                return true;
            }
        }

        return false;
    }
}
