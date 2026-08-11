/**
 * Fetches a hint for the current problem, on demand.
 *
 * <p>Escalates: each press asks for a more specific hint, up to the maximum the server reports.
 * The button then says so rather than silently returning the same text, because a control that
 * looks live and does nothing is worse than one that admits it is finished.
 *
 * <p>The endpoint lives under /problems rather than /api because this page authenticates with a
 * session cookie, and the /api chain is stateless and bearer-only - a fetch from here would be
 * anonymous there.
 */
(function () {
    'use strict';

    var card = document.getElementById('hint-card');
    if (!card) {
        return;
    }

    var button = document.getElementById('hint-button');
    var output = document.getElementById('hint-output');
    var text = document.getElementById('hint-text');
    var meta = document.getElementById('hint-meta');
    var levelLabel = document.getElementById('hint-level');
    var sourceLabel = document.getElementById('hint-source');
    var url = card.getAttribute('data-hint-url');

    var level = 0;
    var maxLevel = 3;

    function show(message, muted) {
        output.classList.remove('d-none');
        text.textContent = message;
        text.className = muted ? 'mb-1 text-muted fst-italic' : 'mb-1';
    }

    function describeSource(source) {
        // Named honestly. HEURISTIC means a fixed library matched the problem's tags - useful,
        // but not a model that read this particular problem, and the reader should know which.
        return source === 'MODEL'
            ? 'generated for this problem'
            : 'from the built-in hint library';
    }

    button.addEventListener('click', function () {
        if (level >= maxLevel) {
            return;
        }

        var next = level + 1;
        button.disabled = true;
        button.textContent = 'Thinking…';

        fetch(url + '?level=' + next, {
            headers: {'Accept': 'application/json'},
            credentials: 'same-origin'
        })
            .then(function (response) {
                if (response.status === 503) {
                    throw new Error('The hint service is not available right now.');
                }
                if (!response.ok) {
                    throw new Error('Could not fetch a hint.');
                }
                return response.json();
            })
            .then(function (data) {
                level = data.level;
                maxLevel = data.maxLevel;

                show(data.hint, false);
                levelLabel.textContent = 'Hint ' + level + ' of ' + maxLevel;
                sourceLabel.textContent = describeSource(data.source);
                meta.classList.remove('d-none');

                if (level >= maxLevel) {
                    button.textContent = 'No more hints';
                } else {
                    button.textContent = 'Next hint';
                    button.disabled = false;
                }
            })
            .catch(function (error) {
                // A missing hint is not a broken page. Say what happened and let them retry.
                show(error.message, true);
                levelLabel.textContent = '';
                sourceLabel.textContent = '';
                meta.classList.add('d-none');
                button.textContent = 'Try again';
                button.disabled = false;
            });
    });
})();
