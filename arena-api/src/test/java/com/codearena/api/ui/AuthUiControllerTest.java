package com.codearena.api.ui;

import com.codearena.api.security.AppUserDetailsService;
import com.codearena.api.service.AuthService;
import com.codearena.api.web.dto.RegisterRequest;
import com.codearena.api.web.error.DuplicateResourceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(AuthUiController.class)
@Import(UiSliceSecurityConfig.class)
@DisplayName("Login and registration pages")
class AuthUiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @MockBean
    private AppUserDetailsService userDetailsService;

    @Test
    @DisplayName("login page renders with the demo credentials hint")
    void loginRenders() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/login"))
                .andExpect(content().string(containsString("name=\"usernameOrEmail\"")))
                .andExpect(content().string(containsString("Demo accounts")));
    }

    @Test
    @DisplayName("the Google button is hidden when no client is registered")
    void googleButtonHiddenWithoutConfiguration() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(content().string(not(containsString("Continue with Google"))));
    }

    @Test
    @DisplayName("the login error message does not reveal which half was wrong")
    void loginErrorIsVague() throws Exception {
        mockMvc.perform(get("/login").param("error", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("did not match an account")))
                .andExpect(content().string(not(containsString("password is incorrect"))))
                .andExpect(content().string(not(containsString("no such user"))));
    }

    @Test
    @WithMockUser(username = "bob")
    @DisplayName("an already signed-in user is bounced off the login page")
    void signedInUserRedirectedAwayFromLogin() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    @DisplayName("registration page renders an empty form")
    void registerRenders() throws Exception {
        mockMvc.perform(get("/register"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/register"))
                .andExpect(content().string(containsString("id=\"username\"")))
                .andExpect(content().string(containsString("id=\"password\"")));
    }

    @Test
    @DisplayName("invalid input re-renders the form with field errors and never reaches the service")
    void invalidRegistrationShowsFieldErrors() throws Exception {
        mockMvc.perform(post("/register")
                        .with(csrf())
                        .param("username", "a")
                        .param("email", "not-an-email")
                        .param("password", "short"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/register"))
                .andExpect(content().string(containsString("is-invalid")));

        verify(authService, never()).register(any());
    }

    @Test
    @DisplayName("a rejected password is never echoed back into the form")
    void rejectedPasswordIsNotEchoed() throws Exception {
        mockMvc.perform(post("/register")
                        .with(csrf())
                        .param("username", "ok")
                        .param("email", "not-an-email")
                        .param("password", "hunter2-secret-value"))
                .andExpect(status().isOk())
                // The password input must come back empty; re-rendering it would put the
                // credential into the HTML and from there into any proxy cache or bug report.
                .andExpect(content().string(not(containsString("hunter2-secret-value"))));
    }

    @Test
    @DisplayName("a taken username is reported on the username field, not as a banner")
    void duplicateUsernameIsAFieldError() throws Exception {
        when(authService.register(any(RegisterRequest.class)))
                .thenThrow(new DuplicateResourceException("Username", "bob"));

        mockMvc.perform(post("/register")
                        .with(csrf())
                        .param("username", "bob")
                        .param("email", "bob2@example.com")
                        .param("password", "correct-horse-battery"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/register"))
                .andExpect(content().string(containsString("is already taken")));
    }

    @Test
    @DisplayName("a successful registration signs the user straight in")
    void successfulRegistrationSignsIn() throws Exception {
        when(userDetailsService.loadUserByUsername("newcomer")).thenReturn(
                new User("newcomer", "irrelevant", List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        mockMvc.perform(post("/register")
                        .with(csrf())
                        .param("username", "newcomer")
                        .param("email", "newcomer@example.com")
                        .param("password", "correct-horse-battery"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/?welcome"));

        verify(authService).register(any(RegisterRequest.class));
    }
}
