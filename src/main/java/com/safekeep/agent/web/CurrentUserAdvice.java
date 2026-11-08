package com.safekeep.agent.web;

import com.safekeep.agent.security.CurrentUser;
import com.safekeep.agent.security.Role;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.Map;

/** Makes the signed-in user available to every page. */
@ControllerAdvice(assignableTypes = WebController.class)
public class CurrentUserAdvice {

    @ModelAttribute("me")
    public Map<String, Object> me() {
        Role role = CurrentUser.role();
        return Map.of("name", CurrentUser.username(), "role", role,
                "support", role.atLeast(Role.SUPPORT_ENGINEER), "admin", role == Role.ADMIN);
    }
}
