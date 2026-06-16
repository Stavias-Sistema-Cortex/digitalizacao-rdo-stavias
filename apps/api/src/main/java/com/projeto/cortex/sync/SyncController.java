package com.projeto.cortex.sync;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SyncController {

    private final SyncService service;

    public SyncController(SyncService service) {
        this.service = service;
    }

    @GetMapping("/api/sync/pull")
    public SyncPullResponse pull(
            @RequestParam(defaultValue = "0") long afterCommitSeq,
            @RequestParam(required = false) Integer limit
    ) {
        return service.pull(afterCommitSeq, limit);
    }

    @PostMapping("/api/sync/dispositivos")
    public SyncDeviceResponse registrarDispositivo(@RequestBody SyncDeviceRequest request) {
        return service.registrarDispositivo(request);
    }

    @PostMapping("/api/sync/ack")
    public SyncAckResponse ack(@RequestBody SyncAckRequest request) {
        return service.ack(request);
    }

    @PostMapping("/api/sync/push")
    public SyncPushResponse push(@RequestBody SyncPushRequest request) {
        return service.push(request);
    }
}
