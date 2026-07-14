package com.projeto.cortex.sync;

import java.util.Set;

public interface SyncOperationHandler {

    String entityType();

    Set<String> operations();

    boolean requiresBaseVersion(String operation);

    AppliedSyncMutation apply(SyncPushRequest.MutacaoCliente mutation);
}
