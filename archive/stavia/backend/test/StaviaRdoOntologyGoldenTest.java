package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.context.StaviaContextBuilder;
import com.projeto.cortex.intelligence.stavia.context.StaviaRawContext;
import com.projeto.cortex.intelligence.stavia.generation.DeterministicStaviaResponseGenerator;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntentClassifier;
import com.projeto.cortex.intelligence.stavia.interpret.DeterministicQuestionInterpreter;
import com.projeto.cortex.intelligence.stavia.interpret.StaviaInterpretation;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.rdo.RdoEntityRecordReader;
import com.projeto.cortex.intelligence.stavia.knowledge.rdo.RdoRecordKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.knowledge.rdo.RdoRecordQuery;
import com.projeto.cortex.intelligence.stavia.model.StaviaAnswer;
import com.projeto.cortex.intelligence.stavia.model.StaviaContext;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.intelligence.stavia.planning.AggregationSpec;
import com.projeto.cortex.intelligence.stavia.planning.StaviaQueryPlan;
import com.projeto.cortex.intelligence.stavia.planning.StaviaQueryPlanner;
import com.projeto.cortex.intelligence.stavia.policy.StaviaContradictionPolicy;
import com.projeto.cortex.intelligence.stavia.policy.StaviaEvidenceQualityPolicy;
import com.projeto.cortex.intelligence.stavia.policy.StaviaGroundingValidator;
import com.projeto.cortex.intelligence.stavia.retrieval.StaviaEvidenceSelector;
import com.projeto.cortex.intelligence.stavia.semantic.StaviaSemanticCatalog;
import com.projeto.cortex.intelligence.stavia.semantic.rdo.RdoOntology;
import com.projeto.cortex.intelligence.stavia.semantic.rdo.RdoOntologyAttribute;
import com.projeto.cortex.intelligence.stavia.semantic.rdo.RdoOntologyEntity;
import com.projeto.cortex.intelligence.stavia.text.StaviaText;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden questions: perguntas operacionais reais respondidas ponta-a-ponta
 * (interpretação → plano → fonte genérica → contexto → engine) sobre um
 * conjunto fixo de registros de RDO. Garante que a ontologia cobre o escopo
 * prometido e que o texto final é utilizável.
 */
class StaviaRdoOntologyGoldenTest {

    private static final Instant NOW =
            Instant.parse("2026-07-02T15:00:00Z");
    private static final Clock FIXED_CLOCK =
            Clock.fixed(NOW, ZoneId.of("America/Sao_Paulo"));

    private final StaviaQueryPlanner planner =
            new StaviaQueryPlanner(
                    new StaviaSemanticCatalog(),
                    FIXED_CLOCK
            );

    private final DeterministicQuestionInterpreter interpreter =
            new DeterministicQuestionInterpreter(
                    new StaviaIntentClassifier(),
                    planner
            );

    private final RdoRecordKnowledgeSource source =
            new RdoRecordKnowledgeSource(
                    RdoOntology.load(),
                    new FakeRdoEntityRecordReader(fixtureRows())
            );

    private final StaviaContextBuilder contextBuilder =
            new StaviaContextBuilder();

    private final StaviaEngine engine =
            new StaviaEngine(
                    new StaviaIntentClassifier(),
                    new StaviaEvidenceSelector(),
                    new StaviaGroundingValidator(),
                    new StaviaEvidenceQualityPolicy(
                            Clock.fixed(NOW, ZoneOffset.UTC),
                            Duration.ofDays(7)
                    ),
                    new StaviaContradictionPolicy(),
                    new DeterministicStaviaResponseGenerator()
            );

    @Test
    void shouldAnswerMaterialFacts() {
        assertThat(answer("Qual a quantidade prevista de CAP 30/45?"))
                .contains("Quantidade prevista de CAP 30/45: 12.5 t");

        assertThat(answer("Qual a nota fiscal do CAP 30/45?"))
                .contains("Nota fiscal");

        assertThat(answer("Qual fornecedor entregou massa asfaltica?"))
                .contains("Fornecedor");
    }

    @Test
    void shouldListMaterials() {
        String answer = answer("Quais materiais foram registrados?");

        assertThat(answer).contains("CAP 30/45");
        assertThat(answer).contains("Massa asfáltica");
    }

