package com.codearena.api.repository.spec;

import com.codearena.api.domain.Problem;
import com.codearena.api.domain.Tag;
import com.codearena.common.domain.Difficulty;
import jakarta.persistence.criteria.Join;
import org.springframework.data.jpa.domain.Specification;

/**
 * Composable filters for the problem catalogue.
 *
 * <p>Each returns {@code null} for an absent criterion, which {@link Specification#and} treats
 * as "no restriction" - so the caller can chain every filter unconditionally instead of
 * branching per parameter.
 */
public final class ProblemSpecifications {

    private ProblemSpecifications() {
    }

    /**
     * Joining a to-many association multiplies rows, so the query is marked distinct. The
     * {@code Long.class} guard skips that for the count query Spring Data issues alongside the
     * page query: {@code SELECT DISTINCT COUNT(...)} is not what we want, and some databases
     * reject it outright.
     */
    public static Specification<Problem> hasTag(String tagName) {
        if (tagName == null || tagName.isBlank()) {
            return null;
        }
        return (root, query, cb) -> {
            if (query != null && Long.class != query.getResultType()) {
                query.distinct(true);
            }
            Join<Problem, Tag> tags = root.join("tags");
            return cb.equal(cb.lower(tags.get("name")), tagName.toLowerCase());
        };
    }

    public static Specification<Problem> hasDifficulty(Difficulty difficulty) {
        if (difficulty == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("difficulty"), difficulty);
    }

    public static Specification<Problem> ratingAtLeast(Integer minRating) {
        if (minRating == null) {
            return null;
        }
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("rating"), minRating);
    }

    public static Specification<Problem> ratingAtMost(Integer maxRating) {
        if (maxRating == null) {
            return null;
        }
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("rating"), maxRating);
    }

    /**
     * Case-insensitive substring match across title and slug. Escapes the LIKE wildcards so a
     * search for {@code 100%} does not match everything.
     */
    public static Specification<Problem> titleOrSlugContains(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        String pattern = "%" + escapeLike(search.toLowerCase()) + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("title")), pattern, '\\'),
                cb.like(cb.lower(root.get("slug")), pattern, '\\')
        );
    }

    private static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
