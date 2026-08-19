package com.projeto.cortex.mensagens.api;

import java.util.List;

/** Gate completo de acesso e preferencias lido no mesmo snapshot do banco. */
public record ConversationAuthorizationSnapshotResponse(
        List<String> authorizedConversationIds,
        List<ConversationPreferenceResponse> preferences
) {
}
