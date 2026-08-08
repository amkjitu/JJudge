package com.codearena.api.ui;

import com.codearena.api.security.AppUserDetailsService;
import com.codearena.api.service.AuthService;
import com.codearena.api.ui.form.RegisterForm;
import com.codearena.api.web.error.DuplicateResourceException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * Login and registration pages.
 *
 * <p>The login <em>form post</em> is not handled here - Spring Security's
 * {@code UsernamePasswordAuthenticationFilter} intercepts {@code POST /login} before any
 * controller sees it. This class only renders the page.
 */
@Controller
public class AuthUiController {

    private final AuthService authService;
    private final AppUserDetailsService userDetailsService;
    private final ObjectProvider<ClientRegistrationRepository> clientRegistrations;
    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    public AuthUiController(AuthService authService,
                            AppUserDetailsService userDetailsService,
                            ObjectProvider<ClientRegistrationRepository> clientRegistrations) {
        this.authService = authService;
        this.userDetailsService = userDetailsService;
        this.clientRegistrations = clientRegistrations;
    }

    /**
     * Whether to show the "Continue with Google" button. Derived from whether a client
     * registration actually exists, so a deployment without Google credentials shows a login
     * page that works rather than a button that 500s.
     */
    @ModelAttribute("googleEnabled")
    boolean googleEnabled() {
        return clientRegistrations.getIfAvailable() != null;
    }

    @GetMapping("/login")
    public String loginPage(Authentication authentication) {
        return UiSecurity.isAuthenticated(authentication) ? "redirect:/" : "auth/login";
    }

    @GetMapping("/register")
    public String registerPage(Authentication authentication, Model model) {
        if (UiSecurity.isAuthenticated(authentication)) {
            return "redirect:/";
        }
        model.addAttribute("form", new RegisterForm());
        return "auth/register";
    }

    @PostMapping("/register")
    public String register(@Valid @ModelAttribute("form") RegisterForm form,
                           BindingResult binding,
                           HttpServletRequest request,
                           HttpServletResponse response) {
        if (binding.hasErrors()) {
            return "auth/register";
        }

        try {
            authService.register(form.toRequest());
        } catch (DuplicateResourceException e) {
            // Field-level rather than a banner, so the message sits next to the input that has
            // to change. The resource type tells us which field clashed.
            String field = "Email".equals(e.getResourceType()) ? "email" : "username";
            binding.addError(new FieldError("form", field, binding.getFieldValue(field),
                    false, null, null, "is already taken"));
            return "auth/register";
        }

        signIn(form.getUsername(), request, response);
        return "redirect:/?welcome";
    }

    /**
     * Signs the new account in without a second round trip through the login form.
     *
     * <p>The context has to be written to the repository by hand: no authentication filter ran
     * for this request, so nothing else will persist it, and the redirect would arrive
     * anonymous.
     */
    private void signIn(String username, HttpServletRequest request, HttpServletResponse response) {
        // Guards against session fixation: the id the browser arrived with is discarded before
        // the session becomes an authenticated one.
        request.getSession().invalidate();
        request.getSession(true);

        UserDetails principal = userDetailsService.loadUserByUsername(username);
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }
}
