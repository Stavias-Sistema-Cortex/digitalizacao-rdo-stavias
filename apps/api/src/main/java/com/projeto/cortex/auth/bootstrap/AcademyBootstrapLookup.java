package com.projeto.cortex.auth.bootstrap;

import com.projeto.cortex.integracoes.AcademyBootstrapUser;
import com.projeto.cortex.integracoes.AcademySourceAdapter;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Narrow Academy boundary used only by the non-web initial-ALFA bootstrap. */
@Component
@Profile("postgresql-bootstrap")
public final class AcademyBootstrapLookup {

    private final AcademySourceAdapter academySourceAdapter;

    public AcademyBootstrapLookup(AcademySourceAdapter academySourceAdapter) {
        this.academySourceAdapter = academySourceAdapter;
    }

    public AcademyBootstrapUser lookupSingleActive(String canonicalCpf) {
        try {
            AcademyBootstrapUser user = academySourceAdapter
                    .findSingleActiveUserForBootstrap(canonicalCpf)
                    .orElseThrow(BootstrapLookupException::new);
            if (!user.ativo()) {
                throw new BootstrapLookupException();
            }
            return user;
        } catch (BootstrapLookupException exception) {
            throw exception;
        } catch (Exception ignored) {
            throw new BootstrapLookupException();
        }
    }

    private static final class BootstrapLookupException extends IllegalStateException {

        private BootstrapLookupException() {
            super("Fonte Academy indisponível para bootstrap.");
        }
    }
}
