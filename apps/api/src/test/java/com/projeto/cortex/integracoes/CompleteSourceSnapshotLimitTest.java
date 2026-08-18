package com.projeto.cortex.integracoes;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CompleteSourceSnapshotLimitTest {

    @Test
    void acceptsTheConfiguredBoundaryAndRejectsAggregateOverflow() {
        assertThatCode(() -> CompleteSourceSnapshotLimit.ensureCapacity(
                CompleteSourceSnapshotLimit.MAX_ROWS - 500,
                500
        )).doesNotThrowAnyException();

        assertThatThrownBy(() -> CompleteSourceSnapshotLimit.ensureCapacity(
                CompleteSourceSnapshotLimit.MAX_ROWS,
                1
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Snapshot da fonte excedeu o limite operacional.");
    }

    @Test
    void rejectsIntegerOverflowInsteadOfWrappingTheAggregateCount() {
        assertThatThrownBy(() -> CompleteSourceSnapshotLimit.ensureCapacity(
                Integer.MAX_VALUE,
                Integer.MAX_VALUE
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Snapshot da fonte excedeu o limite operacional.");
    }
}
