package com.projeto.cortex.auth.offline;

import com.projeto.cortex.auth.CurrentUserService;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class OfflineGrantController {

    private final CurrentUserService currentUsers;
    private final OfflineGrantService grants;

    public OfflineGrantController(
            CurrentUserService currentUsers,
            OfflineGrantService grants
    ) {
        this.currentUsers = currentUsers;
        this.grants = grants;
    }

    @PostMapping("/api/auth/offline-grant")
    public OfflineGrant issue(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        String principal = currentUsers.requireUserId();
        try {
            UUID collaboratorId = UUID.fromString(principal);
            if (!collaboratorId.toString().equalsIgnoreCase(principal)) {
                throw new IllegalArgumentException();
            }
            return grants.issue(collaboratorId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Identidade autenticada inválida."
            );
        }
    }
}
