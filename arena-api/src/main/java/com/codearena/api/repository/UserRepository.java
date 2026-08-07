package com.codearena.api.repository;

import com.codearena.api.domain.User;
import com.codearena.common.domain.AuthProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    /**
     * Federated lookup by the provider's stable subject id. Deliberately not by email - see
     * {@code OAuth2UserProvisioningService} for why matching on email is an account-takeover
     * vector.
     */
    Optional<User> findByProviderIdAndAuthProvider(String providerId, AuthProvider authProvider);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    /**
     * Fetches the user together with their tag statistics in one query. Without this the
     * recommendation engine would trigger a second SELECT the moment it touches
     * {@code user.getTagStats()}.
     */
    @Query("""
            SELECT DISTINCT u FROM User u
            LEFT JOIN FETCH u.tagStats ts
            LEFT JOIN FETCH ts.tag
            WHERE u.id = :id
            """)
    Optional<User> findByIdWithTagStats(@Param("id") Long id);

    List<User> findTop50ByOrderByRatingDesc();
}