    @Test
    void shouldAggregateMaterials() {
        assertThat(
                answer("Quanto de massa asfaltica foi aplicada essa semana?")
        ).contains("Total de Quantidade aplicada");

        assertThat(answer("Quanto de CAP 30/45 foi aplicado?"))
                .contains("Quantidade aplicada");

        assertThat(
                answer("Quantas toneladas de massa asfaltica sobraram?")
        ).contains("Sobra");
    }

    @Test
    void shouldAnswerWorkforceQuestions() {
        assertThat(answer("Quem trabalhou no RDO de 01/07/2026?"))
                .contains("João Silva");

        assertThat(answer("Qual o cargo de Joao Silva?"))
                .contains("Cargo");
    }

    @Test
    void shouldCountEquipment() {
        assertThat(answer("Quantos equipamentos ontem?"))
                .contains("Total de Quantidade");
    }

    @Test
    void shouldAnswerGeometricAndServiceQuestions() {
        assertThat(answer("Qual a espessura media aplicada?"))
                .contains("Espessura média");

        assertThat(answer("Qual a largura executada esta semana?"))
                .contains("Largura");

        assertThat(
                answer("Quanto foi executado de fresagem essa semana?")
        ).contains("Quantidade executada");
    }

    @Test
    void shouldCompareAndRank() {
        String comparison =
                answer("Comparar previsto vs aplicado de CAP 30/45");

        assertThat(comparison).contains("Quantidade prevista: 12.5 t");
        assertThat(comparison).contains("Quantidade aplicada: 11.9 t");

        assertThat(answer("Qual RDO teve mais chuva?"))
                .contains("teve o maior valor de Pluviometria: 18.2 mm");
    }

    @Test
    void shouldKeepHeaderFactsOnLegacyPath() {
        assertThat(plan("Qual a pluviometria de ontem?").requiredSources())
                .contains("cadastro-rdos");

        assertThat(plan("Qual o turno do último RDO?").requiredSources())
                .contains("cadastro-rdos");
    }

    private StaviaQueryPlan plan(String questionText) {
        return interpret(questionText).plan();
    }

    private StaviaInterpretation interpret(String questionText) {
        return interpreter.interpret(
                new StaviaQuestion(questionText, "usuario-1", "obra-1")
        ).orElseThrow();
    }

    private String answer(String questionText) {
        StaviaQuestion question =
                new StaviaQuestion(questionText, "usuario-1", "obra-1");
        StaviaInterpretation interpretation = interpret(questionText);
        StaviaQueryPlan plan = interpretation.plan();
        StaviaIntent intent = interpretation.intent();

        StaviaKnowledgeRequest request = new StaviaKnowledgeRequest(
                question,
                intent,
                "obra-1",
                Set.of(StaviaEngine.REQUIRED_PERMISSION),
                plan
        );

        assertThat(source.supports(request))
                .as("fonte registros-rdo deve suportar: " + questionText)
                .isTrue();

        List<StaviaEvidence> evidences = source.retrieve(request);

        assertThat(evidences)
                .as("evidências para: " + questionText)
                .isNotEmpty();

        StaviaRawContext rawContext = new StaviaRawContext(
                "usuario-1",
                "obra-1",
                Set.of(StaviaEngine.REQUIRED_PERMISSION),
                evidences.stream()
                        .map(evidence -> new StaviaRawContext.RawEvidence(
                                evidence.type(),
                                evidence.id(),
                                "obra-1",
                                StaviaEngine.REQUIRED_PERMISSION,
                                evidence.summary(),
                                evidence.updatedAt(),
                                evidence.validated(),
                                evidence.attributes()
                        ))
                        .toList()
        );

        StaviaContext context = contextBuilder.build(rawContext);
        StaviaAnswer answer = engine.answer(question, context, intent);

        return answer.answer();
    }

