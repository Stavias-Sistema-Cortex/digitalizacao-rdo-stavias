package com.projeto.cortex.auth.identity;

import java.util.List;

public interface CpfLookupDigestService {

    CpfLookupDigest current(String cpfRaw);

    /** Returns the current digest first, followed by an optional previous key. */
    List<CpfLookupDigest> candidates(String cpfRaw);

    /** Returns the same protected shape for valid and invalid identifiers. */
    AuthChallengeLookupMaterial challengeLookup(String identifier);
}
