package com.projeto.cortex.auth.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class ClientInstanceProofTest {

    @Test
    void acceptsOneCanonical32ByteValueAndRetainsOnlyItsHash() {
        String raw = "A".repeat(43);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ClientInstanceProof.HEADER, raw);

        ClientInstanceProof proof = ClientInstanceProof.from(request)
                .orElseThrow();

        assertThat(proof.hash()).isEqualTo(SessionTokenHash.sha256(raw));
        assertThat(proof.toString()).doesNotContain(raw)
                .contains("REDACTED");
    }

    @Test
    void rejectsMissingMultiplePaddedAndNonCanonicalValues() {
        String canonical = "A".repeat(43);

        MockHttpServletRequest missing = new MockHttpServletRequest();
        assertThat(ClientInstanceProof.from(missing)).isEmpty();

        MockHttpServletRequest multiple = new MockHttpServletRequest();
        multiple.addHeader(ClientInstanceProof.HEADER, canonical);
        multiple.addHeader(ClientInstanceProof.HEADER, canonical);
        assertThat(ClientInstanceProof.from(multiple)).isEmpty();

        assertThat(ClientInstanceProof.fromRawValue(canonical + "=")).isEmpty();
        assertThat(ClientInstanceProof.fromRawValue("A".repeat(42))).isEmpty();
        assertThat(ClientInstanceProof.fromRawValue(
                "A".repeat(42) + "B"
        )).isEmpty();
    }

    @Test
    void requireFailsClosedWithUnauthorizedBeforeAnyCallerCanResolveIdentity() {
        ResponseStatusException exception = catchThrowableOfType(
                () -> ClientInstanceProof.require(new MockHttpServletRequest()),
                ResponseStatusException.class
        );

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
