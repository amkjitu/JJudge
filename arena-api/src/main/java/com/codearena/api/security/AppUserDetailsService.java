package com.codearena.api.security;

import com.codearena.api.domain.User;
import com.codearena.api.repository.UserRepository;
import com.codearena.common.domain.AuthProvider;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public AppUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Accepts a username or an email address, because people type either into a login form.
     */
    @Override
    public UserDetails loadUserByUsername(String usernameOrEmail) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(usernameOrEmail)
                .or(() -> userRepository.findByEmail(usernameOrEmail))
                .orElseThrow(() -> new UsernameNotFoundException("No account for '" + usernameOrEmail + "'"));

        if (user.getAuthProvider() != AuthProvider.LOCAL) {
            // Refusing here rather than letting the password check fail on a null hash: the
            // outcome is the same, but this keeps the reason explicit.
            throw new UsernameNotFoundException(
                    "Account '" + user.getUsername() + "' authenticates via " + user.getAuthProvider());
        }

        return new AppUserDetails(user);
    }
}
