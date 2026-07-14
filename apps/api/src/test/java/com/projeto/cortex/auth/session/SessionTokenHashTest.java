package com.projeto.cortex.auth.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SessionTokenHashTest {

    @Test
    void producesCanonicalSha256Hex() {
        assertThat(SessionTokenHash.sha256("synthetic-token-material"))
                .isEqualTo(
                        "884f173c9539215ce2720a4734431642"
                                + "b328e8bc49e21ae4ec9d17a1da106ece"
                );
    }

    @Test
    void comparesTheRawCandidateWithTheStoredDigest() {
        String stored = SessionTokenHash.sha256("expected-token-material");

        assertThat(SessionTokenHash.matches(
                "expected-token-material",
                stored
        )).isTrue();
        assertThat(SessionTokenHash.matches(
                "different-token-material",
                stored
        )).isFalse();
        assertThat(SessionTokenHash.matches("", stored)).isFalse();
        assertThat(SessionTokenHash.matches(
                "expected-token-material",
                "not-a-digest"
        )).isFalse();
    }

    @Test
    void rejectsEmptyMaterialBeforeHashing() {
        assertThatThrownBy(() -> SessionTokenHash.sha256("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