    private static Map<String, List<Map<String, Object>>> fixtureRows() {
        Map<String, List<Map<String, Object>>> rows =
                new LinkedHashMap<>();

        rows.put("rdo", List.of(
                header("r1", "123", "2026-07-01", "APROVADO", "4"),
                header("r2", "124", "2026-07-02", "ENVIADO", "18.2"),
                header("r3", "122", "2026-06-30", "APROVADO", "0")
        ));

        rows.put("material", List.of(
                row("mat-1", "r1", "123", "2026-07-01", "APROVADO", Map.of(
                        "materialNome", "CAP 30/45",
                        "quantidadePrevista", "12.5",
                        "quantidadeAplicada", "11.9",
                        "quantidadeSobra", "0.6",
                        "notaFiscal", "NF-777",
                        "fornecedor", "Petrobras",
                        "unidade", "t"
                )),
                row("mat-2", "r1", "123", "2026-07-01", "APROVADO", Map.of(
                        "materialNome", "Massa asfáltica prevista",
                        "quantidadePrevista", "35",
                        "quantidadeAplicada", "33.5",
                        "quantidadeSobra", "1.5",
                        "fornecedor", "Usina Central",
                        "unidade", "t"
                )),
                row("mat-3", "r2", "124", "2026-07-02", "ENVIADO", Map.of(
                        "materialNome", "Massa asfáltica prevista",
                        "quantidadeAplicada", "20",
                        "unidade", "t"
                ))
        ));

        rows.put("maoObra", List.of(
                row("mo-1", "r1", "123", "2026-07-01", "APROVADO", Map.of(
                        "nomeColaborador", "João Silva",
                        "cargo", "Apontador",
                        "quantidade", "1"
                )),
                row("mo-2", "r1", "123", "2026-07-01", "APROVADO", Map.of(
                        "nomeColaborador", "Maria Souza",
                        "cargo", "Encarregada",
                        "quantidade", "1"
                )),
                row("mo-3", "r2", "124", "2026-07-02", "ENVIADO", Map.of(
                        "nomeColaborador", "Pedro Lima",
                        "cargo", "Operador",
                        "quantidade", "1"
                ))
        ));

        rows.put("equipamento", List.of(
                row("eq-1", "r1", "123", "2026-07-01", "APROVADO", Map.of(
                        "prefixo", "VA-01",
                        "descricao", "Vibroacabadora",
                        "quantidade", "1"
                )),
                row("eq-2", "r1", "123", "2026-07-01", "APROVADO", Map.of(
                        "prefixo", "RC-02",
                        "descricao", "Rolo compactador",
                        "quantidade", "2"
                )),
                row("eq-3", "r2", "124", "2026-07-02", "ENVIADO", Map.of(
                        "prefixo", "CB-07",
                        "descricao", "Caminhão basculante",
                        "quantidade", "3"
                ))
        ));

        rows.put("controleGeometrico", List.of(
                row("cg-1", "r1", "123", "2026-07-01", "APROVADO", Map.of(
                        "subtrecho", "ST-1",
                        "comprimentoM", "500",
                        "larguraM", "7.2",
                        "espessuraMediaCm", "5.2",
                        "areaM2", "800",
                        "volumeM3", "120",
                        "massaTonelada", "60"
                )),
                row("cg-2", "r2", "124", "2026-07-02", "ENVIADO", Map.of(
                        "subtrecho", "ST-2",
                        "espessuraMediaCm", "4.8",
                        "volumeM3", "80"
                ))
        ));

        rows.put("execucaoServico", List.of(
                row("es-1", "r1", "123", "2026-07-01", "APROVADO", Map.of(
                        "servicoNome", "Fresagem contínua",
                        "quantidadeExecutada", "850",
                        "unidade_medida", "m²",
                        "dataExecucao", "2026-07-01"
                )),
                row("es-2", "r2", "124", "2026-07-02", "ENVIADO", Map.of(
                        "servicoNome", "Recomposição de base",
                        "quantidadeExecutada", "120",
                        "unidade_medida", "m³",
                        "dataExecucao", "2026-07-02"
                ))
        ));

        return rows;
    }

    private static Map<String, Object> header(
            String rdoId,
            String number,
            String date,
            String status,
            String rain
    ) {
        Map<String, Object> row = row(
                rdoId,
                rdoId,
                number,
                date,
                status,
                Map.of(
                        "dataRdo", date,
                        "turno", "DIURNO",
                        "pluviometriaMm", rain,
                        "status", status
                )
        );

        return row;
    }

    private static Map<String, Object> row(
            String recordId,
            String rdoId,
            String number,
            String date,
            String status,
            Map<String, Object> values
    ) {
        Map<String, Object> row = new LinkedHashMap<>();

        row.put("registroId", recordId);
        row.put("rdoId", rdoId);
        row.put("numeroRdo", number);
        row.put("dataRdo", date);
        row.put("statusRdo", status);
        row.put("obraId", "obra-1");
        row.putAll(values);

        return row;
    }

