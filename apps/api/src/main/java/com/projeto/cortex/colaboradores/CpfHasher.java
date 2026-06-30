package com.projeto.cortex.colaboradores;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Hashing e normalização de CPF compartilhados pela importação da Academy e
 * pela autenticação. O hash é SHA-256 sobre os 11 dígitos (igual ao gravado
 * em {@code colaborador.cpf_hash}), o que permite validar o login por CPF.
 */
public final class CpfHasher {

    private CpfHasher() {
    }

    public static String normalizar(String cpf) {
        if (cpf == null) {
            return null;
        }

        String apenasNumeros = cpf.replaceAll("\\D", "");
        return apenasNumeros.isBlank() ? null : apenasNumeros;
    }

    public static String hashDeDigitos(String cpfNormalizado) {
        if (cpfNormalizado == null) {
            return null;
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    cpfNormalizado.getBytes(StandardCharsets.UTF_8)
            );
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "Algoritmo SHA-256 indisponível.", exception
            );
        }
    }

    public static String mascarar(String cpfNormalizado) {
        if (cpfNormalizado == null || cpfNormalizado.length() != 11) {
            return null;
        }

        return "***.***.***-" + cpfNormalizado.substring(9);
    }
}
