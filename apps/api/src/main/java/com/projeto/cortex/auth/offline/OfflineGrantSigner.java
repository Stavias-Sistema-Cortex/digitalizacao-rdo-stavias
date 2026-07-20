package com.projeto.cortex.auth.offline;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.ByteArrayOutputStream;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;

final class OfflineGrantSigner {

    private static final int MAX_PAYLOAD_BYTES = 32_768;

    private final OfflineGrantSigningKey key;

    OfflineGrantSigner(OfflineGrantSigningKey key) {
        this.key = key;
    }

    OfflineGrant sign(OfflineGrantClaims claims) {
        byte[] payload = serialize(claims);
        if (payload.length > MAX_PAYLOAD_BYTES) {
            Arrays.fill(payload, (byte) 0);
            throw new OfflineGrantScopeTooLargeException(
                    "Escopo offline excede o tamanho seguro; reconecte-se "
                            + "para atualizar o acesso."
            );
        }
        byte[] signature = sign(payload);
        try {
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return new OfflineGrant(
                    key.keyId(),
                    encoder.encodeToString(payload),
                    encoder.encodeToString(signature),
                    encoder.encodeToString(key.publicKey().getEncoded())
            );
        } finally {
            Arrays.fill(payload, (byte) 0);
            Arrays.fill(signature, (byte) 0);
        }
    }

    private byte[] serialize(OfflineGrantClaims claims) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            JsonGenerator json = new JsonFactory().createGenerator(output);
            json.writeStartObject();
            json.writeNumberField("versao", claims.versao());
            json.writeStringField(
                    "colaboradorId",
                    claims.colaboradorId().toString()
            );
            json.writeStringField("nome", claims.nome());
            json.writeStringField(
                    "papelAcesso",
                    claims.papelAcesso().name()
            );
            json.writeBooleanField("escopoGlobal", claims.escopoGlobal());
            json.writeArrayFieldStart("obraIds");
            for (String obraId : claims.obraIds()) {
                json.writeString(obraId);
            }
            json.writeEndArray();
            json.writeStringField("emitidoEm", claims.emitidoEm().toString());
            json.writeStringField("expiraEm", claims.expiraEm().toString());
            json.writeEndObject();
            json.close();
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Não foi possível serializar o grant offline.",
                    exception
            );
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(key.privateKey());
            signer.update(payload);
            return signer.sign();
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Não foi possível assinar o grant offline.",
                    exception
            );
        }
    }
}
