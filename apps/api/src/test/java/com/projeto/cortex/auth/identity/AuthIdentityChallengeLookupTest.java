package com.projeto.cortex.auth.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class AuthIdentityChallengeLookupTest {

    @Test
    @SuppressWarnings("unchecked")
    void validAndInvalidIdentifiersEachExecuteOneFixedShapeQuery() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CpfLookupDigestService digests = mock(
                CpfLookupDigestService.class
        );
        AuthChallengeLookupMaterial material =
                new AuthChallengeLookupMaterial(
                        List.of(new CpfLookupDigest(
                                "current",
                                "a".repeat(64)
                        )),
                        "b".repeat(64)
                );
        when(digests.challengeLookup(anyString())).thenReturn(material);
        when(jdbc.query(
                anyString(),
                any(RowMapper.class),
                any(), any(), any(), any(), any()
        )).thenReturn(List.of());
        AuthIdentityChallengeLookup lookup =
                new AuthIdentityChallengeLookup(jdbc, digests);

        assertThat(lookup.find("11144477735")).isEmpty();
        assertThat(lookup.find("unsupported-identifier")).isEmpty();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).query(
                sql.capture(),
                any(RowMapper.class),
                any(), any(), any(), any(), any()
        );
        assertThat(sql.getAllValues()).allMatch(query ->
                query.contains("cpf_lookup_key_id")
                        && query.contains("cpf_lookup_hmac")
                        && query.contains("cpf_hash")
                        && query.contains("LIMIT 3")
        );
        verify(digests, times(2)).challengeLookup(anyString());
    }
}
