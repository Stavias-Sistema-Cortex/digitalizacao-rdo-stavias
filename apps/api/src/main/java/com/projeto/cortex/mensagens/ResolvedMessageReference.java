package com.projeto.cortex.mensagens;

public record ResolvedMessageReference(
        String id,
        String objectType,
        String objectId,
        String worksiteId
) {
}
