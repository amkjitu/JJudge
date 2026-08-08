package com.codearena.api.ui;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Target of {@code accessDeniedPage("/error/403")} in the security configuration. Spring
 * Security forwards here rather than redirecting, so the response keeps its 403 status.
 */
@Controller
public class ErrorPageUiController {

    /**
     * Mapped for every HTTP method on purpose.
     *
     * <p>A forward preserves the original request method, so a CSRF failure on a form POST
     * arrives here as a POST. Restricting this to GET turns every such rejection into a 405
     * with Boot's default JSON body - the user sees "Method Not Allowed" for what was really
     * an expired session, and the 403 page never renders.
     */
    @RequestMapping("/error/403")
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public String forbidden(Model model) {
        model.addAttribute("status", 403);
        model.addAttribute("heading", "Not allowed");
        model.addAttribute("message",
                "Your account does not have permission to view that page. If you were signed in "
                        + "for a while, your session may simply have expired - try signing in again.");
        return "error/generic";
    }
}
