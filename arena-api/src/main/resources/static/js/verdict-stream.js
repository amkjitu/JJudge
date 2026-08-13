/*
 * Live verdict updates on the submission page.
 *
 * Opens an EventSource against the submission's SSE endpoint and rewrites the status badge when
 * the verdict lands, so a QUEUED submission becomes AC or WA without a reload.
 *
 * Progressive enhancement: the page is fully readable without this file. If SSE is unavailable
 * or the connection dies, a slow poll takes over rather than leaving the badge stale for ever.
 *
 * Both endpoints live under /submissions rather than /api. This page authenticates with a
 * session cookie, and the /api chain is stateless and bearer-only - pointing these at
 * /api/v1/submissions made every request anonymous there, so the stream and its poll fallback
 * both returned 401 and the badge never updated in a real browser. The tests did not catch it
 * because MockMvc injects the security context directly instead of going through the chain.
 */
(function () {
    'use strict';

    var root = document.getElementById('verdict-watch');
    if (!root || typeof EventSource === 'undefined') {
        return;
    }

    var submissionId = root.dataset.submissionId;
    var badge = document.getElementById('verdict-badge');
    var runtimeCell = document.getElementById('verdict-runtime');
    var notice = document.getElementById('verdict-notice');
    var methodSlot = document.getElementById('verdict-method');
    var simulatedNotice = document.getElementById('verdict-simulated-notice');

    var VERDICT_CLASSES = 'badge text-bg-secondary text-bg-success text-bg-danger';
    var POLL_INTERVAL_MS = 5000;
    var pollTimer = null;

    function classFor(verdict) {
        if (!verdict) {
            return 'badge text-bg-secondary';
        }
        return verdict === 'AC' ? 'badge text-bg-success' : 'badge text-bg-danger';
    }

    // Mirrors the judgedBy fragment in fragments/bits.html. Duplicated because the server renders
    // it on load and this renders it on arrival, and the two must agree - a verdict that arrives
    // live must not be presented as more trustworthy than the same verdict after a refresh.
    function renderMethod(judgedBy) {
        if (!methodSlot) {
            return;
        }
        methodSlot.textContent = '';
        if (judgedBy !== 'SIMULATED' && judgedBy !== 'EXECUTED') {
            return;
        }

        var pill = document.createElement('span');
        var simulated = judgedBy === 'SIMULATED';
        pill.className = simulated
            ? 'badge rounded-pill text-bg-warning'
            : 'badge rounded-pill text-bg-light border text-muted';
        // textContent, not innerHTML: nothing here comes from a user, and keeping it that way
        // means it cannot start to.
        pill.textContent = simulated ? 'Simulated' : 'Executed';
        methodSlot.appendChild(pill);

        if (simulatedNotice) {
            simulatedNotice.classList.toggle('d-none', !simulated);
        }
    }

    function render(submission) {
        if (badge) {
            badge.className = classFor(submission.verdict);
            badge.textContent = submission.verdict || submission.status;
        }
        if (runtimeCell && submission.runtimeMs != null) {
            runtimeCell.textContent = submission.runtimeMs + ' ms';
        }
        renderMethod(submission.judgedBy);
        if (notice && submission.status === 'DONE') {
            notice.remove();
        }
    }

    function isJudged(submission) {
        return submission && submission.status === 'DONE';
    }

    function stopPolling() {
        if (pollTimer !== null) {
            clearInterval(pollTimer);
            pollTimer = null;
        }
    }

    // Fallback for the cases SSE cannot cover: a proxy that buffers event streams, or - the
    // real one here - a verdict consumed by a different API replica than the one holding this
    // connection, since emitters live in a single JVM's heap.
    function startPolling() {
        if (pollTimer !== null) {
            return;
        }
        pollTimer = setInterval(function () {
            fetch('/submissions/' + encodeURIComponent(submissionId) + '/status', {
                headers: {Accept: 'application/json'}
            })
                .then(function (response) {
                    return response.ok ? response.json() : null;
                })
                .then(function (submission) {
                    if (isJudged(submission)) {
                        render(submission);
                        stopPolling();
                    }
                })
                .catch(function () {
                    /* transient; the next tick tries again */
                });
        }, POLL_INTERVAL_MS);
    }

    var source = new EventSource('/submissions/' + encodeURIComponent(submissionId) + '/stream');

    source.addEventListener('verdict', function (event) {
        try {
            render(JSON.parse(event.data));
        } catch (e) {
            startPolling();
            return;
        }
        stopPolling();
        // The server closes after one verdict; closing this end too stops EventSource from
        // treating that as a dropped connection and reconnecting in a loop.
        source.close();
    });

    source.onerror = function () {
        // Fires both on a transient drop and on the server's deliberate close. Polling covers
        // the former and costs one wasted request in the latter.
        startPolling();
    };
})();
