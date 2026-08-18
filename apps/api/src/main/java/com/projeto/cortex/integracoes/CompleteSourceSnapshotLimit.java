package com.projeto.cortex.integracoes;

/** Aggregate bound applied after every source page and before accumulation. */
final class CompleteSourceSnapshotLimit {

    static final int MAX_ROWS = 50_000;
    private static final String LIMIT_EXCEEDED =
            "Snapshot da fonte excedeu o limite operacional.";

    private CompleteSourceSnapshotLimit() {
    }

    static void ensureCapacity(int currentRows, int incomingRows) {
        if (currentRows < 0
                || incomingRows < 0
                || currentRows > MAX_ROWS - incomingRows) {
            throw new IllegalStateException(LIMIT_EXCEEDED);
        }
    }
}
