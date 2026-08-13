/*
 * Add and remove rows on the admin authoring forms.
 *
 * Spring binds indexed properties by path - examples[2].input - so a new row has to carry the
 * next index in its name and id attributes. Cloning the last row and rewriting those indices is
 * the whole trick, and it is why the server side uses AutoPopulatingList: a plain ArrayList
 * throws IndexOutOfBoundsException the moment a path names an index it does not yet hold.
 *
 * Progressive enhancement. Without this file the forms still submit and still save every row the
 * server rendered; only the ability to add more is lost. That is why the markup is rendered by
 * Thymeleaf rather than built here.
 */
(function () {
    'use strict';

    function rewriteIndex(row, field, index) {
        row.querySelectorAll('[name], [id], [for]').forEach(function (el) {
            ['name', 'id', 'for'].forEach(function (attribute) {
                var value = el.getAttribute(attribute);
                if (value) {
                    // Only this form's field, so an unrelated indexed input is left alone.
                    el.setAttribute(attribute,
                        value.replace(new RegExp(field + '\\[\\d+\\]', 'g'), field + '[' + index + ']')
                             .replace(/^sample-\d+$/, 'sample-' + index));
                }
            });
        });
    }

    function blank(row) {
        row.querySelectorAll('textarea').forEach(function (el) {
            el.value = '';
        });
        row.querySelectorAll('input[type="checkbox"]').forEach(function (el) {
            el.checked = false;
        });
        // Hidden companions to checkboxes carry Spring's "false" fallback; leave them in place.
        row.querySelectorAll('.is-invalid').forEach(function (el) {
            el.classList.remove('is-invalid');
        });
        row.querySelectorAll('.invalid-feedback').forEach(function (el) {
            el.remove();
        });
    }

    function renumber(container, rowClass, field) {
        var rows = container.querySelectorAll('.' + rowClass);
        rows.forEach(function (row, i) {
            rewriteIndex(row, field, i);
            var counter = row.querySelector('.badge span');
            if (counter) {
                counter.textContent = i + 1;
            }
        });
    }

    window.arenaRows = function (options) {
        var container = document.getElementById(options.container);
        var addButton = document.getElementById(options.addButton);
        if (!container || !addButton) {
            return;
        }

        addButton.addEventListener('click', function () {
            var rows = container.querySelectorAll('.' + options.rowClass);
            if (rows.length === 0) {
                // Nothing to clone from. A reload renders one empty row server-side, which is a
                // far smaller thing to get right than building the markup twice.
                window.location.reload();
                return;
            }
            var copy = rows[rows.length - 1].cloneNode(true);
            blank(copy);
            container.appendChild(copy);
            renumber(container, options.rowClass, options.field);
            copy.querySelector('textarea').focus();
        });

        // Delegated, so it applies to rows added after this ran.
        container.addEventListener('click', function (event) {
            if (!event.target.classList.contains('remove-row')) {
                return;
            }
            var rows = container.querySelectorAll('.' + options.rowClass);
            if (rows.length === 1) {
                // Removing the only row would leave nothing to clone. Emptying it has the same
                // effect on save, since wholly blank rows are discarded server-side.
                blank(rows[0]);
                return;
            }
            event.target.closest('.' + options.rowClass).remove();
            renumber(container, options.rowClass, options.field);
        });
    };
})();
