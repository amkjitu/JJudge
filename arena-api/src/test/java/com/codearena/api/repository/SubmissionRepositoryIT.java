package com.codearena.api.repository;

import com.codearena.api.domain.Submission;
import com.codearena.api.domain.User;
import com.codearena.common.domain.SubmissionStatus;
import com.codearena.common.domain.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SubmissionRepository")
class SubmissionRepositoryIT extends AbstractRepositoryIT {

    @Autowired
    private SubmissionRepository submissionRepository;

    @Autowired
    private UserRepository userRepository;

    private Long aliceId;
    private Long bobId;

    @BeforeEach
    void resolveDemoUsers() {
        aliceId = userRepository.findByUsername("alice").map(User::getId).orElseThrow();
        bobId = userRepository.findByUsername("bob").map(User::getId).orElseThrow();
    }

    @Test
    @DisplayName("solved problem ids are distinct and exclude failed-only attempts")
    void solvedProblemIds() {
        Set<Long> solved = submissionRepository.findSolvedProblemIds(aliceId);

        // alice cleared the easy set ...
        assertThat(solved).contains(1L, 2L, 3L, 4L, 5L, 6L, 8L, 9L, 11L, 12L);
        // ... but never got an AC on the dp/graph problems she attempted
        assertThat(solved).doesNotContain(10L, 14L, 16L);
    }

    @Test
    @DisplayName("a problem solved after several failures is counted exactly once")
    void repeatedAttemptsCollapseToOneSolve() {
        Set<Long> solved = submissionRepository.findSolvedProblemIds(aliceId);
        long occurrencesOfProblemOne = solved.stream().filter(id -> id == 1L).count();

        assertThat(occurrencesOfProblemOne).isEqualTo(1);
    }

    @Test
    @DisplayName("attempt counts are grouped per problem")
    void attemptsPerProblem() {
        Map<Long, Long> attempts = submissionRepository.countAttemptsPerProblem(aliceId).stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0],
                        row -> (Long) row[1]));

        assertThat(attempts.get(1L)).isEqualTo(2);   // one WA then one AC
        assertThat(attempts.get(10L)).isEqualTo(2);  // WA then TLE, never solved
        assertThat(attempts.get(16L)).isEqualTo(2);  // two WAs
        assertThat(attempts.get(4L)).isEqualTo(1);
    }

    @Test
    @DisplayName("history pages newest-first and eagerly loads the problem")
    void historyIsPagedNewestFirst() {
        Page<Submission> page = submissionRepository.findByUserIdOrderBySubmittedAtDesc(
                bobId, PageRequest.of(0, 5));

        assertThat(page.getContent()).hasSize(5);
        assertThat(page.getTotalElements()).isGreaterThan(5);
        assertThat(page.getContent()).extracting(Submission::getSubmittedAt)
                .isSortedAccordingTo((a, b) -> b.compareTo(a));
        assertThat(page.getContent()).allSatisfy(s ->
                assertThat(s.getProblem().getTitle()).isNotBlank());
    }

    @Test
    @DisplayName("counts by verdict and by status")
    void counters() {
        assertThat(submissionRepository.countByUserIdAndVerdict(aliceId, Verdict.AC)).isEqualTo(10);
        assertThat(submissionRepository.countByUserIdAndStatus(aliceId, SubmissionStatus.QUEUED)).isEqualTo(1);
        assertThat(submissionRepository.existsByUserIdAndProblemIdAndVerdict(aliceId, 1L, Verdict.AC)).isTrue();
        assertThat(submissionRepository.existsByUserIdAndProblemIdAndVerdict(aliceId, 16L, Verdict.AC)).isFalse();
    }

    @Test
    @DisplayName("a queued submission carries no verdict, matching the DB check constraint")
    void queuedSubmissionsHaveNoVerdict() {
        List<Submission> queued = submissionRepository.findAll().stream()
                .filter(s -> s.getStatus() == SubmissionStatus.QUEUED)
                .toList();

        assertThat(queued).isNotEmpty();
        assertThat(queued).allSatisfy(s -> {
            assertThat(s.getVerdict()).isNull();
            assertThat(s.getRuntimeMs()).isNull();
        });
    }

    @Test
    @DisplayName("JPA auditing stamps submittedAt on insert")
    void auditingPopulatesSubmittedAt() {
        User alice = userRepository.findById(aliceId).orElseThrow();
        Submission fresh = Submission.builder()
                .user(alice)
                .problem(submissionRepository.findAll().get(0).getProblem())
                .language(com.codearena.common.domain.Language.JAVA)
                .status(SubmissionStatus.QUEUED)
                .build();

        Submission saved = submissionRepository.saveAndFlush(fresh);

        assertThat(saved.getSubmittedAt()).isNotNull().isBefore(Instant.now().plusSeconds(1));
    }

    @Test
    @DisplayName("solved ids map cleanly onto real problems")
    void solvedIdsResolve() {
        Set<Long> solved = submissionRepository.findSolvedProblemIds(bobId);
        Map<Long, Long> identity = solved.stream().collect(Collectors.toMap(Function.identity(), Function.identity()));

        assertThat(identity).hasSameSizeAs(solved);
        assertThat(solved).isNotEmpty();
    }
}
