package com.codearena.api.repository;

import com.codearena.api.domain.Problem;
import com.codearena.api.domain.Tag;
import com.codearena.common.domain.Difficulty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ProblemRepository")
class ProblemRepositoryIT extends AbstractRepositoryIT {

    @Autowired
    private ProblemRepository problemRepository;

    @Test
    @DisplayName("findBySlug eagerly attaches tags via the entity graph")
    void findBySlugLoadsTags() {
        Problem problem = problemRepository.findBySlug("dijkstra-on-a-weighted-grid").orElseThrow();

        assertThat(problem.getTitle()).isEqualTo("Dijkstra on a Weighted Grid");
        assertThat(problem.getRating()).isEqualTo(1500);
        assertThat(problem.getDifficulty()).isEqualTo(Difficulty.MEDIUM);
        assertThat(problem.getTags()).extracting(Tag::getName)
                .containsExactlyInAnyOrder("shortest-path", "heap", "graph");
    }

    @Test
    @DisplayName("unknown slug yields an empty Optional rather than throwing")
    void findBySlugUnknown() {
        assertThat(problemRepository.findBySlug("does-not-exist")).isEmpty();
    }

    @Test
    @DisplayName("paginates and sorts without losing the tag graph")
    void paginationWorks() {
        Page<Problem> firstPage = problemRepository.findAllBy(
                PageRequest.of(0, 10, Sort.by("rating").ascending()));

        assertThat(firstPage.getTotalElements()).isEqualTo(40);
        assertThat(firstPage.getTotalPages()).isEqualTo(4);
        assertThat(firstPage.getContent()).hasSize(10);
        assertThat(firstPage.getContent()).extracting(Problem::getRating).isSorted();
        assertThat(firstPage.getContent()).allSatisfy(p -> assertThat(p.getTags()).isNotEmpty());
    }

    @Test
    @DisplayName("filters by difficulty")
    void filterByDifficulty() {
        Page<Problem> hard = problemRepository.findByDifficulty(Difficulty.HARD, PageRequest.of(0, 50));

        assertThat(hard.getContent()).isNotEmpty();
        assertThat(hard.getContent()).allSatisfy(p -> {
            assertThat(p.getDifficulty()).isEqualTo(Difficulty.HARD);
            assertThat(p.getRating()).isGreaterThanOrEqualTo(1700);
        });
    }

    @Test
    @DisplayName("findCandidates honours the rating band and excludes solved problems")
    void candidatePoolRespectsBandAndExclusions() {
        // Mirrors the engine's band for a 1450-rated user: [1350, 1650].
        Set<Long> alreadySolved = Set.of(22L, 23L);

        List<Problem> candidates = problemRepository.findCandidates(1350, 1650, alreadySolved);

        assertThat(candidates).isNotEmpty();
        assertThat(candidates).allSatisfy(p -> assertThat(p.getRating()).isBetween(1350, 1650));
        assertThat(candidates).extracting(Problem::getId).doesNotContain(22L, 23L);
    }

    @Test
    @DisplayName("slugs are unique across the catalogue")
    void slugsAreUnique() {
        List<Problem> all = problemRepository.findAll();

        assertThat(all).extracting(Problem::getSlug).doesNotHaveDuplicates();
    }
}
