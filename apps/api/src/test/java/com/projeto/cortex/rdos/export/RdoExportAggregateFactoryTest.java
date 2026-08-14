package com.projeto.cortex.rdos.export;

import static com.projeto.cortex.rdos.export.RdoExportTestFixtures.populatedRdo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.projeto.cortex.rdos.RdoQueryService;
import com.projeto.cortex.rdos.RdoResponse;
import org.junit.jupiter.api.Test;

class RdoExportAggregateFactoryTest {

    @Test
    void projectsTheValidatedPrintableSnapshotForEveryRenderer() {
        RdoQueryService queryService = mock(RdoQueryService.class);
        RdoExportWorksiteReader worksiteReader =
                mock(RdoExportWorksiteReader.class);
        when(queryService.buscarPorId("rdo-42")).thenReturn(
                populatedRdo("rdo-42", "RDO-0042")
        );
        when(worksiteReader.read("obra-7")).thenReturn(
                new RdoExportWorksiteReader.Worksite("Obra Norte", "CW-007")
        );
        RdoExportAggregateFactory factory =
                new RdoExportAggregateFactory(queryService, worksiteReader);

        RdoExportAggregate aggregate = factory.load("rdo-42");

        assertThat(aggregate.rdo().numeroRdo()).isEqualTo("RDO-0042");
        assertThat(aggregate.worksite()).isEqualTo(
                new RdoExportWorksiteReader.Worksite("Obra Norte", "CW-007")
        );
        assertThat(aggregate.workforce()).isNotEmpty();
        assertThat(aggregate.worked()).isNotEmpty();
        assertThat(aggregate.materials()).isNotEmpty();
        assertThat(aggregate.observations()).contains("RDO");
    }

    /**
     * Um parágrafo comum de observação cabe, e por isso não pode recusar.
     *
     * <p>O texto abaixo tem mais de 100 caracteres numa linha só — o que
     * qualquer apontador escreve sem pensar. Na folha do PDF ele ocupa uma
     * única linha das doze da área de observações, e na planilha entra numa
     * célula mesclada de seis linhas com quebra automática. Recusar aqui
     * deixava o RDO sem XLSX <em>e</em> sem PDF, porque a validação é comum
     * aos dois renderizadores.
     */
    @Test
    void acceptsAnObservationParagraphThatBothRenderersStillFit() {
        RdoQueryService queryService = mock(RdoQueryService.class);
        RdoExportWorksiteReader worksiteReader =
                mock(RdoExportWorksiteReader.class);
        String paragraph = "Frente liberada pela fiscalizacao apos a chuva da "
                + "madrugada; equipe deslocada para o km 10+400 e servico "
                + "retomado sem intercorrencias ate o fim do turno.";
        assertThat(paragraph.length()).isGreaterThan(100);
        when(queryService.buscarPorId("rdo-42")).thenReturn(
                withObservations(populatedRdo("rdo-42", "RDO-0042"), paragraph)
        );
        when(worksiteReader.read("obra-7")).thenReturn(
                new RdoExportWorksiteReader.Worksite("Obra Norte", "CW-007")
        );
        RdoExportAggregateFactory factory =
                new RdoExportAggregateFactory(queryService, worksiteReader);

        assertThatCode(() -> factory.load("rdo-42")).doesNotThrowAnyException();
        assertThat(factory.load("rdo-42").observations()).contains(paragraph);
    }

    private static RdoResponse withObservations(
            RdoResponse original,
            String observations
    ) {
        return new RdoResponse(
                original.id(), original.obraId(), original.programacaoId(),
                original.numeroRdo(), original.dataRdo(),
                original.previousRdoId(), original.creationContextVersion(),
                original.clientMutationId(), original.versaoEntidade(),
                original.apontadorColaboradorId(), original.diaSemana(),
                original.cliente(), original.contrato(), original.rodovia(),
                original.cidade(), original.uf(),
                original.kmInicialProgramado(), original.kmFinalProgramado(),
                original.kmInicialInterditado(), original.kmFinalInterditado(),
                original.turno(), original.horaInicio(), original.horaFim(),
                original.condicaoManha(), original.condicaoTarde(),
                original.condicaoNoite(), original.pluviometriaMm(),
                original.status(), observations, original.preenchidoPor(),
                original.apontadorRdo(), original.encarregadoObra(),
                original.fiscalizacaoCampo(), original.maoObra(),
                original.equipamentos(), original.materiais(),
                original.controlesGeometricos(), original.servicosExecutados(),
                original.alocacoesColaboradores(), original.attachments()
        );
    }
}
