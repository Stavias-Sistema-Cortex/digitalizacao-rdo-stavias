package com.projeto.cortex.mensagens;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class MessageRateLimitService {

    private static final long WINDOW_SECONDS = 60;
    private static final int PRUNE_THRESHOLD = 10_000;

    private final int uploadsPerMinute;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows =
            new ConcurrentHashMap<>();

    @Autowired
    public MessageRateLimitService(MessageAttachmentProperties properties) {
        this(properties, Clock.systemUTC());
    }

    MessageRateLimitService(
            MessageAttachmentProperties properties,
            Clock clock
    ) {
        this.uploadsPerMinute = Math.max(
                1,
                properties.getRateLimitUploadsPerMinute()
        );
        this.clock = clock;
    }

    public void checkUpload(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Usuário autenticado não encontrado."
            );
        }
        Instant now = clock.instant();
        AtomicBoolean rejected = new AtomicBoolean(false);
        windows.compute(userId.trim(), (ignored, current) -> {
            if (current == null || !now.isBefore(current.startedAt()
                    .plusSeconds(WINDOW_SECONDS))) {
                return new Window(now, 1);
            }
            if (current.uploads() >= uploadsPerMinute) {
                rejected.set(true);
                return current;
            }
            return new Window(current.startedAt(), current.uploads() + 1);
        });

        if (windows.size() > PRUNE_THRESHOLD) {
            Instant oldestActive = now.minusSeconds(WINDOW_SECONDS);
            windows.entrySet().removeIf(entry ->
                    entry.getValue().startedAt().isBefore(oldestActive)
            );
        }
        if (rejected.get()) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Limite de uploads por minuto excedido. Tente novamente em instantes."
            );
        }
    }

    private record Window(Instant startedAt, int uploads) {
    }
}
