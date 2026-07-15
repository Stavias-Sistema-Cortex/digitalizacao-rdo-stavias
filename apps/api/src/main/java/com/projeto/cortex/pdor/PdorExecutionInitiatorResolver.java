package com.projeto.cortex.pdor;

import com.projeto.cortex.auth.CurrentUserService;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class PdorExecutionInitiatorResolver {

    private final CurrentUserService currentUserService;

    public PdorExecutionInitiatorResolver(CurrentUserService currentUserService) {
        this.currentUserService = currentUserService;
    }

    public PdorExecutionInitiator resolve() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes)) {
            return PdorExecutionInitiator.process();
        }

        // Em requisição HTTP, ausência de identidade continua sendo erro de
        // autenticação. Ela nunca é silenciosamente reclassificada como processo.
        return new PdorExecutionInitiator(
                currentUserService.requireUserId(),
                "USER"
        );
    }
}
