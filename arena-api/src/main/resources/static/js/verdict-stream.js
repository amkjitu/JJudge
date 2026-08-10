/*
 * Live verdict updates on the submission page.
 *
 * Opens an EventSource against the submission's SSE endpoint and rewrites the status badge when
 * the verdict lands, so a QUEUED submission becomes AC or WA without a reload.
 *
 * Progressive enhancement: the page is fully readable without this file. If SSE is unavailable
 * or the connection dies, a slow poll takes over rather than leaving the badge stale for ever.
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

    var VERDICT_CLASSES = 'badge text-bg-secondary text-bg-success text-bg-danger';
    var POLL_INTERVAL_MS = 5000;
    var pollTimer = null;

    function classFor(verdict) {
        if (!verdict) {
            return 'badge text-bg-secondary';
        }
        return verdict === 'AC' ? 'badge text-bg-success' : 'badge text-bg-danger';
    }

    function render(submission) {
        if (badge) {
            badge.className = classFor(submission.verdict);
            badge.textContent = submission.verdict || submission.status;
        }
        if (runtimeCell && submission.runtimeMs != null) {
            runtimeCell.textContent = submission.runtimeMs + ' ms';
        }
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
            fetch('/api/v1/submissions/' + encodeURIComponent(submissionId), {
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

    var source = new EventSource('/api/v1/submissions/' + encodeURIComponent(submissionId) + '/stream');

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
