package com.projeto.cortex.auth.offline;

final class OfflineGrantScopeTooLargeException extends IllegalStateException {

    OfflineGrantScopeTooLargeException(String message) {
        super(message);
    }
}
