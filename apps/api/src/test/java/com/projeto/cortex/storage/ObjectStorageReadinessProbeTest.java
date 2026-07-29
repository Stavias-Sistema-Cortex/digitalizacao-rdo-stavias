package com.projeto.cortex.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ObjectStorageReadinessProbeTest {

    @TempDir
    Path root;

    @Test
    void verifiesPutGetAndDeleteWithoutLeavingAProbeObject() throws Exception {
        CountingObjectStorage storage = new CountingObjectStorage(
                new LocalObjectStorage(root)
        );
        ObjectStorageReadinessProbe probe = new ObjectStorageReadinessProbe(
                storage,
                Clock.fixed(Instant.parse("2026-07-29T12:00:00Z"), ZoneOffset.UTC),
                Duration.ofMinutes(1),
                "6d7cad95-e9ba-4f17-a94d-4bd85750174b"
        );

        probe.verifyObjectStorageReadiness();
        probe.verifyObjectStorageReadiness();

        assertThat(probe.readinessStatus()).isEqualTo("READY");
        assertThat(storage.puts).isEqualTo(1);
        assertThat(storage.gets).isEqualTo(1);
        assertThat(storage.deletes).isEqualTo(1);
        assertThat(regularFiles()).isZero();
    }

    @Test
    void failsClosedAndStillDeletesWhenTheRoundTripBodyChanges()
            throws Exception {
        ObjectStorage corruptingStorage = new CorruptingObjectStorage(
                new LocalObjectStorage(root)
        );
        ObjectStorageReadinessProbe probe = new ObjectStorageReadinessProbe(
                corruptingStorage,
                Clock.systemUTC(),
                Duration.ofMinutes(1),
                "25a05af2-6321-430f-8334-89bce4fe7b35"
        );

        assertThatThrownBy(probe::verifyObjectStorageReadiness)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "Object storage indisponível para readiness."
                );
        assertThat(regularFiles()).isZero();
    }

    @Test
    void cachesAnOutageInsteadOfRepeatingTheRoundTripForEveryRequest() {
        AlwaysFailingObjectStorage storage = new AlwaysFailingObjectStorage();
        ObjectStorageReadinessProbe probe = new ObjectStorageReadinessProbe(
                storage,
                Clock.fixed(Instant.parse("2026-07-29T12:00:00Z"), ZoneOffset.UTC),
                Duration.ofMinutes(1),
                "7554f83b-fd82-49ad-b56c-c800166873fe"
        );

        assertThatThrownBy(probe::verifyObjectStorageReadiness)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "Object storage indisponível para readiness."
                );
        assertThatThrownBy(probe::verifyObjectStorageReadiness)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "Object storage indisponível para readiness."
                );

        assertThat(storage.puts.get()).isEqualTo(1);
        assertThat(storage.deletes.get()).isEqualTo(1);
    }

    @Test
    void retriesAnOutageAfterTheShortFailureBackoffExpires() {
        AlwaysFailingObjectStorage storage = new AlwaysFailingObjectStorage();
        MutableClock clock = new MutableClock(
                Instant.parse("2026-07-29T12:00:00Z")
        );
        ObjectStorageReadinessProbe probe = new ObjectStorageReadinessProbe(
                storage,
                clock,
                Duration.ofMinutes(1),
                Duration.ofSeconds(5),
                "307a17ec-4293-425d-b498-2996f59bd59b"
        );

        assertThatThrownBy(probe::verifyObjectStorageReadiness)
                .isInstanceOf(IllegalStateException.class);
        clock.advance(Duration.ofSeconds(4));
        assertThatThrownBy(probe::verifyObjectStorageReadiness)
                .isInstanceOf(IllegalStateException.class);
        assertThat(storage.puts.get()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(1));
        assertThatThrownBy(probe::verifyObjectStorageReadiness)
                .isInstanceOf(IllegalStateException.class);
        assertThat(storage.puts.get()).isEqualTo(2);
        assertThat(storage.deletes.get()).isEqualTo(2);
    }

    @Test
    void concurrentRequestFailsClosedWithoutWaitingForTheActiveRoundTrip()
            throws Exception {
        BlockingObjectStorage storage = new BlockingObjectStorage();
        ObjectStorageReadinessProbe probe = new ObjectStorageReadinessProbe(
                storage,
                Clock.systemUTC(),
                Duration.ofMinutes(1),
                "2600bb29-6aa4-4bbc-af50-5ed0d5154306"
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var activeProbe = executor.submit(
                    probe::verifyObjectStorageReadiness
            );
            assertThat(storage.putStarted.await(1, TimeUnit.SECONDS))
                    .isTrue();

            var concurrentProbe = executor.submit(
                    probe::verifyObjectStorageReadiness
            );

            assertThatThrownBy(() -> concurrentProbe.get(
                    500,
                    TimeUnit.MILLISECONDS
            )).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage(
                            "Object storage indisponível para readiness."
                    );
            assertThat(storage.puts.get()).isEqualTo(1);

            storage.allowPut.countDown();
            activeProbe.get(1, TimeUnit.SECONDS);
            assertThat(storage.puts.get()).isEqualTo(1);
            assertThat(storage.deletes.get()).isEqualTo(1);
            assertThat(storage.payload).isNull();
        } finally {
            storage.allowPut.countDown();
            executor.shutdownNow();
        }
    }

    private long regularFiles() throws Exception {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    private static final class CountingObjectStorage
            implements ObjectStorage {

        private final ObjectStorage delegate;
        private int puts;
        private int gets;
        private int deletes;

        private CountingObjectStorage(ObjectStorage delegate) {
            this.delegate = delegate;
        }

        @Override
        public String backend() {
            return delegate.backend();
        }

        @Override
        public void put(
                String key,
                InputStream inputStream,
                long length,
                String mediaType
        ) {
            puts++;
            delegate.put(key, inputStream, length, mediaType);
        }

        @Override
        public ObjectStorageContent get(String key) {
            gets++;
            return delegate.get(key);
        }

        @Override
        public void delete(String key) {
            deletes++;
            delegate.delete(key);
        }
    }

    private static final class CorruptingObjectStorage
            implements ObjectStorage {

        private final ObjectStorage delegate;

        private CorruptingObjectStorage(ObjectStorage delegate) {
            this.delegate = delegate;
        }

        @Override
        public String backend() {
            return delegate.backend();
        }

        @Override
        public void put(
                String key,
                InputStream inputStream,
                long length,
                String mediaType
        ) {
            delegate.put(key, inputStream, length, mediaType);
        }

        @Override
        public ObjectStorageContent get(String key) {
            byte[] changed = "corrompido".getBytes(StandardCharsets.UTF_8);
            return new ObjectStorageContent(
                    new ByteArrayInputStream(changed),
                    changed.length,
                    "application/octet-stream"
            );
        }

        @Override
        public void delete(String key) {
            delegate.delete(key);
        }
    }

    private static final class AlwaysFailingObjectStorage
            implements ObjectStorage {

        private final AtomicInteger puts = new AtomicInteger();
        private final AtomicInteger deletes = new AtomicInteger();

        @Override
        public String backend() {
            return "TEST";
        }

        @Override
        public void put(
                String key,
                InputStream inputStream,
                long length,
                String mediaType
        ) {
            puts.incrementAndGet();
            throw new ObjectStorageException("outage");
        }

        @Override
        public ObjectStorageContent get(String key) {
            throw new AssertionError("get não deve ocorrer após falha no put");
        }

        @Override
        public void delete(String key) {
            deletes.incrementAndGet();
        }
    }

    private static final class BlockingObjectStorage
            implements ObjectStorage {

        private final AtomicInteger puts = new AtomicInteger();
        private final AtomicInteger deletes = new AtomicInteger();
        private final CountDownLatch putStarted = new CountDownLatch(1);
        private final CountDownLatch allowPut = new CountDownLatch(1);
        private volatile byte[] payload;

        @Override
        public String backend() {
            return "TEST";
        }

        @Override
        public void put(
                String key,
                InputStream inputStream,
                long length,
                String mediaType
        ) {
            puts.incrementAndGet();
            putStarted.countDown();
            try {
                if (!allowPut.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test timed out");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            try {
                payload = inputStream.readAllBytes();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public ObjectStorageContent get(String key) {
            byte[] current = payload;
            return new ObjectStorageContent(
                    new ByteArrayInputStream(current),
                    current.length,
                    "application/octet-stream"
            );
        }

        @Override
        public void delete(String key) {
            deletes.incrementAndGet();
            payload = null;
        }
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("only UTC is supported");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
