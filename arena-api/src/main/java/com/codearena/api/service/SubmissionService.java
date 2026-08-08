package com.codearena.api.service;

import com.codearena.api.domain.Problem;
import com.codearena.api.domain.Submission;
import com.codearena.api.domain.User;
import com.codearena.api.repository.SubmissionRepository;
import com.codearena.api.repository.UserRepository;
import com.codearena.api.web.dto.CreateSubmissionRequest;
import com.codearena.api.web.dto.SubmissionResponse;
import com.codearena.api.web.error.ResourceNotFoundException;
import com.codearena.api.web.mapper.SubmissionMapper;
import com.codearena.common.domain.SubmissionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Like {@link ProblemService}, this returns DTOs rather than entities: with open-in-view
 * disabled, a {@code Submission} handed back to a controller carries lazy {@code user} and
 * {@code problem} proxies that can no longer be resolved.
 */
@Service
@Transactional(readOnly = true)
public class SubmissionService {

    private static final Logger log = LoggerFactory.getLogger(SubmissionService.class);

    private final SubmissionRepository submissionRepository;
    private final UserRepository userRepository;
    private final ProblemService problemService;
    private final SubmissionSourceStore sourceStore;
    private final SubmissionMapper submissionMapper;

    public SubmissionService(SubmissionRepository submissionRepository,
                             UserRepository userRepository,
                             ProblemService problemService,
                             SubmissionSourceStore sourceStore,
                             SubmissionMapper submissionMapper) {
        this.submissionRepository = submissionRepository;
        this.userRepository = userRepository;
        this.problemService = problemService;
        this.sourceStore = sourceStore;
        this.submissionMapper = submissionMapper;
    }

    /**
     * Records an attempt and leaves it {@code QUEUED}.
     *
     * <p>Nothing judges it yet - Phase 6 publishes a {@code SubmissionCreated} event here and
     * the worker moves it to {@code RUNNING} then {@code DONE}. The status enum already models
     * that lifecycle, so no schema change is needed when the pipeline lands.
     *
     * @param username the caller, resolved by {@link CurrentUserProvider} - never taken from
     *                 the request body
     */
    @Transactional
    public SubmissionResponse create(String username, CreateSubmissionRequest request) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User", username));
        Problem problem = problemService.getBySlug(request.problemSlug());

        Submission submission = submissionRepository.save(Submission.builder()
                .user(user)
                .problem(problem)
                .language(request.language())
                .status(SubmissionStatus.QUEUED)
                .build());

        sourceStore.store(submission.getId(), request.language(), request.sourceCode());

        log.info("Queued submission {} for user '{}' on problem '{}'",
                submission.getId(), username, problem.getSlug());

        return submissionMapper.toResponse(submission);
    }

    public SubmissionResponse getById(Long id) {
        return submissionMapper.toResponse(requireSubmission(id));
    }

    public Optional<String> getSourceCode(Long submissionId) {
        // Confirms the submission exists so a missing source and a missing submission are not
        // conflated into the same empty result.
        requireSubmission(submissionId);
        return sourceStore.find(submissionId);
    }

    public Page<SubmissionResponse> findByUsername(String username, Pageable pageable) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User", username));
        return submissionRepository.findByUserIdOrderBySubmittedAtDesc(user.getId(), pageable)
                .map(submissionMapper::toResponse);
    }

    public Page<SubmissionResponse> findByProblemSlug(String slug, Pageable pageable) {
        Problem problem = problemService.getBySlug(slug);
        return submissionRepository.findByProblemIdOrderBySubmittedAtDesc(problem.getId(), pageable)
                .map(submissionMapper::toResponse);
    }

    /**
     * Ids of every problem the user has solved. The problem list uses this to tick the solved
     * column: one query for the whole page rather than a lookup per row.
     */
    public Set<Long> solvedProblemIds(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User", username));
        return submissionRepository.findSolvedProblemIds(user.getId());
    }

    /**
     * One user's attempts at one problem, newest first. Bounded to the most recent handful -
     * this feeds a sidebar on the problem page, not a paginated history.
     */
    public List<SubmissionResponse> findByUsernameAndProblem(String username, String slug) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User", username));
        Problem problem = problemService.getBySlug(slug);

        return submissionRepository
                .findTop10ByUserIdAndProblemIdOrderBySubmittedAtDesc(user.getId(), problem.getId())
                .stream()
                .map(submissionMapper::toResponse)
                .toList();
    }

    private Submission requireSubmission(Long id) {
        return submissionRepository.findWithDetailsById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Submission", id));
    }
}
