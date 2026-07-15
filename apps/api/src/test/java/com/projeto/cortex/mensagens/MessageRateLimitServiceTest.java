package com.projeto.cortex.mensagens;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageRateLimitServiceTest {

    @Test
    void limitsUploadsPerAuthenticatedUserAndRecoversNextWindow() {
        MessageAttachmentProperties properties = new MessageAttachmentProperties();
        properties.setRateLimitUploadsPerMinute(2);
        MutableClock clock = new MutableClock(
                Instant.parse("2026-07-15T13:00:00Z")
        );
        MessageRateLimitService limiter =
                new MessageRateLimitService(properties, clock);

        limiter.checkUpload("user-1");
        limiter.checkUpload("user-1");

        assertThatThrownBy(() -> limiter.checkUpload("user-1"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                );
        assertThatCode(() -> limiter.checkUpload("user-2"))
                .doesNotThrowAnyException();

        clock.advanceSeconds(61);
        assertThatCode(() -> limiter.checkUpload("user-1"))
                .doesNotThrowAnyException();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
