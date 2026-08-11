package com.codearena.api.service;

import com.codearena.api.domain.Problem;
import com.codearena.api.domain.Tag;
import com.codearena.api.repository.ProblemRepository;
import com.codearena.api.repository.TagRepository;
import com.codearena.api.repository.spec.ProblemSpecifications;
import com.codearena.api.web.dto.CreateProblemRequest;
import com.codearena.api.web.dto.ProblemDetailResponse;
import com.codearena.api.web.dto.ProblemSummaryResponse;
import com.codearena.api.web.dto.UpdateProblemRequest;
import com.codearena.api.web.error.DuplicateResourceException;
import com.codearena.api.web.error.ResourceNotFoundException;
import com.codearena.api.web.mapper.ProblemMapper;
import com.codearena.common.domain.Difficulty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Note that the public read methods return DTOs, not entities.
 *
 * <p>{@code spring.jpa.open-in-view} is off, so the persistence session closes when this
 * method returns. Handing a controller an entity with an uninitialised lazy collection would
 * mean the mapping blows up with {@code LazyInitializationException} at serialisation time -
 * far from the code that caused it. Mapping inside the transaction makes the transaction
 * boundary and the "what is loaded" boundary the same line.
 */
@Service
@Transactional(readOnly = true)
public class ProblemService {

    private static final int DEFAULT_TIME_LIMIT_MS = 1000;
    private static final int DEFAULT_MEMORY_LIMIT_MB = 256;

    private final ProblemRepository problemRepository;
    private final TagRepository tagRepository;
    private final ProblemMapper problemMapper;
    private final ProblemStatementService statements;

    public ProblemService(ProblemRepository problemRepository,
                          TagRepository tagRepository,
                          ProblemMapper problemMapper,
                          ProblemStatementService statements) {
        this.problemRepository = problemRepository;
        this.tagRepository = tagRepository;
        this.problemMapper = problemMapper;
        this.statements = statements;
    }

    public Page<ProblemSummaryResponse> search(ProblemFilter filter, Pageable pageable) {
        Specification<Problem> spec = Specification
                .where(ProblemSpecifications.hasTag(filter.tag()))
                .and(ProblemSpecifications.hasDifficulty(filter.difficulty()))
                .and(ProblemSpecifications.ratingAtLeast(filter.minRating()))
                .and(ProblemSpecifications.ratingAtMost(filter.maxRating()))
                .and(ProblemSpecifications.titleOrSlugContains(filter.search()));

        return problemRepository.findAll(spec, pageable).map(problemMapper::toSummary);
    }

    /**
     * The full problem view: the relational record from PostgreSQL, with the Markdown statement
     * joined on from MongoDB.
     *
     * <p>The two stores are read separately and stitched here rather than being made to look
     * like one query. That is the real cost of splitting a problem across two databases, and
     * hiding it behind a clever abstraction would only make the second read harder to notice.
     * It is one lookup by primary key, on the one page that needs prose.
     */
    public ProblemDetailResponse getDetail(String slug) {
        ProblemDetailResponse detail = problemMapper.toDetail(getBySlug(slug));
        return statements.markdownFor(slug)
                .map(detail::withStatement)
                .orElse(detail);
    }

    /**
     * Entity-returning lookup for callers that stay inside the transaction, such as
     * {@link SubmissionService} needing a {@link Problem} to associate. Not exposed over HTTP.
     */
    public Problem getBySlug(String slug) {
        return problemRepository.findBySlug(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Problem", slug));
    }

    @Transactional
    public ProblemDetailResponse create(CreateProblemRequest request) {
        if (problemRepository.existsBySlug(request.slug())) {
            throw new DuplicateResourceException("Problem", request.slug());
        }

        Problem problem = Problem.builder()
                .title(request.title())
                .slug(request.slug())
                .rating(request.rating())
                // Never taken from the request: difficulty is a projection of rating.
                .difficulty(Difficulty.fromRating(request.rating()))
                .timeLimitMs(orDefault(request.timeLimitMs(), DEFAULT_TIME_LIMIT_MS))
                .memoryLimitMb(orDefault(request.memoryLimitMb(), DEFAULT_MEMORY_LIMIT_MB))
                .tags(resolveTags(request.tags()))
                .build();

        return problemMapper.toDetail(problemRepository.save(problem));
    }

    @Transactional
    public ProblemDetailResponse update(String slug, UpdateProblemRequest request) {
        Problem problem = getBySlug(slug);

        problem.setTitle(request.title());
        problem.setRating(request.rating());
        problem.setDifficulty(Difficulty.fromRating(request.rating()));
        problem.setTimeLimitMs(orDefault(request.timeLimitMs(), DEFAULT_TIME_LIMIT_MS));
        problem.setMemoryLimitMb(orDefault(request.memoryLimitMb(), DEFAULT_MEMORY_LIMIT_MB));

        // Mutate the managed collection rather than replacing it, so Hibernate can diff the
        // join table instead of clearing and reinserting every row.
        Set<Tag> resolved = resolveTags(request.tags());
        problem.getTags().retainAll(resolved);
        problem.getTags().addAll(resolved);

        return problemMapper.toDetail(problem);
    }

    @Transactional
    public void delete(String slug) {
        problemRepository.delete(getBySlug(slug));
    }

    /**
     * Resolves tag names to entities, failing loudly on anything unknown. Silently dropping an
     * unrecognised tag would leave the caller believing a filterable tag had been applied.
     */
    private Set<Tag> resolveTags(Set<String> names) {
        Set<String> requested = names.stream()
                .map(String::trim)
                .map(String::toLowerCase)
                .collect(Collectors.toCollection(TreeSet::new));

        List<Tag> found = tagRepository.findAllByNameIn(requested);
        if (found.size() != requested.size()) {
            Set<String> known = found.stream().map(Tag::getName).collect(Collectors.toSet());
            Set<String> unknown = new TreeSet<>(requested);
            unknown.removeAll(known);
            throw new IllegalArgumentException("Unknown tags: " + String.join(", ", unknown));
        }
        return new LinkedHashSet<>(found);
    }

    private static int orDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}
