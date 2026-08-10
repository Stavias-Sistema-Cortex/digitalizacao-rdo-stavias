package com.projeto.cortex.rdos;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

/**
 * A frente que trabalha à noite precisa conseguir apontar a própria jornada.
 *
 * <p>A duração da alocação era a diferença crua entre dois relógios, e ela é
 * negativa em toda jornada noturna: entrar às 22:00 e sair às 06:00 dava -960
 * minutos e caía na recusa "horaFim deve ser maior que horaInicio". Boa parte da
 * obra em rodovia acontece exatamente aí, com a pista interditada de madrugada.
 *
 * <p>A checagem de sobreposição tinha o problema espelhado, e mais perigoso:
 * o par {@code hora_inicio < ? AND hora_fim > ?} pressupõe fim depois do início
 * no mesmo mostrador, então ela simplesmente parava de encontrar conflito
 * nenhum entre turnos noturnos. A guarda continuava no código sem nunca acusar
 * nada — e ninguém procura o que não reclama.
 *
 * <p>Os dois métodos são privados por serem detalhe de como a alocação é
 * montada; o que se prende aqui é a aritmética do relógio, que é onde o defeito
 * morava.
 */
class TurnoDaNoiteAtravessaODiaTest {

    private final RdoOperationalDetailService servico =
            new RdoOperationalDetailService(null, null);

    private long duracao(String inicio, String fim) throws Exception {
        Method metodo = RdoOperationalDetailService.class.getDeclaredMethod(
                "duracaoEmMinutos", intervaloClass()
        );
        metodo.setAccessible(true);
        return (long) metodo.invoke(servico, intervalo(inicio, fim));
    }

    private boolean sobrepoem(
            String inicioA, String fimA, String inicioB, String fimB
    ) throws Exception {
        Method metodo = RdoOperationalDetailService.class.getDeclaredMethod(
                "seSobrepoemNoRelogio", intervaloClass(), intervaloClass()
        );
        metodo.setAccessible(true);
        return (boolean) metodo.invoke(
                servico, intervalo(inicioA, fimA), intervalo(inicioB, fimB)
        );
    }

    private static Class<?> intervaloClass() throws Exception {
        return Class.forName(
                "com.projeto.cortex.rdos.RdoOperationalDetailService$IntervaloAlocacao"
        );
    }

    private static Object intervalo(String inicio, String fim) throws Exception {
        var construtor = intervaloClass().getDeclaredConstructors()[0];
        construtor.setAccessible(true);
        return construtor.newInstance(
                LocalTime.parse(inicio), LocalTime.parse(fim), 0
        );
    }

    @Test
    void medeAJornadaQueViraODia() throws Exception {
        assertThat(duracao("22:00", "06:00")).isEqualTo(480);
    }

    @Test
    void continuaMedindoAJornadaDiurna() throws Exception {
        assertThat(duracao("07:00", "17:00")).isEqualTo(600);
    }

    /** Meia-noite exata continua sendo travessia, não jornada de zero minuto. */
    @Test
    void medeAJornadaQueTerminaNaMeiaNoite() throws Exception {
        assertThat(duracao("18:00", "00:00")).isEqualTo(360);
    }

    @Test
    void encontraSobreposicaoEntreDoisTurnosDaNoite() throws Exception {
        assertThat(sobrepoem("22:00", "06:00", "23:00", "07:00")).isTrue();
    }

    /*
     * O par que o SQL antigo nunca ia achar: um atravessa, o outro não, e o
     * encontro acontece do outro lado da meia-noite.
     */
    @Test
    void encontraATardeQueInvadeAMadrugadaDoTurnoNoturno() throws Exception {
        assertThat(sobrepoem("22:00", "06:00", "05:00", "09:00")).isTrue();
    }

    @Test
    void deixaPassarTurnosQueApenasSeEncostam() throws Exception {
        assertThat(sobrepoem("22:00", "06:00", "06:00", "14:00")).isFalse();
    }

    @Test
    void deixaPassarTurnosSeparadosNoMesmoDia() throws Exception {
        assertThat(sobrepoem("07:00", "11:00", "13:00", "17:00")).isFalse();
    }

    @Test
    void continuaEncontrandoSobreposicaoDiurna() throws Exception {
        assertThat(sobrepoem("07:00", "17:00", "16:00", "20:00")).isTrue();
    }
}
