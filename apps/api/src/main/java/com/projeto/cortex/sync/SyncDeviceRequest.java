package com.projeto.cortex.sync;

public record SyncDeviceRequest(
        String id,
        String nome,
        String tipo,
        String usuarioId
) {
}
