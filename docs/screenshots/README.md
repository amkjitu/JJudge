# Screenshots

Images referenced by the root `README.md`. All captured from the running compose stack with the
seeded demo data — nothing mocked up or hand-edited.

| File | What it shows |
|---|---|
| `hero.png` | Dashboard as `bob`: standing, weakest topics, and the recommendation panel |
| `problem-detail.png` | Problem detail — MongoDB statement rendered from Markdown, worked examples, CodeMirror editor, editorial behind a spoiler |
| `ai-hint.png` | A hint with its provenance line, showing whether a model or the built-in library answered |
| `recommendations.png` | The recommendation cards close up, each naming its targeted weak topic |
| `problem-list.png` | Catalogue with tag and difficulty filters applied |
| `profile-charts.png` | Profile: solved-by-tag and progress-over-time |
| `leaderboard.png` | Redis-backed ranking with solve counts joined from PostgreSQL |
| `swagger.png` | OpenAPI UI at `/swagger-ui.html` |

## Recapturing

The stack must be up (`docker compose up -d --build`) and seeded. The capture script drives the
system Chrome through puppeteer-core, logs in through the real form, and writes the PNGs here:

```bash
node docs/screenshots/capture.mjs
```

Viewport is 1440×900 with browser chrome excluded, which is what keeps the set visually
consistent. The first page load after a cold start can take half a minute on a modest machine —
the script's timeouts allow for that, so a slow first shot is not a failure.

## No submit-to-verdict GIF

An earlier plan here called for an animated capture of a submission flipping from `QUEUED` to a
verdict over SSE. There is no GIF, and the reason is worth stating rather than leaving as a
gap: judging completes in about two seconds, so the interesting part is a badge changing once.
A four-megabyte animation to show that is a poor trade against a sentence saying it, and the
behaviour is covered where it can actually be checked — by `SubmissionPipelineIT`, and by the
`compose` job in CI, which submits a solution and waits for the verdict.
