/*
 * Profile page charts.
 *
 * Data arrives as two JSON *strings* on window.arenaChartData, serialised server-side and
 * injected through th:inline="javascript" so Thymeleaf does the escaping. Parsing here keeps
 * the template free of any hand-built JavaScript literals.
 */
(function () {
    'use strict';

    if (typeof Chart === 'undefined' || !window.arenaChartData) {
        return;
    }

    function parse(value) {
        try {
            return typeof value === 'string' ? JSON.parse(value) : (value || []);
        } catch (e) {
            return [];
        }
    }

    var solvedByTag = parse(window.arenaChartData.solvedByTag);
    var progress = parse(window.arenaChartData.progress);

    var tagCanvas = document.getElementById('tagChart');
    if (tagCanvas && solvedByTag.length > 0) {
        new Chart(tagCanvas, {
            type: 'bar',
            data: {
                labels: solvedByTag.map(function (d) { return d.tag; }),
                datasets: [{
                    label: 'Problems solved',
                    data: solvedByTag.map(function (d) { return d.solved; }),
                    backgroundColor: 'rgba(13, 110, 253, 0.7)',
                    borderRadius: 3
                }]
            },
            options: {
                indexAxis: 'y',
                responsive: true,
                maintainAspectRatio: false,
                plugins: { legend: { display: false } },
                // Solve counts are whole numbers; the default tick generator would otherwise
                // offer 0.5 of a problem.
                scales: { x: { beginAtZero: true, ticks: { precision: 0 } } }
            }
        });
    } else if (tagCanvas) {
        emptyState(tagCanvas, 'No solved problems yet.');
    }

    var progressCanvas = document.getElementById('progressChart');
    if (progressCanvas && progress.length > 0) {
        new Chart(progressCanvas, {
            type: 'line',
            data: {
                labels: progress.map(function (d) { return d.month; }),
                datasets: [{
                    label: 'Cumulative solved',
                    data: progress.map(function (d) { return d.cumulativeSolved; }),
                    borderColor: 'rgba(25, 135, 84, 1)',
                    backgroundColor: 'rgba(25, 135, 84, 0.15)',
                    fill: true,
                    tension: 0.25,
                    pointRadius: 2
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                plugins: { legend: { display: false } },
                scales: { y: { beginAtZero: true, ticks: { precision: 0 } } }
            }
        });
    } else if (progressCanvas) {
        emptyState(progressCanvas, 'No solves recorded yet.');
    }

    function emptyState(canvas, message) {
        var placeholder = document.createElement('p');
        placeholder.className = 'text-muted text-center my-5';
        placeholder.textContent = message;
        canvas.replaceWith(placeholder);
    }
})();
