package com.projeto.cortex.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.projeto.cortex.auth.offline.OfflineGrantPublicKeyFingerprint;
import com.projeto.cortex.storage.ObjectStorageReadiness;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * O readiness é anônimo por necessidade e caro por construção.
 *
 * <p>Ele precisa continuar anônimo: o health check da hospedagem não manda
 * cabeçalho nenhum, e fechá-lo derrubaria o próprio mecanismo que mantém a
 * instância de pé. Mas ele gravava, lia e apagava um objeto de verdade no
 * armazenamento pago <em>a cada chamada</em> — então qualquer pessoa, sem
 * login, podia fazer o Córtex gastar armazenamento em nome dela, e numa
 * instância de plano free isso é derrubar a obra inteira com um laço de
 * {@code curl}.
 *
 * <p>O que estes testes guardam é a distinção entre as duas leituras: o health
 * check, que bate espaçado, continua vendo a prova de verdade; a enxurrada
 * anônima passa a custar quase nada. E o que <em>não</em> pode acontecer:
 * armazenamento quebrado passando por são porque a janela ainda não venceu.
 */
class ReadinessProbeThrottleTest {

    private static final String REVISION = "0123456789abcdef0123456789abcdef01234567";

    private final AtomicInteger sondas = new AtomicInteger();
    private final AtomicLong agora = new AtomicLong(1_000_000L);
    private final AtomicLong falharApos = new AtomicLong(Long.MAX_VALUE);

    private final ObjectStorageReadiness objectStorage =
            new ObjectStorageReadiness() {

                @Override
                public void verifyObjectStorageReadiness() {
                    if (sondas.incrementAndGet() > falharApos.get()) {
                        throw new IllegalStateException(
                                "Armazenamento indisponível."
                        );
                    }
                }

                @Override
                public String readinessStatus() {
                    return "READY";
                }
            };

    private ReadinessController controller(long intervalo) {
        return new ReadinessController(
                new RuntimeReadiness() {

                    @Override
                    public void verifyRuntimeReadiness() {
                        // Pronto por construção: o que este teste mede é a
                        // sonda do armazenamento, não a do banco.
                    }

                    @Override
                    public Map<String, String> verifiedPublicEvidence() {
                        return Map.of();
                    }
                },
                new RuntimeRevision(REVISION),
                new RuntimeInstanceFingerprint("instancia-de-teste"),
                () -> "impressao-de-teste",
                objectStorage,
                intervalo,
                agora::get
        );
    }

    @Test
    void sondaOArmazenamentoUmaVezPorJanelaAindaQueChamadoEmRajada() {
        ReadinessController controller = controller(30_000L);

        for (int i = 0; i < 50; i++) {
            assertThat(controller.readiness()).containsEntry("status", "READY");
        }

        assertThat(sondas.get()).isEqualTo(1);
    }

    /** Passada a janela, a prova volta a ser feita de verdade. */
    @Test
    void voltaASondarQuandoAJanelaVence() {
        ReadinessController controller = controller(30_000L);

        controller.readiness();
        assertThat(sondas.get()).isEqualTo(1);

        agora.addAndGet(29_999L);
        controller.readiness();
        assertThat(sondas.get()).isEqualTo(1);

        agora.addAndGet(1L);
        controller.readiness();
        assertThat(sondas.get()).isEqualTo(2);
    }

    /**
     * Armazenamento quebrado não pode ficar meio minuto passando por são: a
     * janela só avança quando a prova deu certo, senão o endereço que existe
     * para dizer "estou pronto" mentiria justamente quando não está.
     */
    @Test
    void naoDeixaUmaFalhaDoArmazenamentoSerEscondidaPelaJanela() {
        ReadinessController controller = controller(30_000L);
        falharApos.set(0L);

        assertThatThrownBy(controller::readiness)
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(controller::readiness)
                .isInstanceOf(IllegalStateException.class);

        assertThat(sondas.get()).isEqualTo(2);
    }

    /** Intervalo zero é o comportamento antigo, para quem quiser de volta. */
    @Test
    void aceitaIntervaloZeroComoSondarSempre() {
        ReadinessController controller = controller(0L);

        controller.readiness();
        controller.readiness();
        controller.readiness();

        assertThat(sondas.get()).isEqualTo(3);
    }
}
