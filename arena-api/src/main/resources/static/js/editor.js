/*
 * Upgrades the plain <textarea> on the problem page into a CodeMirror editor.
 *
 * Progressive enhancement on purpose: the form posts the textarea's value, so with JavaScript
 * disabled (or if a CodeMirror asset fails to load) the page degrades to a working plain text
 * area rather than a submit button that silently sends nothing.
 */
(function () {
    'use strict';

    var textarea = document.getElementById('sourceCode');
    var languageSelect = document.getElementById('language');
    if (!textarea || typeof CodeMirror === 'undefined') {
        return;
    }

    var MODES = {
        JAVA: 'text/x-java',
        CPP: 'text/x-c++src',
        PYTHON: 'text/x-python',
        GO: 'text/x-go',
        JAVASCRIPT: 'text/javascript'
    };

    var STARTERS = {
        JAVA: 'import java.util.*;\nimport java.io.*;\n\npublic class Main {\n    public static void main(String[] args) throws IOException {\n        \n    }\n}\n',
        CPP: '#include <bits/stdc++.h>\nusing namespace std;\n\nint main() {\n    ios::sync_with_stdio(false);\n    cin.tie(nullptr);\n    \n    return 0;\n}\n',
        PYTHON: 'import sys\n\ndef main():\n    data = sys.stdin.read().split()\n    \n\nmain()\n',
        GO: 'package main\n\nimport (\n    "bufio"\n    "fmt"\n    "os"\n)\n\nfunc main() {\n    reader := bufio.NewReader(os.Stdin)\n    _ = reader\n    _ = fmt.Sprint\n}\n',
        JAVASCRIPT: 'const data = require("fs").readFileSync(0, "utf8").split(/\\s+/);\n\n'
    };

    function modeFor(language) {
        return MODES[language] || 'text/plain';
    }

    var editor = CodeMirror.fromTextArea(textarea, {
        lineNumbers: true,
        indentUnit: 4,
        tabSize: 4,
        indentWithTabs: false,
        autoCloseBrackets: true,
        matchBrackets: true,
        theme: 'material-darker',
        mode: modeFor(languageSelect ? languageSelect.value : 'JAVA'),
        extraKeys: {
            // Tab inserts spaces rather than moving focus, which is what anyone typing code
            // expects; Shift-Tab still escapes the editor for keyboard navigation.
            Tab: function (cm) {
                cm.replaceSelection('    ', 'end');
            }
        }
    });
    editor.setSize(null, 420);

    // CodeMirror 5 writes back to the underlying textarea on form submit, but only if the form
    // submit event fires through the DOM - which it does here. save() makes it explicit.
    var form = document.getElementById('submit-form');
    if (form) {
        form.addEventListener('submit', function () {
            editor.save();
        });
    }

    if (languageSelect) {
        languageSelect.addEventListener('change', function () {
            var language = languageSelect.value;
            editor.setOption('mode', modeFor(language));

            // Only swap in a starter template when the editor is effectively empty, so
            // switching language never destroys work in progress.
            if (editor.getValue().trim() === '' && STARTERS[language]) {
                editor.setValue(STARTERS[language]);
            }
        });
    }

    if (editor.getValue().trim() === '') {
        editor.setValue(STARTERS[languageSelect ? languageSelect.value : 'JAVA'] || '');
    }
})();
