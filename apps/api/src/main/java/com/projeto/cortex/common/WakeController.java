package com.projeto.cortex.common;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Aquece o caminho que o login realmente percorre — e só ele.
 *
 * <p>Antes, quem precisava acordar a instância batia em {@code /api/readiness}.
 * Aquilo funciona, mas cobra o preço errado: o readiness faz um round trip
 * completo no object storage (PUT, GET e DELETE) e reúne a evidência de release,
 * nada disso exigido para entrar no sistema. Numa JVM fria isso é carregamento
 * do SDK da AWS e três handshakes TLS somados a uma espera que ninguém pediu.
 *
 * <p>O que o primeiro login do dia espera é outra coisa: o contêiner de pé, uma
 * conexão de verdade no pool e o banco fora da suspensão automática. Este
 * endpoint faz exatamente esses três e para por aí.
 *
 * <p>Ele não decide nada e por isso não fecha: se o banco ainda não respondeu, a
 * resposta continua 200 e apenas relata que o banco segue frio. Quem decide
 * prontidão é {@link ReadinessController}, que permanece fail-closed. Confundir
 * os dois transformaria um toque de aquecimento em portão de liberação.
 */
@RestController
public class WakeController {

    private final DatabaseWake database;
    private final RuntimeRevision revision;

    public WakeController(DatabaseWake database, RuntimeRevision revision) {
        this.database = database;
        this.revision = revision;
    }

    @GetMapping("/api/wake")
    public Map<String, String> wake() {
        Map<String, String> response = new LinkedHashMap<>();
        response.put("status", "AWAKE");
        response.put("service", "cortex-api");
        response.put("revision", revision.value());
        response.put("database", database.status());
        response.put("timestamp", Instant.now().toString());
        return Map.copyOf(response);
    }
}
