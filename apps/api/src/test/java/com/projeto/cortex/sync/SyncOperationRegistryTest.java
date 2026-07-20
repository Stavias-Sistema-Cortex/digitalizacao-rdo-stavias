package com.projeto.cortex.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class SyncOperationRegistryTest {

    @Test
    void routesCanonicalOperationToExactlyOneHandler() {
        SyncOperationHandler messages = handler(
                "MENSAGEM",
                Set.of("CRIAR_MENSAGEM", "EDITAR_MENSAGEM")
        );
        SyncOperationRegistry registry = new SyncOperationRegistry(
                List.of(messages)
        );

        assertThat(registry.require("CRIAR_MENSAGEM"))
                .isSameAs(messages);
        assertThat(registry.operations())
                .containsExactlyInAnyOrder(
                        "CRIAR_MENSAGEM",
                        "EDITAR_MENSAGEM"
                );
    }

    @Test
    void failsStartupWhenTwoHandlersClaimTheSameOperation() {
        assertThatThrownBy(() -> new SyncOperationRegistry(List.of(
                handler("RDO", Set.of("CRIAR_RDO")),
                handler("MENSAGEM", Set.of("CRIAR_RDO"))
        ))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CRIAR_RDO");
    }

    @Test
    void rejectsUnknownAndNonCanonicalOperations() {
        SyncOperationRegistry registry = new SyncOperationRegistry(
                List.of(handler("RDO", Set.of("CRIAR_RDO")))
        );

        assertThatThrownBy(() -> registry.require("CRIAR_MENSAGEM"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> registry.require(" criar_rdo "))
                .isInstanceOf(ResponseStatusException.class);
    }

    private SyncOperationHandler handler(
            String entityType,
            Set<String> operations
    ) {
        return new SyncOperationHandler() {
            @Override
            public String entityType() {
                return entityType;
            }

            @Override
            public Set<String> operations() {
                return operations;
            }

            @Override
            public boolean requiresBaseVersion(String operation) {
                return !operation.startsWith("CRIAR_");
            }

            @Override
            public AppliedSyncMutation apply(
                    SyncPushRequest.MutacaoCliente mutation,
                    SyncMutationContext context
            ) {
                return new AppliedSyncMutation(
                        entityType,
                        mutation.entidadeId(),
                        JsonNodeFactory.instance.objectNode()
                );
            }
        };
    }
}
