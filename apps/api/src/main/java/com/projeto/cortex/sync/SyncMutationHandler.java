package com.projeto.cortex.sync;

import java.util.Set;

public interface SyncMutationHandler {

    Set<String> operations();

    boolean requiresBaseVersion(String operation);

    SyncMutationApplied apply(SyncPushRequest.MutacaoCliente mutation);
}