    private static final class FakeRdoEntityRecordReader
            implements RdoEntityRecordReader {

        private final Map<String, List<Map<String, Object>>> rowsByEntity;

        private FakeRdoEntityRecordReader(
                Map<String, List<Map<String, Object>>> rowsByEntity
        ) {
            this.rowsByEntity = rowsByEntity;
        }

        @Override
        public List<Map<String, Object>> findRecords(
                RdoOntologyEntity entity,
                RdoRecordQuery query
        ) {
            return rowsByEntity
                    .getOrDefault(entity.name(), List.of())
                    .stream()
                    .filter(row -> matchesPeriod(row, query))
                    .filter(row -> matchesIdentity(entity, row, query))
                    .limit(query.limit())
                    .toList();
        }

        @Override
        public List<Map<String, Object>> aggregate(
                RdoOntologyEntity entity,
                RdoOntologyAttribute attribute,
                AggregationSpec spec,
                RdoRecordQuery query
        ) {
            List<Map<String, Object>> rows = findRecords(entity, query);

            if ("rdo".equals(spec.groupBy())) {
                Map<String, List<Map<String, Object>>> groups =
                        new LinkedHashMap<>();

                rows.forEach(row -> groups.computeIfAbsent(
                        (String) row.get("rdoId"),
                        key -> new ArrayList<>()
                ).add(row));

                List<Map<String, Object>> result = new ArrayList<>();

                for (List<Map<String, Object>> group : groups.values()) {
                    BigDecimal value =
                            compute(group, entity, attribute, spec);

                    if (value == null) {
                        continue;
                    }

                    Map<String, Object> first = group.getFirst();
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("rdoId", first.get("rdoId"));
                    out.put("numeroRdo", first.get("numeroRdo"));
                    out.put("dataRdo", first.get("dataRdo"));
                    out.put(
                            "valor",
                            value.stripTrailingZeros().toPlainString()
                    );
                    out.put("linhas", String.valueOf(group.size()));
                    result.add(out);
                }

                Comparator<Map<String, Object>> byValue =
                        Comparator.comparing(row ->
                                new BigDecimal((String) row.get("valor"))
                        );

                result.sort(
                        "MIN".equalsIgnoreCase(spec.function())
                                ? byValue
                                : byValue.reversed()
                );

                return result.stream().limit(5).toList();
            }

            BigDecimal value = compute(rows, entity, attribute, spec);
            Map<String, Object> out = new LinkedHashMap<>();

            if (value != null) {
                out.put(
                        "valor",
                        value.stripTrailingZeros().toPlainString()
                );
            }

            out.put("linhas", String.valueOf(rows.size()));

            return List.of(out);
        }

        private BigDecimal compute(
                List<Map<String, Object>> rows,
                RdoOntologyEntity entity,
                RdoOntologyAttribute attribute,
                AggregationSpec spec
        ) {
            String function = spec.function() == null
                    ? "SUM"
                    : spec.function().toUpperCase(Locale.ROOT);

            List<BigDecimal> values = rows.stream()
                    .map(row -> (String) row.get(attribute.name()))
                    .filter(Objects::nonNull)
                    .map(BigDecimal::new)
                    .toList();

            if ("COUNT".equals(function)) {
                if (attribute.name().equals(entity.countAttribute())) {
                    return values.stream()
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                }

                return BigDecimal.valueOf(rows.size());
            }

            if (values.isEmpty()) {
                return null;
            }

            return switch (function) {
                case "AVG" -> values.stream()
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(
                                BigDecimal.valueOf(values.size()),
                                MathContext.DECIMAL64
                        );
                case "MAX" -> values.stream()
                        .max(Comparator.naturalOrder())
                        .orElse(null);
                case "MIN" -> values.stream()
                        .min(Comparator.naturalOrder())
                        .orElse(null);
                default -> values.stream()
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
            };
        }

        private boolean matchesPeriod(
                Map<String, Object> row,
                RdoRecordQuery query
        ) {
            String date = (String) row.get("dataRdo");

            if (date == null) {
                return true;
            }

            LocalDate value = LocalDate.parse(date);

            if (query.startDate() != null
                    && value.isBefore(query.startDate())) {
                return false;
            }

            return query.endDate() == null
                    || !value.isAfter(query.endDate());
        }

        private boolean matchesIdentity(
                RdoOntologyEntity entity,
                Map<String, Object> row,
                RdoRecordQuery query
        ) {
            if (query.identityTerm() == null) {
                return true;
            }

            String term = StaviaText.normalize(query.identityTerm());

            return entity.identityAttributes().stream()
                    .map(attribute ->
                            (String) row.get(attribute.name())
                    )
                    .filter(Objects::nonNull)
                    .map(StaviaText::normalize)
                    .anyMatch(value -> value.contains(term));
        }
    }
}
