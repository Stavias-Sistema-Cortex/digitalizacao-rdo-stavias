package com.projeto.cortex.rdos.export;

import static com.projeto.cortex.rdos.export.RdoExportTestFixtures.populatedRdo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.projeto.cortex.rdos.RdoQueryService;
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
}
