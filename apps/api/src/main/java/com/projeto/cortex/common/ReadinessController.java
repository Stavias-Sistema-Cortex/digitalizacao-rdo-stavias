package com.projeto.cortex.common;

import com.projeto.cortex.auth.offline.OfflineGrantPublicKeyFingerprint;
import com.projeto.cortex.storage.ObjectStorageReadiness;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!postgresql-activation")
public final class ReadinessController {

    private final RuntimeReadiness readiness;
    private final RuntimeRevision revision;
    private final RuntimeInstanceFingerprint runtimeInstanceFingerprint;
    private final OfflineGrantPublicKeyFingerprint offlineKeyFingerprint;
    private final ObjectStorageReadiness objectStorage;

    /**
     * A sonda de armazenamento grava, lê e apaga um objeto de verdade — é o que
     * a torna uma prova, e é o que a torna cara. Esta rota é anônima por
     * necessidade (o health check da hospedagem não manda cabeçalho), então
     * cada chamada de fora era um round trip completo no armazenamento pago,
     * cobrado de nós e disparado por qualquer pessoa.
     *
     * <p>A janela existe para separar as duas coisas: o health check bate a
     * cada trinta segundos ou mais e continua vendo a prova de verdade; uma
     * enxurrada anônima passa a custar o que uma leitura de campo em memória
     * custa. Não é cache do <em>resultado</em> — o status continua sendo lido a
     * cada resposta —, é intervalo mínimo entre duas provas.
     */
    private final long probeIntervalMillis;
    private final LongSupplier clock;
    private final AtomicLong lastProbeAt = new AtomicLong(Long.MIN_VALUE);

    @Autowired
    public ReadinessController(
            RuntimeReadiness readiness,
            RuntimeRevision revision,
            RuntimeInstanceFingerprint runtimeInstanceFingerprint,
            OfflineGrantPublicKeyFingerprint offlineKeyFingerprint,
            ObjectStorageReadiness objectStorage,
            @Value("${cortex.readiness.object-storage-probe-interval-ms:30000}")
            long probeIntervalMillis
    ) {
        this(
                readiness,
                revision,
                runtimeInstanceFingerprint,
                offlineKeyFingerprint,
                objectStorage,
                probeIntervalMillis,
                System::currentTimeMillis
        );
    }

    ReadinessController(
            RuntimeReadiness readiness,
            RuntimeRevision revision,
            RuntimeInstanceFingerprint runtimeInstanceFingerprint,
            OfflineGrantPublicKeyFingerprint offlineKeyFingerprint,
            ObjectStorageReadiness objectStorage,
            long probeIntervalMillis,
            LongSupplier clock
    ) {
        this.readiness = readiness;
        this.revision = revision;
        this.runtimeInstanceFingerprint = runtimeInstanceFingerprint;
        this.offlineKeyFingerprint = offlineKeyFingerprint;
        this.objectStorage = objectStorage;
        this.probeIntervalMillis = probeIntervalMillis;
        this.clock = clock;
    }

    @GetMapping("/api/readiness")
    public Map<String, String> readiness() {
        Map<String, String> verifiedEvidence =
                readiness.verifiedPublicEvidence();
        probeObjectStorageAtMostOncePerInterval();
        Map<String, String> response = new LinkedHashMap<>();
        response.put("status", readiness.readinessStatus());
        response.put("service", "cortex-api");
        response.put("revision", revision.value());
        response.put(
                "runtimeInstanceSha256",
                runtimeInstanceFingerprint.value()
        );
        response.put(
                "offlineGrantPublicKeySha256",
                offlineKeyFingerprint.value()
        );
        response.put("objectStorage", objectStorage.readinessStatus());
        response.put("timestamp", Instant.now().toString());
        verifiedEvidence.forEach((key, value) -> {
            if (response.putIfAbsent(key, value) != null) {
                throw new IllegalStateException(
                        "Evidência pública de readiness conflitante."
                );
            }
        });
        return Map.copyOf(response);
    }

    /**
     * Falha continua sendo falha. Só a sonda bem-sucedida marca a janela: se a
     * verificação lançar, o relógio não avança e a próxima chamada tenta de
     * novo — senão um armazenamento quebrado ficaria trinta segundos passando
     * por são, que é o oposto do que este endpoint existe para dizer.
     */
    private void probeObjectStorageAtMostOncePerInterval() {
        long now = clock.getAsLong();
        long previous = lastProbeAt.get();
        if (previous != Long.MIN_VALUE
                && now - previous < probeIntervalMillis) {
            return;
        }
        if (!lastProbeAt.compareAndSet(previous, now)) {
            // Outra requisição assumiu a sonda desta janela. Deixar passar é o
            // certo: a prova está sendo feita, e fazê-la duas vezes em paralelo
            // é justamente o gasto que se quer evitar.
            return;
        }
        try {
            objectStorage.verifyObjectStorageReadiness();
        } catch (RuntimeException exception) {
            lastProbeAt.set(previous);
            throw exception;
        }
    }
}
