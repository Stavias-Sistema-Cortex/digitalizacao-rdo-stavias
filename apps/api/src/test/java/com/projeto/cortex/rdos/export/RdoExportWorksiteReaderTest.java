package com.projeto.cortex.rdos.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

class RdoExportWorksiteReaderTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final RdoExportWorksiteReader reader =
            new RdoExportWorksiteReader(jdbcTemplate);

    @Test
    @SuppressWarnings("unchecked")
    void readsNameAndBusinessCodeWithoutContractOrUuidFallback() {
        when(jdbcTemplate.queryForObject(
                argThat(sql -> sql.contains("codigo_cw")
                        && sql.contains("codigo_interno")
                        && !sql.contains("codigo_contrato")),
                any(RowMapper.class),
                eq("obra-uuid")
        )).thenReturn(new RdoExportWorksiteReader.Worksite(
                "Obra Norte",
                "CW-007"
        ));

        assertThat(reader.read("obra-uuid"))
                .isEqualTo(new RdoExportWorksiteReader.Worksite(
                        "Obra Norte",
                        "CW-007"
                ));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsWorksiteWithoutBusinessCodeInsteadOfPrintingUuidOrContract() {
        when(jdbcTemplate.queryForObject(
                any(String.class),
                any(RowMapper.class),
                eq("obra-uuid")
        )).thenReturn(new RdoExportWorksiteReader.Worksite(
                "Obra Norte",
                null
        ));

        assertThatThrownBy(() -> reader.read("obra-uuid"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode())
                            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(exception.getReason())
                            .contains("código CW ou código interno")
                            .contains("Nenhum conteúdo substituto foi inventado");
                });
    }
}
