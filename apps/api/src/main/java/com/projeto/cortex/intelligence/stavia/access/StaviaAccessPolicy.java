package com.projeto.cortex.intelligence.stavia.access;

import java.util.Set;

/**
 * Single authorization decision point for Stav.IA queries.
 *
 * <p>It replaces the previous pattern where the controller fabricated the
 * required permission for every caller and stamped it onto every piece of
 * evidence. Permissions and worksite access are now <em>derived from the user</em>
 * through this policy, so a real implementation (backed by a roles/ACL store)
 * can be plugged in without touching the query pipeline.
 */
public interface StaviaAccessPolicy {

    /**
     * The permissions granted to {@code userId}. An unknown or blank user must
     * receive an empty set so the engine denies the query.
     */
    Set<String> permissionsFor(String userId);

    /**
     * Whether {@code userId} is allowed to query data for {@code worksiteId}.
     * Used to prevent a user from reading another worksite's data (IDOR).
     */
    boolean canAccessWorksite(String userId, String worksiteId);

    /**
     * Whether the user may retrieve financial evidence for this exact worksite.
     * Adapters must opt in explicitly. The default denies financial retrieval.
     */
    default boolean canAccessFinancial(String userId, String worksiteId) {
        return false;
    }

    /**
     * Whether the user may retrieve message evidence for this exact worksite.
     * The default denies so adapters must derive the capability explicitly.
     */
    default boolean canAccessMessages(String userId, String worksiteId) {
        return false;
    }
}
