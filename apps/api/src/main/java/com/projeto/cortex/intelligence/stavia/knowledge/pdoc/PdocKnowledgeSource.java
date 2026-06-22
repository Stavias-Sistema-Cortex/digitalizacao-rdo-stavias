package com.projeto.cortex.intelligence.stavia.knowledge.pdoc;

import com.projeto.cortex.intelligence.PdocContextBuilder;
import com.projeto.cortex.intelligence.PdocEngine;
import com.projeto.cortex.intelligence.stavia.StaviaEngine;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.intelligence.stavia.version.StaviaVersions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class PdocKnowledgeSource
        implements StaviaKnowledgeSource {

    private final PdocSnapshotProvider snapshotProvider;
    private final PdocContextBuilder contextBuilder;
    private final PdocEngine engine;

    @Autowired
    public PdocKnowledgeSource(
            PdocSnapshotProvider snapshotProvider
    ) {
        this(
                snapshotProvider,
                new PdocContextBuilder(),
                new PdocEngine()
        );
    }

    PdocKnowledgeSource(
            PdocSnapshotProvider snapshotProvider,
            PdocContextBuilder contextBuilder,
            PdocEngine engine
    ) {
        this.snapshotProvider = snapshotProvider;
        this.contextBuilder = contextBuilder;
        this.engine = engine;
    }

    @Override
    public String sourceName() {
        return "pdoc";
    }

    @Override
    public String sourceVersion() {
        return StaviaVersions.PDOC_SOURCE;
    }

    @Override
    public boolean supports(
            StaviaKnowledgeRequest request
    ) {
        if (request == null) {
            return false;
        }

        if (
                !request.permissions().contains(
                        StaviaEngine.REQUIRED_PERMISSION
                )
        ) {
            return false;
        }

        return request.intent() == StaviaIntent.CONSULTAR_PDOC
                || request.intent()
                        == StaviaIntent.CONSULTAR_ESTADO_ATUAL
                || request.intent()
                        == StaviaIntent.RESUMIR_OBRA;
    }

    @Override
    public List<StaviaEvidence> retrieve(
            StaviaKnowledgeRequest request
    ) {
        return snapshotProvider
                .findLatestByWorksiteId(
                        request.worksiteId()
                )
                .map(contextBuilder::build)
                .map(engine::calculate)
                .map(this::toEvidence)
                .map(List::of)
                .orElseGet(List::of);
    }

    private StaviaEvidence toEvidence(
            PdocEngine.PdocResult result
    ) {
        Map<String, Object> attributes =
                new LinkedHashMap<>();

        attributes.put(
                "obraId",
                result.obraId()
        );
        attributes.put(
                "modelVersion",
                result.modelVersion()
        );
        attributes.put(
                "assumptionsVersion",
                result.assumptionsVersion()
        );
        attributes.put(
                "calculationMode",
                result.calculationMode().name()
        );
        attributes.put(
                "calibrationStatus",
                result.calibrationStatus().name()
        );
        attributes.put(
                "projectPhase",
                result.projectPhase().name()
        );
        attributes.put(
                "riskLevel",
                result.riskLevel().name()
        );
        attributes.put(
                "heuristicRiskScore",
                result.heuristicRiskScore()
        );
        attributes.put(
                "confidence",
                result.confidence()
        );
        attributes.put(
                "simulationConverged",
                result.simulationConverged()
        );
        attributes.put(
                "simulationIterationsUsed",
                result.simulationIterationsUsed()
        );
        attributes.put(
                "probabilityAnyOverrun",
                result.simulationProbabilityAnyOverrun()
        );
        attributes.put(
                "probabilityOverFivePercent",
                result.simulationProbabilityOverFivePercent()
        );
        attributes.put(
                "probabilityOverTenPercent",
                result.simulationProbabilityOverTenPercent()
        );
        attributes.put("costP10", result.costP10());
        attributes.put("costP50", result.costP50());
        attributes.put("costP80", result.costP80());
        attributes.put("costP95", result.costP95());
        attributes.put("cpi", result.evm().cpi());
        attributes.put("spi", result.evm().spi());
        attributes.put(
                "drivers",
                result.drivers()
                        .stream()
                        .map(this::driverAttributes)
                        .toList()
        );

        return new StaviaEvidence(
                StaviaEvidenceTypes.PDOC,
                "PDOC:" + result.obraId()
                        + ":" + result.referenceDate(),
                buildSummary(result),
                result.referenceDate()
                        .atStartOfDay()
                        .toInstant(ZoneOffset.UTC),
                true,
                attributes
        );
    }

    private Map<String, Object> driverAttributes(
            PdocEngine.PdocDriver driver
    ) {
        Map<String, Object> attributes =
                new LinkedHashMap<>();

        attributes.put("code", driver.code());
        attributes.put(
                "description",
                driver.description()
        );
        attributes.put("impact", driver.impact());
        attributes.put(
                "evidence",
                driver.evidence()
        );

        return attributes;
    }

    private String buildSummary(
            PdocEngine.PdocResult result
    ) {
        return "O PDOC classificou a obra com risco "
                + result.riskLevel()
                + ", probabilidade simulada de "
                + percentage(
                        result.simulationProbabilityOverFivePercent()
                )
                + " de ultrapassar o orçamento em mais de 5%, "
                + "custo P50 de "
                + result.costP50()
                + ", CPI "
                + result.evm().cpi()
                + ", SPI "
                + result.evm().spi()
                + " e confiança "
                + percentage(result.confidence())
                + ".";
    }

    private String percentage(double value) {
        return String.format(
                java.util.Locale.ROOT,
                "%.2f%%",
                value * 100.0
        );
    }
}
