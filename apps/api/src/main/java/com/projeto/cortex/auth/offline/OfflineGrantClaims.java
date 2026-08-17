package com.projeto.cortex.auth.offline;

import com.projeto.cortex.auth.PapelAcesso;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

record OfflineGrantClaims(
        int versao,
        UUID colaboradorId,
        String nome,
        PapelAcesso papelAcesso,
        boolean escopoGlobal,
        List<String> obraIds,
        Instant emitidoEm,
        Instant expiraEm,
        long authEpoch
) {

    OfflineGrantClaims {
        if (authEpoch < 1) {
            throw new IllegalArgumentException(
                    "Época de autorização offline inválida."
            );
        }
    }

    @Override
    public String toString() {
        return "OfflineGrantClaims[identityAndScope=[REDACTED]]";
    }
}
