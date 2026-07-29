package com.projeto.cortex.storage;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Performs a cached, reversible object-storage round trip for readiness.
 */
@Component
public final class ObjectStorageReadinessProbe
        implements ObjectStorageReadiness {

    private static final Duration DEFAULT_MAX_AGE = Duration.ofMinutes(1);
    private static final Duration DEFAULT_FAILURE_BACKOFF =
            Duration.ofSeconds(5);
    private static final String MEDIA_TYPE = "application/octet-stream";
    private static final String FAILURE_MESSAGE =
            "Object storage indisponível para readiness.";

    private final ObjectStorage storage;
    private final Clock clock;
    private final Duration maxAge;
    private final Duration failureBackoff;
    private final String key;
    private final byte[] payload;
    private final Object stateLock = new Object();
    private Instant lastSuccess;
    private Instant retryAfterFailure;
    private IllegalStateException lastFailure;
    private boolean probeInFlight;

    @Autowired
    public ObjectStorageReadinessProbe(ObjectStorage storage) {
        this(
                storage,
                Clock.systemUTC(),
                DEFAULT_MAX_AGE,
                DEFAULT_FAILURE_BACKOFF,
                UUID.randomUUID().toString()
        );
    }

    ObjectStorageReadinessProbe(
            ObjectStorage storage,
            Clock clock,
            Duration maxAge,
            String probeId
    ) {
        this(
                storage,
                clock,
                maxAge,
                DEFAULT_FAILURE_BACKOFF,
                probeId
        );
    }

    ObjectStorageReadinessProbe(
            ObjectStorage storage,
            Clock clock,
            Duration maxAge,
            Duration failureBackoff,
            String probeId
    ) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maxAge = requirePositive(maxAge);
        this.failureBackoff = requirePositive(failureBackoff);
        String canonicalProbeId = requireCanonicalProbeId(probeId);
        this.key = "readiness/" + canonicalProbeId;
        this.payload = ("cortex-object-storage-readiness-v1:"
                + canonicalProbeId).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    @Override
    public void verifyObjectStorageReadiness() {
        Instant now = clock.instant();
        synchronized (stateLock) {
            if (lastSuccess != null
                    && !now.isBefore(lastSuccess)
                    && now.isBefore(lastSuccess.plus(maxAge))) {
                return;
            }
            if (probeInFlight) {
                throw readinessFailure(null);
            }
            if (lastFailure != null && now.isBefore(retryAfterFailure)) {
                throw readinessFailure(lastFailure);
            }
            probeInFlight = true;
        }

        IllegalStateException failure;
        try {
            failure = performRoundTrip();
        } catch (Error error) {
            synchronized (stateLock) {
                probeInFlight = false;
            }
            throw error;
        }
        Instant completedAt = clock.instant();
        synchronized (stateLock) {
            probeInFlight = false;
            if (failure == null) {
                lastSuccess = completedAt;
                retryAfterFailure = null;
                lastFailure = null;
            } else {
                lastSuccess = null;
                retryAfterFailure = completedAt.plus(failureBackoff);
                lastFailure = failure;
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private IllegalStateException performRoundTrip() {
        IllegalStateException failure = null;
        try {
            storage.put(
                    key,
                    new ByteArrayInputStream(payload),
                    payload.length,
                    MEDIA_TYPE
            );
            verifyStoredPayload();
        } catch (Exception exception) {
            failure = readinessFailure(exception);
        }

        try {
            storage.delete(key);
        } catch (Exception exception) {
            if (failure == null) {
                failure = readinessFailure(exception);
            } else {
                failure.addSuppressed(exception);
            }
        }

        return failure;
    }

    private static IllegalStateException readinessFailure(
            Throwable cause
    ) {
        return cause == null
                ? new IllegalStateException(FAILURE_MESSAGE)
                : new IllegalStateException(FAILURE_MESSAGE, cause);
    }

    private void verifyStoredPayload() throws Exception {
        try (ObjectStorageContent stored = storage.get(key)) {
            byte[] actual = stored.inputStream().readNBytes(
                    payload.length + 1
            );
            if (stored.length() != payload.length
                    || !MEDIA_TYPE.equals(stored.mediaType())
                    || !Arrays.equals(payload, actual)) {
                throw new IllegalStateException(
                        "O round trip do object storage divergiu."
                );
            }
        }
    }

    private static Duration requirePositive(Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(
                    "A validade do readiness de storage deve ser positiva."
            );
        }
        return value;
    }

    private static String requireCanonicalProbeId(String value) {
        if (value == null
                || !value.matches(
                        "[a-f0-9]{8}-[a-f0-9]{4}-[1-5][a-f0-9]{3}-"
                                + "[89ab][a-f0-9]{3}-[a-f0-9]{12}"
                )) {
            throw new IllegalArgumentException(
                    "Identificador de readiness de storage inválido."
            );
        }
        return value;
    }
}
