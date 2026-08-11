package com.codearena.api.ui;

import com.codearena.api.service.SubmissionService;
import com.codearena.api.sse.SubmissionStream;
import com.codearena.api.web.dto.SubmissionResponse;
import com.codearena.common.domain.SubmissionStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Controller
public class SubmissionUiController {

    private final SubmissionService submissionService;
    private final SubmissionStream submissionStream;

    public SubmissionUiController(SubmissionService submissionService,
                                  SubmissionStream submissionStream) {
        this.submissionService = submissionService;
        this.submissionStream = submissionStream;
    }

    @GetMapping("/submissions")
    public String mine(@PageableDefault(size = 20) Pageable pageable,
                       Authentication authentication,
                       Model model) {
        model.addAttribute("page", submissionService.findByUsername(authentication.getName(), pageable));
        return "submissions/list";
    }

    @GetMapping("/submissions/{id}")
    public String detail(@PathVariable Long id, Authentication authentication, Model model) {
        SubmissionResponse submission = submissionService.getById(id);

        // Source code is the author's alone. Anyone may see that a submission exists and what
        // verdict it got - that is on the problem page - but not the solution behind it.
        if (!submission.username().equals(authentication.getName())) {
            throw new AccessDeniedException("Submission " + id + " belongs to another user");
        }

        model.addAttribute("submission", submission);
        // Missing source is a normal state, not a 404. Seeded history predates the archive, and
        // an archive write can fail without failing the submission - in both cases the verdict,
        // the runtime and the problem are still worth showing. Refusing the whole page because
        // one panel has nothing in it loses the parts that do.
        submissionService.getSourceCode(id).ifPresent(source -> model.addAttribute("sourceCode", source));

        return "submissions/detail";
    }

    /**
     * The submission as JSON, for the page's own live-update script.
     *
     * <p>Exists for the same reason as the hint route on {@code ProblemUiController}: this page
     * authenticates with a session cookie, and {@code /api/**} is stateless and bearer-only, so
     * a {@code fetch} from here is anonymous there and gets a 401.
     */
    @GetMapping("/submissions/{id}/status")
    @ResponseBody
    public SubmissionResponse status(@PathVariable Long id, Authentication authentication) {
        SubmissionResponse submission = submissionService.getById(id);
        if (!submission.username().equals(authentication.getName())) {
            throw new AccessDeniedException("Submission " + id + " belongs to another user");
        }
        return submission;
    }

    /**
     * Live verdict updates for the page, over Server-Sent Events.
     *
     * <p>Registers the emitter first and re-reads afterwards, so a verdict that lands between the
     * two is still delivered and one that landed before the connection is replayed - judging can
     * finish in under two seconds, which a page that loads and then connects would otherwise miss.
     */
    @GetMapping(value = "/submissions/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable Long id, Authentication authentication) {
        SubmissionResponse submission = submissionService.getById(id);
        if (!submission.username().equals(authentication.getName())) {
            throw new AccessDeniedException("Submission " + id + " belongs to another user");
        }

        SseEmitter emitter = submissionStream.subscribe(id);

        SubmissionResponse current = submissionService.getById(id);
        if (current.status() == SubmissionStatus.DONE) {
            submissionStream.publish(current);
        }
        return emitter;
    }
}
