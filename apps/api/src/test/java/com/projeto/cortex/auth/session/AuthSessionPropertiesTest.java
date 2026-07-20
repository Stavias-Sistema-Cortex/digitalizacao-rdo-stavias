package com.projeto.cortex.auth.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AuthSessionPropertiesTest {

    @Test
    void acceptsBoundedProductionTtl() {
        assertThat(new AuthSessionProperties(43_200).ttlSeconds())
                .isEqualTo(43_200);
    }

    @Test
    void rejectsUnreasonablyShortOrLongSessions() {
        assertThatThrownBy(() -> new AuthSessionProperties(299))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AuthSessionProperties(2_592_001))
                .isInstanceOf(IllegalStateException.class);
    }
}
