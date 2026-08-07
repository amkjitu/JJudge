package com.codearena.api.security;

import com.codearena.api.domain.User;
import com.codearena.api.repository.UserRepository;
import com.codearena.common.domain.AuthProvider;
import com.codearena.common.domain.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * Turns a verified Google identity into a local account.
 */
@Service
public class OAuth2UserProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(OAuth2UserProvisioningService.class);

    private static final int STARTING_RATING = 1200;
    private static final int MAX_USERNAME_LENGTH = 50;

    private final UserRepository userRepository;

    public OAuth2UserProvisioningService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Finds or creates the account for a federated identity.
     *
     * <p>Matching is on the provider's {@code sub}, not on email. Email addresses get reassigned
     * and can change; {@code sub} is the only stable identifier Google promises. Matching on
     * email would also mean anyone who could get Google to issue a token for an address that
     * happens to equal an existing local account's email would take that account over.
     *
     * @param providerId the provider's stable subject identifier
     * @param email      the verified email address, used only for display and username seeding
     * @param name       the display name, if the provider supplied one
     */
    @Transactional
    public User provision(String providerId, String email, String name) {
        return userRepository.findByProviderIdAndAuthProvider(providerId, AuthProvider.GOOGLE)
                .orElseGet(() -> createFederatedUser(providerId, email, name));
    }

    private User createFederatedUser(String providerId, String email, String name) {
        if (email == null || email.isBlank()) {
            throw new OAuth2AuthenticationException(new OAuth2Error("email_required"),
                    "The identity provider did not supply an email address");
        }
        if (userRepository.existsByEmail(email)) {
            // A local account already owns this address. Silently linking them would let anyone
            // who controls the Google account take over the local one, so this refuses and
            // leaves the user to log in the way they originally signed up.
            throw new OAuth2AuthenticationException(new OAuth2Error("email_already_registered"),
                    "An account already exists for " + email);
        }

        User user = userRepository.save(User.builder()
                .username(uniqueUsernameFrom(email, name))
                .email(email)
                .passwordHash(null)
                .authProvider(AuthProvider.GOOGLE)
                .providerId(providerId)
                .role(Role.USER)
                .rating(STARTING_RATING)
                .build());

        log.info("Provisioned Google account '{}'", user.getUsername());
        return user;
    }

    /**
     * Derives a username from the display name or email local part, appending a counter until
     * it is free. Bounded rather than a while(true): if a hundred variants are taken something
     * is wrong, and falling back to the provider id guarantees termination.
     */
    private String uniqueUsernameFrom(String email, String name) {
        String seed = (name != null && !name.isBlank()) ? name : email.substring(0, email.indexOf('@'));
        String base = seed.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        if (base.length() < 3) {
            base = "user" + base;
        }
        base = base.substring(0, Math.min(base.length(), MAX_USERNAME_LENGTH - 4));

        if (!userRepository.existsByUsername(base)) {
            return base;
        }
        for (int suffix = 2; suffix < 100; suffix++) {
            String candidate = base + suffix;
            if (!userRepository.existsByUsername(candidate)) {
                return candidate;
            }
        }
        return "user" + System.nanoTime();
    }
}
