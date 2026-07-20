package com.projeto.cortex.auth;

import java.util.Base64;
import java.util.Collection;

/**
 * Filtro de Bloom sobre os hashes SHA-256 dos CPFs dos colaboradores ativos.
 *
 * <p>Permite que o cliente valide o login offline sem expor a lista de CPFs:
 * é possível testar a associação de um CPF, mas não enumerar os existentes.
 * O esquema ({@code sha256-double/v1}) precisa ser replicado no frontend:
 *
 * <ul>
 *   <li>elemento = SHA-256(dígitos do CPF) em hex (= colaborador.cpf_hash);</li>
 *   <li>h1 = primeiros 4 bytes do hash (big-endian, sem sinal);</li>
 *   <li>h2 = próximos 4 bytes (big-endian) com o bit menos significativo ligado;</li>
 *   <li>para i em [0, k): índice = (h1 + i*h2) mod m; liga/testa o bit;</li>
 *   <li>bits gravados LSB-first: byte[idx/8] |= 1 &lt;&lt; (idx % 8).</li>
 * </ul>
 */
public final class CpfBloomFilter {

    public static final String ALGORITMO = "sha256-double/v1";
    private static final int MIN_BITS = 1024;

    private final int m;
    private final int k;
    private final byte[] bits;

    private CpfBloomFilter(int m, int k, byte[] bits) {
        this.m = m;
        this.k = k;
        this.bits = bits;
    }

    public static CpfBloomFilter construir(
            Collection<String> cpfHashesHex,
            double falsePositiveAlvo
    ) {
        int n = Math.max(cpfHashesHex.size(), 1);
        double p = (falsePositiveAlvo <= 0 || falsePositiveAlvo >= 1)
                ? 0.001
                : falsePositiveAlvo;

        double ln2 = Math.log(2);
        int m = (int) Math.ceil(-(n * Math.log(p)) / (ln2 * ln2));
        m = Math.max(m, MIN_BITS);
        m = ((m + 7) / 8) * 8;
        int k = Math.max(1, (int) Math.round(-Math.log(p) / ln2));

        CpfBloomFilter filtro = new CpfBloomFilter(m, k, new byte[m / 8]);

        for (String hashHex : cpfHashesHex) {
            if (hashHex != null && hashHex.length() >= 16) {
                filtro.aplicar(hashHex, true);
            }
        }

        return filtro;
    }

    public boolean podeConter(String cpfHashHex) {
        if (cpfHashHex == null || cpfHashHex.length() < 16) {
            return false;
        }
        return aplicar(cpfHashHex, false);
    }

    /** Liga os bits (set=true) ou verifica se todos estão ligados (set=false). */
    private boolean aplicar(String cpfHashHex, boolean set) {
        byte[] hash = hexParaBytes(cpfHashHex);
        long h1 = uint32(hash, 0);
        long h2 = uint32(hash, 4) | 1L;

        for (int i = 0; i < k; i++) {
            int idx = (int) Math.floorMod(h1 + (long) i * h2, (long) m);
            int byteIndex = idx >>> 3;
            int mask = 1 << (idx & 7);

            if (set) {
                bits[byteIndex] |= (byte) mask;
            } else if ((bits[byteIndex] & mask) == 0) {
                return false;
            }
        }

        return true;
    }

    private static long uint32(byte[] bytes, int offset) {
        return ((long) (bytes[offset] & 0xFF) << 24)
                | ((long) (bytes[offset + 1] & 0xFF) << 16)
                | ((long) (bytes[offset + 2] & 0xFF) << 8)
                | (bytes[offset + 3] & 0xFF);
    }

    private static byte[] hexParaBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(
                    hex.substring(i * 2, i * 2 + 2), 16
            );
        }
        return out;
    }

    public int getM() {
        return m;
    }

    public int getK() {
        return k;
    }

    public String getBitsBase64() {
        return Base64.getEncoder().encodeToString(bits);
    }
}
