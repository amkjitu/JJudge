package com.codearena.api.service;

import com.codearena.api.domain.Problem;
import com.codearena.api.domain.Tag;
import com.codearena.api.repository.ProblemRepository;
import com.codearena.api.repository.TagRepository;
import com.codearena.api.web.dto.CreateProblemRequest;
import com.codearena.api.web.dto.ProblemDetailResponse;
import com.codearena.api.web.dto.UpdateProblemRequest;
import com.codearena.api.web.error.DuplicateResourceException;
import com.codearena.api.web.error.ResourceNotFoundException;
import com.codearena.api.web.mapper.ProblemMapperImpl;
import com.codearena.common.domain.Difficulty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The mapper is the real generated implementation rather than a mock: it is pure logic with no
 * collaborators, and mocking it would leave the DTO assertions testing nothing.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProblemService")
class ProblemServiceTest {

    @Mock
    private ProblemRepository problemRepository;

    @Mock
    private TagRepository tagRepository;

    @Mock
    private ProblemStatementService statementService;

    private ProblemService problemService;

    @BeforeEach
    void setUp() {
        problemService = new ProblemService(problemRepository, tagRepository,
                new ProblemMapperImpl(), statementService);
    }

    private static Tag tag(long id, String name) {
        return Tag.builder().id(id).name(name).build();
    }

    private static CreateProblemRequest createRequest(int rating, Set<String> tags) {
        return new CreateProblemRequest("Some Problem", "some-problem", rating, null, null, tags);
    }

    @Test
    @DisplayName("derives difficulty from rating instead of trusting the caller")
    void derivesDifficulty() {
        when(problemRepository.existsBySlug("some-problem")).thenReturn(false);
        when(tagRepository.findAllByNameIn(anyCollection())).thenReturn(List.of(tag(1L, "dp")));
        when(problemRepository.save(any(Problem.class))).thenAnswer(inv -> inv.getArgument(0));

        ProblemDetailResponse created = problemService.create(createRequest(1850, Set.of("dp")));

        assertThat(created.difficulty()).isEqualTo(Difficulty.HARD);
        assertThat(created.rating()).isEqualTo(1850);

        ArgumentCaptor<Problem> saved = ArgumentCaptor.forClass(Problem.class);
        verify(problemRepository).save(saved.capture());
        assertThat(saved.getValue().getDifficulty()).isEqualTo(Difficulty.HARD);
    }

    @Test
    @DisplayName("applies the documented limit defaults when they are omitted")
    void appliesLimitDefaults() {
        when(problemRepository.existsBySlug(anyString())).thenReturn(false);
        when(tagRepository.findAllByNameIn(anyCollection())).thenReturn(List.of(tag(1L, "dp")));
        when(problemRepository.save(any(Problem.class))).thenAnswer(inv -> inv.getArgument(0));

        ProblemDetailResponse created = problemService.create(createRequest(1000, Set.of("dp")));

        assertThat(created.timeLimitMs()).isEqualTo(1000);
        assertThat(created.memoryLimitMb()).isEqualTo(256);
    }

    @Test
    @DisplayName("rejects a duplicate slug before touching the database")
    void rejectsDuplicateSlug() {
        when(problemRepository.existsBySlug("some-problem")).thenReturn(true);

        assertThatThrownBy(() -> problemService.create(createRequest(1000, Set.of("dp"))))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("some-problem");

        verify(problemRepository, never()).save(any());
    }

    @Test
    @DisplayName("rejects unknown tags rather than silently dropping them")
    void rejectsUnknownTags() {
        when(problemRepository.existsBySlug(anyString())).thenReturn(false);
        // only 'dp' resolves; 'quantum-computing' does not exist
        when(tagRepository.findAllByNameIn(anyCollection())).thenReturn(List.of(tag(1L, "dp")));

        assertThatThrownBy(() ->
                problemService.create(createRequest(1000, Set.of("dp", "quantum-computing"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quantum-computing");

        verify(problemRepository, never()).save(any());
    }

    @Test
    @DisplayName("normalises tag names to lower case before resolving them")
    void normalisesTagNames() {
        when(problemRepository.existsBySlug(anyString())).thenReturn(false);
        when(tagRepository.findAllByNameIn(anyCollection())).thenReturn(List.of(tag(1L, "dp")));
        when(problemRepository.save(any(Problem.class))).thenAnswer(inv -> inv.getArgument(0));

        problemService.create(createRequest(1000, Set.of("  DP  ")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> names = ArgumentCaptor.forClass(Collection.class);
        verify(tagRepository).findAllByNameIn(names.capture());
        assertThat(names.getValue()).containsExactly("dp");
    }

    @Test
    @DisplayName("update recomputes difficulty and leaves the slug alone")
    void updateRecomputesDifficulty() {
        Problem existing = Problem.builder()
                .id(7L)
                .title("Old title")
                .slug("some-problem")
                .rating(900)
                .difficulty(Difficulty.EASY)
                .tags(new LinkedHashSet<>(Set.of(tag(1L, "dp"))))
                .build();
        when(problemRepository.findBySlug("some-problem")).thenReturn(Optional.of(existing));
        when(tagRepository.findAllByNameIn(anyCollection())).thenReturn(List.of(tag(2L, "graph")));

        ProblemDetailResponse updated = problemService.update("some-problem",
                new UpdateProblemRequest("New title", 1750, 3000, 512, Set.of("graph")));

        assertThat(updated.slug()).isEqualTo("some-problem");
        assertThat(updated.title()).isEqualTo("New title");
        assertThat(updated.difficulty()).isEqualTo(Difficulty.HARD);
        assertThat(updated.timeLimitMs()).isEqualTo(3000);
        assertThat(updated.tags()).containsExactly("graph");
    }

    @Test
    @DisplayName("getBySlug reports a missing problem as not found")
    void missingProblem() {
        when(problemRepository.findBySlug("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> problemService.getDetail("nope"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("nope");
    }

    @Test
    @DisplayName("the detail view joins the Markdown statement on from MongoDB")
    void detailCarriesTheStatement() {
        when(problemRepository.findBySlug("some-problem")).thenReturn(Optional.of(problem()));
        when(statementService.markdownFor("some-problem")).thenReturn(Optional.of("# Statement"));

        ProblemDetailResponse detail = problemService.getDetail("some-problem");

        assertThat(detail.statementMarkdown()).isEqualTo("# Statement");
        assertThat(detail.slug()).isEqualTo("some-problem");
    }

    @Test
    @DisplayName("a problem with no statement document is still a complete problem")
    void detailWithoutAStatement() {
        // The relational record is what makes a problem real. Prose is an enrichment, so its
        // absence leaves the field null rather than failing the request - which is also what
        // happens when MongoDB is unreachable.
        when(problemRepository.findBySlug("some-problem")).thenReturn(Optional.of(problem()));
        when(statementService.markdownFor("some-problem")).thenReturn(Optional.empty());

        ProblemDetailResponse detail = problemService.getDetail("some-problem");

        assertThat(detail.statementMarkdown()).isNull();
        assertThat(detail.rating()).isEqualTo(1200);
    }

    private static Problem problem() {
        return Problem.builder()
                .id(1L)
                .title("Some Problem")
                .slug("some-problem")
                .rating(1200)
                .difficulty(Difficulty.MEDIUM)
                .timeLimitMs(1000)
                .memoryLimitMb(256)
                .tags(new LinkedHashSet<>(Set.of(tag(1L, "dp"))))
                .build();
    }
}
