package com.projeto.cortex.auth.identity;

import com.projeto.cortex.config.SecretMaterialLoader;
import com.projeto.cortex.colaboradores.CpfHasher;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class HmacCpfLookupDigestService
        implements CpfLookupDigestService {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String DECOY_INPUT = "invalid-id!";

    private final String currentKeyId;
    private final byte[] currentKey;
    private final String previousKeyId;
    private final byte[] previousKey;

    public HmacCpfLookupDigestService(
            String currentKeyId,
            Path currentKeyFile,
            String currentKeyInline,
            PreviousKey previous
    ) {
        this.currentKeyId = requireKeyId(currentKeyId);
        this.currentKey = SecretMaterialLoader.required(
                currentKeyFile,
                currentKeyInline,
                "CPF HMAC"
        );

        if (previous == null) {
            this.previousKeyId = null;
            this.previousKey = null;
            return;
        }

        this.previousKeyId = requireKeyId(previous.keyId());
        this.previousKey = SecretMaterialLoader.required(
                previous.file(),
                previous.inline(),
                "CPF HMAC anterior"
        );
        if (this.currentKeyId.equals(this.previousKeyId)) {
            throw new IllegalStateException(
                    "Os Key IDs atual e anterior devem ser distintos."
            );
        }
        if (Arrays.equals(this.currentKey, this.previousKey)) {
            throw new IllegalStateException(
                    "As chaves HMAC atual e anterior devem usar materiais distintos."
            );
        }
    }

    @Override
    public CpfLookupDigest current(String cpfRaw) {
        return digest(
                currentKeyId,
                currentKey,
                CpfNormalizer.requireValid(cpfRaw)
        );
    }

    @Override
    public List<CpfLookupDigest> candidates(String cpfRaw) {
        String cpf = CpfNormalizer.requireValid(cpfRaw);
        CpfLookupDigest active = digest(currentKeyId, currentKey, cpf);
        return previousKey == null
                ? List.of(active)
                : List.of(active, digest(previousKeyId, previousKey, cpf));
    }

    @Override
    public AuthChallengeLookupMaterial challengeLookup(
            String identifier
    ) {
        String protectedInput;
        try {
            if (identifier == null
                    || identifier.length() > 512
                    || identifier.contains("\r")
                    || identifier.contains("\n")) {
                throw new IllegalArgumentException();
            }
            protectedInput = CpfNormalizer.requireValid(identifier);
        } catch (IllegalArgumentException exception) {
            protectedInput = DECOY_INPUT;
        }
        CpfLookupDigest active = digest(
                currentKeyId,
                currentKey,
                protectedInput
        );
        List<CpfLookupDigest> protectedCandidates = previousKey == null
                ? List.of(active)
                : List.of(
                        active,
                        digest(
                                previousKeyId,
                                previousKey,
                                protectedInput
                        )
                );
        return new AuthChallengeLookupMaterial(
                protectedCandidates,
                CpfHasher.hashDeDigitos(protectedInput)
        );
    }

    private CpfLookupDigest digest(String keyId, byte[] key, String cpf) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return new CpfLookupDigest(
                    keyId,
                    HexFormat.of().formatHex(
                            mac.doFinal(cpf.getBytes(StandardCharsets.UTF_8))
                    )
            );
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                    "Falha ao calcular identificador protegido.",
                    exception
            );
        }
    }

    private static String requireKeyId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,32}")) {
            throw new IllegalStateException("Key ID de CPF HMAC inválido.");
        }
        return value;
    }

    public record PreviousKey(String keyId, Path file, String inline) {
    }
}
