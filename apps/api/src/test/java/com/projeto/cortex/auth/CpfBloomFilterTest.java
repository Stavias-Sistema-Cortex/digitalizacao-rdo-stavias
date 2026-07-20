package com.projeto.cortex.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.projeto.cortex.colaboradores.CpfHasher;
import java.util.List;
import org.junit.jupiter.api.Test;

class CpfBloomFilterTest {

    private static final List<String> CPFS_MEMBROS =
            List.of("11144477735", "52998224725");

    @Test
    void contemTodosOsInseridos() {
        CpfBloomFilter filtro = construir(CPFS_MEMBROS);

        for (String cpf : CPFS_MEMBROS) {
            assertTrue(
                    filtro.podeConter(CpfHasher.hashDeDigitos(cpf)),
                    "deveria conter o CPF inserido " + cpf
            );
        }
    }

    @Test
    void naoContemAusente() {
        CpfBloomFilter filtro = construir(CPFS_MEMBROS);

        assertFalse(
                filtro.podeConter(CpfHasher.hashDeDigitos("39053344705")),
                "não deveria conter um CPF ausente (sem falso positivo)"
        );
    }

    @Test
    void filtroVazioNaoContemNada() {
        CpfBloomFilter filtro = CpfBloomFilter.construir(List.of(), 0.001);

        assertFalse(
                filtro.podeConter(CpfHasher.hashDeDigitos("52998224725"))
        );
    }

    @Test
    void parametrosSaoDeterministicos() {
        CpfBloomFilter a = construir(CPFS_MEMBROS);
        CpfBloomFilter b = construir(CPFS_MEMBROS);

        assertEquals(a.getM(), b.getM());
        assertEquals(a.getK(), b.getK());
        assertEquals(a.getBitsBase64(), b.getBitsBase64());
    }

    /** Imprime parâmetros do filtro para conferência de paridade com o cliente. */
    @Test
    void imprimeParametrosParaParidade() {
        CpfBloomFilter filtro = construir(CPFS_MEMBROS);

        System.out.println("PARIDADE_M=" + filtro.getM());
        System.out.println("PARIDADE_K=" + filtro.getK());
        System.out.println("PARIDADE_BITS=" + filtro.getBitsBase64());
        for (String cpf : CPFS_MEMBROS) {
            System.out.println(
                    "PARIDADE_HASH " + cpf + " "
                            + CpfHasher.hashDeDigitos(cpf)
            );
        }
    }

    private static CpfBloomFilter construir(List<String> cpfs) {
        return CpfBloomFilter.construir(
                cpfs.stream().map(CpfHasher::hashDeDigitos).toList(),
                0.001
        );
    }
}
