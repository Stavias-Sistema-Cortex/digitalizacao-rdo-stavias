package com.projeto.cortex.auth.roles;

import com.projeto.cortex.auth.CurrentUserService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/colaboradores")
public class AdminRoleController {

    private final CurrentUserService currentUser;
    private final AdminRoleService service;

    public AdminRoleController(
            CurrentUserService currentUser,
            AdminRoleService service
    ) {
        this.currentUser = currentUser;
        this.service = service;
    }

    @PutMapping("/{colaboradorId}/papel")
    public AdminRoleChangeResponse changeRole(
            @PathVariable String colaboradorId,
            @RequestBody AdminRoleChangeRequest request
    ) {
        return service.changeRole(
                currentUser.requireUserId(),
                colaboradorId,
                request
        );
    }
}
