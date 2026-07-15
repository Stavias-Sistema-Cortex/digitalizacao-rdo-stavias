package com.projeto.cortex.sync;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SyncMutationHandlerRegistryTest {

    @Test
    void exposesDomainMutationHandlerContract() {
        assertThatCode(() -> Class.forName("com.projeto.cortex.sync.SyncMutationHandler"))
                .doesNotThrowAnyException();
        assertThatCode(() -> Class.forName("com.projeto.cortex.sync.SyncMutationApplied"))
                .doesNotThrowAnyException();
    }

    @Test
    void exposesValidatedOperationRegistry() {
        assertThatCode(() -> Class.forName("com.projeto.cortex.sync.SyncMutationHandlerRegistry"))
                .doesNotThrowAnyException();
    }

    @Test
    void resolvesHandlerByExactOperation() {
        SyncMutationHandler handler = new StubHandler(Set.of("CRIAR_TESTE"));
        SyncMutationHandlerRegistry registry = new SyncMutationHandlerRegistry(List.of(handler));

        assertThat(registry.require("CRIAR_TESTE")).isSameAs(handler);
    }

    @Test
    void rejectsDuplicateOperationOwnershipAtConstruction() {
        SyncMutationHandler first = new StubHandler(Set.of("CRIAR_TESTE"));
        SyncMutationHandler second = new StubHandler(Set.of("CRIAR_TESTE"));

        assertThatThrownBy(() -> new SyncMutationHandlerRegistry(List.of(first, second)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CRIAR_TESTE");
    }

    @Test
    void rejectsUnsupportedOperationWithPortugueseBadRequest() {
        SyncMutationHandlerRegistry registry = new SyncMutationHandlerRegistry(List.of());

        assertThatThrownBy(() -> registry.require("OPERACAO_DESCONHECIDA"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getReason())
                            .isEqualTo("Operação não suportada pelo sync push: OPERACAO_DESCONHECIDA");
                });
    }

    private record StubHandler(Set<String> operations) implements SyncMutationHandler {

        @Override
        public boolean requiresBaseVersion(String operation) {
            return false;
        }

        @Override
        public SyncMutationApplied apply(SyncPushRequest.MutacaoCliente mutation) {
            throw new UnsupportedOperationException("O teste do registro não aplica mutações.");
        }
    }
}
