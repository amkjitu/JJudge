package com.codearena.api.ui.form;

import com.codearena.api.web.dto.RegisterRequest;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Form-backing bean for the registration page.
 *
 * <p>Mutable, unlike the {@link RegisterRequest} record the JSON API binds. That is not
 * duplication for its own sake: Thymeleaf's {@code th:field} has to <em>write</em> back into
 * the object to re-populate the form after a validation failure, and a record cannot be
 * written to. Records stay on the API boundary where they are only ever constructed once.
 */
public class RegisterForm {

    @NotBlank
    @Size(min = 3, max = 50)
    @Pattern(regexp = "^[a-zA-Z0-9_-]+$",
            message = "may contain only letters, digits, underscores and hyphens")
    private String username;

    @NotBlank
    @Email
    @Size(max = 255)
    private String email;

    @NotBlank
    @Size(min = 10, max = 100, message = "must be between 10 and 100 characters")
    private String password;

    public RegisterRequest toRequest() {
        return new RegisterRequest(username, email, password);
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
