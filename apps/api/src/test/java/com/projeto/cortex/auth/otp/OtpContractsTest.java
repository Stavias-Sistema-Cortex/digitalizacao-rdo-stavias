package com.projeto.cortex.auth.otp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import org.junit.jupiter.api.Test;

class OtpContractsTest {

    @Test
    void requestUsesGenericIdentifierWithoutCpfField() {
        assertThat(OtpChallengeRequest.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("identifier");
    }

    @Test
    void verificationRequestContainsOnlyTheOneTimeCode() {
        assertThat(OtpVerifyRequest.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("code");
    }

    @Test
    void publicResponseIsGenericAndContainsNoIdentityHint() {
        OtpChallengeResponse first = OtpChallengeResponse.generic(
                "00000000-0000-4000-8000-000000000001",
                600
        );
        OtpChallengeResponse second = OtpChallengeResponse.generic(
                "00000000-0000-4000-8000-000000000002",
                600
        );

        assertThat(first.challengeId()).isNotEqualTo(second.challengeId());
        assertThat(first.expiresInSeconds())
                .isEqualTo(second.expiresInSeconds());
        assertThat(first.message()).isEqualTo(second.message())
                .isEqualTo(
                        "Se os dados estiverem aptos, enviaremos um código para o e-mail cadastrado."
                );
        assertThat(OtpChallengeResponse.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly(
                        "challengeId",
                        "expiresInSeconds",
                        "message"
                );
    }

    @Test
    void publicResponseRejectsNonCanonicalChallengeIds() {
        assertThatThrownBy(() -> OtpChallengeResponse.generic(
                "challenge-1",
                600
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("challenge-1");
    }
}
