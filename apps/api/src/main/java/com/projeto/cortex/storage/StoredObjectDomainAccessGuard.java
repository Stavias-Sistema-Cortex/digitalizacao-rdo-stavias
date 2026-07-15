package com.projeto.cortex.storage;

/**
 * Applies a domain-specific authorization boundary before generic owner or
 * worksite access is considered for a stored object.
 */
public interface StoredObjectDomainAccessGuard {

    boolean protects(StoredObjectRecord object);

    void authorize(StoredObjectRecord object);
}
