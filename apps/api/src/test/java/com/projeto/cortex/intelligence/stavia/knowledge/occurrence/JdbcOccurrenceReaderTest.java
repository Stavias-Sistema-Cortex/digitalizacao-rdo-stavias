package com.projeto.cortex.intelligence.stavia.knowledge.occurrence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcOccurrenceReaderTest {

    @Test
    void shouldReadOccurrenceNotesFromEveryRdoObservationBlock() {
        String sql = JdbcOccurrenceReader.FIND_BY_WORKSITE_SQL;

        assertThat(sql).contains("FROM rdo_material material");
        assertThat(sql).contains("FROM rdo_mao_obra mao_obra");
        assertThat(sql).contains("FROM rdo_equipamento equipamento");
        assertThat(sql).contains("FROM rdo_controle_geometrico controle");
        assertThat(sql).contains("controle.atividade_observacoes");
        assertThat(sql).contains("FROM execucao_servico_rdo servico");
        assertThat(sql).contains("FROM alocacao_colaborador alocacao");
        assertThat(sql).contains("FROM ocorrencia_frequencia ocorrencia");
        assertThat(sql).contains("CONCAT(r.id, ':servico:', servico.id)");
    }
}
