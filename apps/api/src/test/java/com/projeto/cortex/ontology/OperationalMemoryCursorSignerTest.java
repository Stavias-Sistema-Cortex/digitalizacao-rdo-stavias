package com.projeto.cortex.ontology;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperationalMemoryCursorSignerTest {

    private static final byte[] CURRENT =
            "current-memory-cursor-secret-material-2026"
                    .getBytes(StandardCharsets.UTF_8);
    private static final byte[] PREVIOUS =
            "previous-memory-cursor-secret-material-2026"
                    .getBytes(StandardCharsets.UTF_8);

    @Test
    void signsAnOpaqueCursorBoundToSnapshotScopeAndFilter() {
        OperationalMemoryCursorSigner signer = new OperationalMemoryCursorSigner(
                new OperationalMemoryCursorKeyring("current", CURRENT, null, null)
        );

        String token = signer.sign(105L, 104L, "event-104", "scope-a", "filter-a");
        OperationalMemoryCursorSigner.VerifiedCursor cursor = signer.verify(
                token,
                "scope-a",
                "filter-a"
        );

        assertThat(token).doesNotContain("event-104", "scope-a", "filter-a");
        assertThat(cursor.highWaterMark()).isEqualTo(105L);
        assertThat(cursor.commitSequence()).isEqualTo(104L);
        assertThat(cursor.eventId()).isEqualTo("event-104");
    }

    @Test
    void rejectsTamperingAndDifferentScopeOrFilter() {
        OperationalMemoryCursorSigner signer = new OperationalMemoryCursorSigner(
                new OperationalMemoryCursorKeyring("current", CURRENT, null, null)
        );
        String token = signer.sign(105L, 104L, "event-104", "scope-a", "filter-a");
        String tampered = token.substring(0, token.length() - 1)
                + (token.endsWith("A") ? "B" : "A");

        assertThatThrownBy(() -> signer.verify(tampered, "scope-a", "filter-a"))
                .isInstanceOf(OperationalMemoryCursorScopeException.class);
        assertThatThrownBy(() -> signer.verify(token, "scope-b", "filter-a"))
                .isInstanceOf(OperationalMemoryCursorScopeException.class);
        assertThatThrownBy(() -> signer.verify(token, "scope-a", "filter-b"))
                .isInstanceOf(OperationalMemoryCursorScopeException.class);
    }

    @Test
    void acceptsThePreviousKeyDuringRotationButNeverSignsWithIt() {
        OperationalMemoryCursorSigner previousSigner = new OperationalMemoryCursorSigner(
                new OperationalMemoryCursorKeyring("previous", PREVIOUS, null, null)
        );
        String oldToken = previousSigner.sign(
                105L, 104L, "event-104", "scope-a", "filter-a"
        );
        OperationalMemoryCursorSigner rotated = new OperationalMemoryCursorSigner(
                new OperationalMemoryCursorKeyring(
                        "current", CURRENT, "previous", PREVIOUS
                )
        );

        assertThat(rotated.verify(oldToken, "scope-a", "filter-a").eventId())
                .isEqualTo("event-104");
        assertThat(rotated.sign(105L, 103L, "event-103", "scope-a", "filter-a"))
                .startsWith("v1.current.");
    }

    @Test
    void keyringFailsClosedWithoutStrongDistinctKeyMaterial() {
        assertThatThrownBy(() -> new OperationalMemoryCursorKeyring(
                "current", null, null, null
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new OperationalMemoryCursorKeyring(
                "current", "short".getBytes(StandardCharsets.UTF_8), null, null
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new OperationalMemoryCursorKeyring(
                "current", CURRENT, "previous", CURRENT
        )).isInstanceOf(IllegalStateException.class);
    }
}
