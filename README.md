# CodeArena

[![Build](https://img.shields.io/badge/build-passing-brightgreen)](#testing)
[![Java](https://img.shields.io/badge/Java-17-orange)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-brightgreen)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

A competitive-programming practice platform: browse problems, submit solutions, get
asynchronously judged verdicts, climb a leaderboard — and get told *what to solve next* by a
recommendation engine that models your per-topic proficiency.

> **Status: Phase 6 of 8 complete.** Domain model, migrations, seed data, a versioned REST API
> with RFC 7807 errors and OpenAPI docs, JWT + OAuth2 authentication with rotating refresh
> tokens and per-user rate limiting, and a server-rendered Thymeleaf UI with a CodeMirror
> editor and Chart.js progress charts, the recommendation engine with a Redis-backed
> leaderboard, and an async judging pipeline over Kafka with live verdicts over SSE — all
> running in Docker. The build order and what each phase adds is in [Roadmap](#roadmap).

<!-- TODO(phase-8): hero screenshot / GIF goes here -->
<!-- TODO(phase-8): live demo link + demo credentials -->

---

## Table of contents

- [Why this project](#why-this-project)
- [Architecture](#architecture)
- [Tech stack](#tech-stack)
- [Data model](#data-model)
- [The recommendation algorithm](#the-recommendation-algorithm)
- [Getting started](#getting-started)
- [API](#api)
- [Security](#security)
- [Web UI](#web-ui)
- [Redis](#redis)
- [The judging pipeline](#the-judging-pipeline)
- [Testing](#testing)
- [Project structure](#project-structure)
- [Roadmap](#roadmap)
- [License](#license)

---

## Why this project

Most portfolio backends are a CRUD app with a login screen. This one has a genuine algorithm
at its centre — a recommender with a documented scoring function, a bounded-heap top-N
selection, and a topologically sorted prerequisite graph — plus an async event pipeline that
actually needs a message broker rather than having one bolted on for show.

---

## Architecture

```
                    ┌────────────────────┐
   Browser  ───────▶│   arena-api        │  Spring Boot (web + REST)
   (Thymeleaf UI)   │   Security / JWT   │
                    │   JPA  ├─▶ Postgres│  users, problems, submissions, stats
                    │        ├─▶ MongoDB │  statements, editorials, source code
                    │        └─▶ Redis   │  leaderboard cache, rate limits
                    └────────┬───────────┘
                             │ publish  SubmissionCreated
                             ▼
                        ┌─────────┐
                        │  Kafka  │
                        └────┬────┘
                             │ consume
                    ┌────────▼───────────┐
                    │  arena-judge       │  evaluates in a thread pool
                    └────────┬───────────┘
                             │ publish  VerdictAssigned  ──▶ back to arena-api ──▶ SSE
                    ┌────────────────────┐
                    │  arena-ai          │  Spring AI: hints, complexity analysis
                    └────────────────────┘
```

Four Maven modules: `arena-common` (shared enums and event contracts), `arena-api`,
`arena-judge`, `arena-ai`.

---

## Tech stack

| Area | Choice |
|---|---|
| Language / runtime | Java 17, Spring Boot 3.3.5 |
| Build | Multi-module Maven, Maven Wrapper (`./mvnw` — no local Maven needed) |
| Relational data | PostgreSQL 16, Spring Data JPA, Hibernate 6, Flyway migrations |
| Document data | MongoDB *(Phase 7)* |
| Cache / ranking | Redis sorted sets for ranking, Lua token bucket for rate limiting |
| Messaging | Apache Kafka in KRaft mode, JSON events, at-least-once with idempotent consumers |
| Security | Spring Security 6, BCrypt, HS256 JWT access tokens, rotating opaque refresh tokens, OAuth2 Google |
| Web UI | Thymeleaf, Bootstrap 5, CodeMirror, Chart.js — served as WebJars, no CDN |
| AI | Spring AI with Ollama, provider-configurable to OpenAI *(Phase 7)* |
| API | Versioned `/api/v1`, MapStruct DTO mapping, Bean Validation, RFC 7807 errors |
| API docs | springdoc-openapi at `/swagger-ui.html` |
| Testing | JUnit 5, AssertJ, Mockito, Testcontainers |
| Infra | Multi-stage Docker builds, Docker Compose, GitHub Actions *(Phase 8)* |

---

## Data model

**PostgreSQL** (owned by Flyway, `arena-api/src/main/resources/db/migration`):

| Table | Purpose |
|---|---|
| `users` | credentials, role, Elo-style `rating` |
| `problems` | title, slug, difficulty bucket, numeric `rating`, limits |
| `tags` | topic taxonomy (30 tags) |
| `problem_tags` | problem ↔ tag many-to-many |
| `tag_prerequisites` | directed edges of the prerequisite DAG over tags |
| `submissions` | one row per attempt: language, status, verdict, runtime |
| `user_tag_stats` | denormalised per-user, per-tag solved/attempt counters |
| `refresh_tokens` | hashed, revocable refresh tokens with rotation lineage |

Design notes worth calling out:

- **Enums are `varchar` + `CHECK`, not native PG enums.** Adding a value stays an ordinary
  migration instead of an `ALTER TYPE` that cannot run inside a transaction.
- **`submissions` has a cross-column check**: a verdict exists *exactly when* status is
  `DONE`. The illegal intermediate state is unrepresentable rather than merely discouraged.
- **`user_tag_stats` counts attempts per *problem*, not per submission.** Five failed
  submissions on one problem is one attempt — otherwise `solved / attempts` would measure
  stubbornness rather than proficiency.
- **Hibernate runs with `ddl-auto: validate`.** Any drift between entity mappings and the
  Flyway schema is a startup failure, which makes every integration test a mapping test too.

**MongoDB** *(Phase 7)*: `problem_statements`, `submission_sources`.
**Redis**: `leaderboard:global` sorted set, `ratelimit:submissions:*` token buckets.

### Seed data

Migrations `V2`–`V5` seed 30 tags with their prerequisite edges, 40 problems spanning ratings
800–2200, and three demo users with deliberately different skill profiles so the recommender
produces visibly different output for each:

| User | Password | Rating | Profile |
|---|---|---|---|
| `admin` | `Admin123!` | 2100 | ADMIN role |
| `alice` | `Password123!` | 1150 | strong on arrays/strings, bounces off dp and graphs |
| `bob` | `Password123!` | 1450 | broad coverage, repeatedly fails dp and shortest-path |
| `carol` | `Password123!` | 1750 | strong nearly everywhere, weak on geometry |

These are demo credentials for a throwaway local database — not secrets.

`user_tag_stats` is *derived* in SQL from the seeded submissions rather than hand-written, so
the counters cannot drift from the history that justifies them. A test re-derives them and
asserts zero difference.

---

## The recommendation algorithm

The centrepiece. Given a user, return the *N* problems they should attempt next — and be able
to say why.

The whole engine is **framework-free**: `RecommendationEngine`, `ProblemScorer`,
`PrerequisiteGate` and `TagProficiency` take plain values and know nothing about Spring, JPA or
HTTP. `RecommendationService` is the only place the algorithm and the database meet. That is
what makes it practical to test the scoring behaviour exhaustively instead of incidentally.

### 1. Tag-proficiency vector

For each topic, `proficiency = solved / (attempts + k)` with `k = 3`. The `+ k` is a Bayesian
pseudo-count: it stops "1 solved out of 1" outranking "18 solved out of 20".

Counts are carried through rather than a precomputed ratio, because **ranking and gating ask
different questions of the same data** — see the [gate](#3-prerequisite-gate).

### 2. Candidate pool

Unsolved problems with `rating ∈ [userRating − 100, userRating + 200]`, filtered in SQL so the
pool is small before any scoring happens. The band is asymmetric on purpose: growth comes from
stretching upwards, not from revisiting easier ground.

### 3. Prerequisite gate

A topic is blocked when a prerequisite was **demonstrably not learned**, or when a prerequisite
is itself blocked. Blocking propagates: fail `arrays` and `segment-tree` disappears too.

**Why a topological sort.** Readiness is transitive. Answering "is this topic ready?" per topic
means walking the ancestor chain each time — O(V·E), and awkward to terminate on a malformed
graph. Processing topics in dependency order means every prerequisite is already decided when a
topic is reached, so the entire readiness map falls out of **one O(V + E) pass** with no
recursion and no repeated work. Kahn's algorithm; a cycle degrades to unordered rather than
silently dropping topics.

**Demonstrably matters.** Two conditions are required: a raw success rate below the floor,
*and* enough attempts for that rate to mean anything. Absence of evidence is not evidence of
absence — and getting this wrong is not a subtle mis-ranking:

> The first version gated on the *smoothed* proficiency. That scores 1-solved-of-1-attempted at
> `1/(1+3) = 0.25`, below the 0.34 floor — so a **perfect record read as a failure**. On the
> root topic `implementation` that verdict cascaded to every descendant, and `bob` got **2**
> suggestions instead of 10. Smoothing is right for ranking and wrong for thresholding.

The gate also stands down entirely rather than returning an empty list: an empty panel is a
worse answer than a slightly-too-hard problem.

### 4. Scoring

```
score = w₁·tagWeakness + w₂·ratingFit + w₃·recency − w₄·repetitionPenalty
        0.45            0.35           0.10         0.30
```

Every term is normalised to **[0, 1] before weighting**. That is the point of the
normalisation: it makes the weights directly comparable, so "weakness matters more than
recency" is expressed by `0.45 > 0.10` rather than by an accident of units.

| Term | Shape | Why |
|---|---|---|
| `tagWeakness` | mean of `1 − proficiency` across the problem's topics | The **mean, not the minimum** — taking the weakest tag would score every multi-topic problem as maximally urgent the moment it touched one unfamiliar area |
| `ratingFit` | Gaussian centred at `userRating + 100`, σ = 120 | Peaks *above* the user: recommending what you can already do is comfortable and useless. Gaussian rather than linear because 30 points off target is nearly as good and 400 points off is the wrong problem — one function cannot express both linearly |
| `recency` | `0.5 ^ (ageDays / 180)` | Half-life, so an old problem is mildly less attractive rather than disqualified. Never reaches zero |
| `repetitionPenalty` | `n / (n + 2)` | Candidates are unsolved, so attempts are failures. Saturating, so the tenth failure does not drown out every other term |

All four components are returned in the API response. A recommender that cannot explain itself
is indistinguishable from a shuffle.

### 5. Top-N selection — the bounded min-heap

A `PriorityQueue` of capacity *K*, ordered by score **ascending**, so its root is always the
weakest of the best *K* seen so far. Each candidate is compared against that root in O(1) and
inserted — O(log K) — only if it would displace it.

```
sorting the pool    O(M log M) time, O(M) extra memory
bounded heap        O(M log K) time, O(K) extra memory
```

For the seeded catalogue of 40 problems the difference is unmeasurable. The reason to write it
this way is that **M grows with the catalogue while K stays fixed at about thirty** — the gap
widens exactly as it starts to matter, and the code does not need revisiting when it does.

The heap is an optimisation, and an optimisation that changes the answer is a bug — so a test
runs it against a plain full-sort reference over **100,000 random candidates** and asserts the
two produce identical output. Ties break on problem id so the result is a total order and the
same request always returns the same list.

### 6. Diversity cap

At most 2 results may share a topic, so the list is not five flavours of DP.

**Why over-fetch first.** A heap of exactly *N* yields the top *N* by score, and the cap then
removes some of them — leaving fewer than were asked for, with nothing to backfill from.
Keeping `K = N × 3` gives the cap alternatives to promote. It is still O(M log K); the factor
sits inside the logarithm.

Selection is greedy best-first rather than optimal. Choosing the highest-scoring set satisfying
all caps is a constrained selection problem; taking them in score order and skipping breaches
is O(K·t), explainable in one sentence ("the best ones, spread out"), and no user could tell
the difference. If the cap would starve the list, skipped candidates are added back in score
order — returning eight when ten were asked for, to honour a soft preference about variety,
would be the cap overruling the request.

### Complexity

With **M** candidates, **N** requested, **t** mean tags per problem, **K = N × overfetch**,
**V** topics and **E** prerequisite edges:

| Step | Cost |
|---|---|
| Load proficiency, DAG, solved ids, attempt counts | 4 queries, independent of M |
| Prerequisite gate (Kahn) | O(V + E) |
| Gate + score every candidate | O(M · t) |
| Top-K selection | **O(M log K)** |
| Diversify | O(K log K) |
| **Total** | **O(M log K)** |

Memory is O(K), not O(M).

### Try it

The three demo accounts were seeded with deliberately different histories, so comparing their
output shows the scoring responding to input rather than sorting the catalogue:

```bash
curl -s "http://localhost:8080/api/v1/recommendations/users/bob?limit=5" | jq '.[] | {slug: .problem.slug, score, reason}'
```

---

## Getting started

### Run the stack

```bash
cp .env.example .env
docker compose up -d --build
```

Then:

```bash
curl http://localhost:8080/actuator/health
```

Postgres is exposed on `localhost:5432` (`codearena` / `codearena`) so you can inspect the
seeded data directly.

Tear down, including the database volume:

```bash
docker compose down -v
```

### Local development

No local Maven or JDK install beyond Java 17 is required — the Maven Wrapper bootstraps
Maven itself.

```bash
./mvnw clean verify
```

---

## API

Interactive docs: **<http://localhost:8080/swagger-ui.html>** (spec at `/v3/api-docs`).

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `POST` | `/api/v1/auth/register` | — | Create a local account, returns a token pair |
| `POST` | `/api/v1/auth/login` | — | Exchange credentials for a token pair |
| `POST` | `/api/v1/auth/refresh` | refresh token | Rotate: consumes the token, returns a new pair |
| `POST` | `/api/v1/auth/logout` | refresh token | Revoke a refresh token (idempotent) |
| `GET` | `/api/v1/auth/me` | bearer | The caller's own profile |

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/v1/problems` | List/filter/search, paginated |
| `GET` | `/api/v1/problems/{slug}` | Problem detail |
| `GET` | `/api/v1/problems/{slug}/submissions` | Submissions against a problem |
| `POST` | `/api/v1/submissions` | Submit a solution → `201` + `Location` |
| `GET` | `/api/v1/submissions/{id}` | One submission |
| `GET` | `/api/v1/submissions/{id}/source` | Submitted source as `text/plain` |
| `GET` | `/api/v1/submissions/me` | Calling user's history |
| `GET` | `/api/v1/users/{username}` | Profile with per-topic proficiency |
| `GET` | `/api/v1/tags` | Topic taxonomy + prerequisite DAG |
| `GET` | `/api/v1/reports/tag-difficulty` | Aggregate report — **Spring JDBC, not JPA** |
| `POST`/`PUT`/`DELETE` | `/api/v1/admin/problems[/{slug}]` | Problem authoring |

```bash
curl -s "http://localhost:8080/api/v1/problems?tag=dp&difficulty=HARD&size=3"
```

```bash
curl -s "http://localhost:8080/api/v1/reports/tag-difficulty?sort=HARDEST&minProblems=2"
```

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' -d '{"usernameOrEmail":"bob","password":"Password123!"}' | python -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")
```

```bash
curl -s -X POST http://localhost:8080/api/v1/submissions -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"problemSlug":"edit-distance","language":"JAVA","sourceCode":"class Main {}"}'
```

### Design decisions worth calling out

- **Errors are RFC 7807** `application/problem+json` with a stable `type` URI clients can
  branch on, plus an `errors` array of field-level violations. Framework failures — unreadable
  bodies, type mismatches, unknown sort properties — are reshaped into the same envelope
  rather than falling through to Boot's default error body.
- **Difficulty is never accepted from a client.** It is derived from `rating` via
  `Difficulty.fromRating`, so a problem labelled `EASY` at rating 2200 is unrepresentable.
- **Pagination has its own envelope.** Serialising Spring's `Page` directly would publish
  `Pageable`/`Sort` internals as API contract — a shape Spring itself warns is unstable.
- **Page size is capped at 100.** Without it, `?size=1000000` is free denial-of-service.
- **Services return DTOs, not entities.** See the note on `open-in-view` under
  [Testing](#a-bug-the-tests-were-hiding).
- **`ORDER BY` in the JDBC report comes from an enum, never from the request.** A bind
  variable is only legal where a *value* is expected, so a sortable column has to be
  interpolated — which makes a whitelist the only safe way to do it.

### Why one endpoint uses `JdbcTemplate`

`/api/v1/reports/tag-difficulty` is four CTEs of set-level aggregation returning a shape that
maps to no entity. It uses `FILTER (WHERE ...)`, `bool_or` and `NULLS LAST` — none of which
JPQL has vocabulary for. This is the case where dropping to SQL is simpler, faster and more
honest than bending an ORM around it, and Spring JDBC gives that up without giving up
connection management or exception translation.

One detail it gets right: an untouched tag returns `null` rates rather than `0.0`. "Nobody has
tried this" and "everybody who tried failed" are different facts and should not render alike —
which means using `ResultSet#wasNull()`, since `getDouble` silently turns SQL `NULL` into zero.

---

## Security

Two filter chains, because the API and the browser login flow want opposite things:

| Chain | Matches | Session | CSRF | Unauthenticated response |
|---|---|---|---|---|
| API | `/api/**` | stateless | off | `401` + `application/problem+json` |
| Web | everything else | as needed | on | `401`, or the OAuth2 redirect |

CSRF is disabled **only** on the stateless chain. That is safe precisely because
authentication rides in the `Authorization` header rather than a cookie — a cross-site form
post carries no credentials to forge. The browser chain keeps CSRF protection, because the
OAuth2 `state` parameter genuinely needs a session behind it.

### Access rules

| Area | Rule |
|---|---|
| Problems, tags, profiles, reports (`GET`) | public — browsing needs no account |
| Submissions | authenticated |
| `/api/v1/admin/**` | `ADMIN` — enforced by *both* the path matcher and `@PreAuthorize` |
| `/actuator/health`, `/actuator/info`, Swagger | public |
| Other actuator endpoints | `ADMIN` |

The admin rule is deliberately doubled up. Path matchers are easy to break by accident — a new
controller mapped one level up, a matcher that stops matching after a refactor — and method
security fails closed regardless of how a request was routed.

### Tokens

**Access tokens are JWTs** (HS256, 15 minutes), so authorization needs no database round trip.
Signed with a key the app refuses to start without, and validated for signature, expiry *and*
issuer — so a token minted by another service sharing the key is still rejected.

**Refresh tokens are not JWTs.** They are 256 bits of `SecureRandom`, stored as a SHA-256
digest, valid for 7 days. The reasoning: the entire point of a refresh token is that it can be
revoked, and a signed JWT cannot be — once issued it is valid until it expires no matter what
the server later decides. A database row gives you logout, rotation and reuse detection; the
access token stays stateless precisely because it is short-lived enough that revocation doesn't
matter.

SHA-256 rather than BCrypt for the digest is also deliberate: BCrypt's work factor exists to
slow brute-forcing of low-entropy human passwords, and a 256-bit random token has nothing to
brute-force. This hash runs on every single refresh.

**Rotation with reuse detection.** Every refresh consumes the presented token and issues a new
one, linked back through `replaced_by`. Presenting an already-rotated token has only one
consistent explanation — it leaked — so every live session for that account is revoked. This
costs a legitimate user one re-login in the rare genuinely-concurrent case, which is the right
side of that trade.

> **A bug this caught:** the revocation originally ran in the same transaction as the exception
> that reports it — so throwing rolled the revocation straight back, leaving the *attacker's*
> newer token live. It only shows up in a test that checks the other token afterwards. Fixed by
> running the revocation in a `REQUIRES_NEW` transaction.

### Other decisions

- **Federated accounts are matched on the provider's `sub`, never on email.** Email addresses
  get reassigned and can change; matching on them would mean anyone who could get Google to
  issue a token for an address equal to an existing account's email would take that account
  over. A Google identity whose email already belongs to a local account is refused rather than
  silently linked.
- **The database enforces the password/provider correspondence.** `password_hash` is nullable,
  with a `CHECK` requiring it exactly when `auth_provider = 'LOCAL'`. "Federated user with a
  password nobody set" is unrepresentable, not merely unlikely.
- **The OAuth2 callback delivers the refresh token in an `HttpOnly` cookie**, not a URL
  parameter or fragment — both of those write a long-lived credential into browser history, and
  a query parameter additionally leaks through `Referer` and access logs.
- **Login does not distinguish "no such user" from "wrong password".** That difference is a free
  account-enumeration oracle. A test asserts the two responses are byte-identical.
- **Validation errors redact sensitive fields.** Echoing the rejected value is genuinely useful
  ("you sent 99999, the max is 4000") and is also how a rejected password ends up in a response
  body and every log that aggregates it. Fields matching `password`/`secret`/`token`/
  `credential` report no value, and long values are truncated.
- **Google login is optional.** With no credentials configured the application starts normally
  and simply has no Google button — Boot's own auto-configuration would instead fail at startup
  on an empty `client-id`, which is what happens when Compose passes the variable through
  unset.
- **Rate limiting is a token bucket, keyed by user.** A fixed window would let a caller spend
  the whole quota at the end of one window and again at the start of the next. Keyed by user id
  rather than IP, because IP punishes everyone behind one NAT and is trivially evaded — which
  is why it runs as an interceptor (after authentication) rather than a filter.

---

## Redis

Two uses, both chosen for something Redis does that PostgreSQL does not do cheaply.

### Leaderboard — a sorted set

PostgreSQL remains the source of truth; Redis holds the ranking, cache-aside. The win is not
"the query was slow" — with four users it was not:

- **`ZREVRANGE`** returns the top N in O(log N + M) without touching the other rows. The SQL
  equivalent orders the whole table on every page view.
- **`ZREVRANK`** answers *"what position am I?"* in O(log N). In SQL that is a window function
  over every user — the cost of finding one person's rank is the cost of ranking everybody.

Redis stores only the ranking. Solve counts come from PostgreSQL for the handful of users
actually on screen, because caching them too would mean two copies of a mutable number, free to
disagree. Any Redis failure falls back to SQL and logs a warning: a ranking is a feature of the
page, not a precondition for it.

### Rate limiting — a token bucket in Lua

The same token-bucket algorithm as the in-process limiter it replaces, because swapping where
state lives should not change how the limiter behaves. What changes is that the limit now counts
once for the whole deployment rather than once per JVM — an in-process limiter behind two
replicas permits twice the configured rate.

The refill-and-consume step is a **Lua script**, not a sequence of commands. Separate GET/SET
round trips would let two concurrent requests read the same token count and both spend it, so
the limit would leak under exactly the load it exists to control. A script is Redis's unit of
atomicity.

Time comes from `redis.call('TIME')` rather than from the application. With several replicas the
callers' clocks disagree, and a bucket refilled against a fast clock hands out free tokens; the
server is the one clock every replica already shares.

**Fail-open on Redis outage**, deliberately. This limiter protects the judge queue from
enthusiasm, not the application from attack — turning every submission into a 429 during a cache
outage would convert a degraded cache into a total outage of the product's main action. A
limiter guarding authentication or payment would warrant the opposite choice.

---

## The judging pipeline

Submitting returns in milliseconds; judging happens somewhere else entirely.

```
POST /api/v1/submissions
        │ insert row (QUEUED), commit
        ▼
  arena.submissions ──────────────▶ arena-judge
   (keyed by submission id)          20 test cases on a thread pool
        ▲                                    │
        │                                    ▼
   arena-api ◀────────────────────── arena.verdicts
   apply verdict, update tag stats,
   award Elo, push SSE ──────────▶ browser updates in place
```

### Ordering and delivery

Both topics are **keyed by submission id**. Every message about one submission therefore lands
on one partition and is processed in order by a single consumer — without that, two workers
could judge the same submission concurrently and race to write conflicting verdicts.

Delivery is **at-least-once**: the listener processes a record to completion before returning,
so the offset is committed after the work is durable rather than before it starts. The cost of
that choice is duplicates, which is why `VerdictService.apply` is idempotent — a redelivered
message would otherwise award the rating and the tag counters twice. There is a test for exactly
that, because it is the failure that would never show up in manual testing.

### Three ordering hazards, and what was done about them

- **Publishing inside the transaction would race the commit.** The judge is fast enough to
  consume, judge and publish a verdict before the API's own transaction commits — so the verdict
  listener would look for a row that does not exist yet and drop it, leaving the submission
  QUEUED for ever. The event is published on `afterCommit` instead. The residual risk is the
  other direction (committed but not published); the honest fix is a transactional outbox, which
  is noted in the code rather than pretended away.
- **A thread pool per record would break at-least-once.** Handing each record to an executor and
  returning immediately lets Kafka commit the offset while the work is still running, so a crash
  mid-judge loses the submission silently. Parallelism across submissions comes from consuming
  multiple partitions; the thread pool inside the judge overlaps the *test cases* of one
  submission, which is independent work with no delivery guarantee riding on it.
- **The default error handler retries for ever.** One unprocessable record and the partition
  never advances — every submission behind it stuck, with nothing but a repeating log line.
  Both consumers retry twice then move on, and treat a deserialization failure as immediately
  fatal since it will never succeed.

### Why the source code travels in the event

The better design sends only a reference and lets the worker fetch the code from shared storage.
There is no such storage yet — the source lives in the API's own process until Phase 7 moves it
to MongoDB — so it travels in the payload, bounded by the 64 KiB cap the submission endpoint
already enforces, comfortably inside Kafka's 1 MB default.

### Judging is simulated, deterministically

Compiling and running untrusted code needs a real sandbox — containers, seccomp, cgroups — which
is a project in itself and not what this one demonstrates. What *is* real is everything around
it: the queue, the worker, the ordering guarantees, the write-back, the live update.

The outcome is a pure function of the submission's content: no `Random`, no clock. That is what
lets an end-to-end test assert an exact verdict instead of retrying until it sees one, and it
means resubmitting identical code gives the same answer — which is what anyone would expect of a
judge. Obvious tells are checked before the hash, so `while (true)` reliably times out and an
empty body reliably fails to compile.

### Live updates over SSE

One-directional, low-volume traffic: the server has something to say, the browser has nothing to
send back. SSE is plain HTTP, reconnects by itself, and needs no proxy configuration — a duplex
protocol would be more machinery for a smaller problem.

**Known limitation, stated rather than hidden:** emitters live in one JVM's heap, so with several
API replicas a verdict consumed by one instance cannot reach a browser connected to another. The
page falls back to a slow poll, and the real fix is fanning verdicts out over Redis pub/sub.

### What a verdict updates

Applying a verdict is where the recommender's inputs are maintained. If `user_tag_stats` stopped
being updated here, the engine would quietly degrade into "sort the catalogue by rating".

- The submission's status, verdict and runtime.
- `user_tag_stats`, per **problem** rather than per submission — the third failed attempt at one
  problem is not a third attempt at the topic. One `INSERT ... ON CONFLICT` covers every tag of
  the problem.
- The user's rating, on a **first solve only**, by Elo against the problem's rating: a hard
  problem is worth more than an easy one. Failures cost nothing on purpose — a practice platform
  that punishes attempting hard problems trains people to avoid them.
- The Redis leaderboard entry.

---

## Testing

| Command | What runs | Needs Docker |
|---|---|---|
| `./mvnw test` | unit and web-slice tests (`*Test`) — 170 tests | no |
| `./mvnw verify` | unit **and** integration tests (`*IT`) — 318 tests | yes |

Integration tests use Testcontainers against a real PostgreSQL 16 image — never H2 — so
migrations, `CHECK` constraints, `FULL OUTER JOIN` and Postgres-specific SQL are all exercised
as they run in production. A single container is shared across the whole suite via the
singleton-container pattern rather than started per test class.

Three layers, each testing something the others cannot:

| Layer | Style | What it proves |
|---|---|---|
| Unit | plain JUnit + Mockito | business rules — difficulty derivation, tag resolution, proficiency smoothing, LRU eviction |
| Algorithm | plain JUnit, no Spring at all | the recommendation engine: scoring shape, gating, heap equivalence at 100k candidates |
| Web slice | `@WebMvcTest`, mocked services | the HTTP contract — status codes, JSON shape, error envelope |
| UI slice | `@WebMvcTest` rendering real Thymeleaf | that every page renders, forms carry a CSRF token, and output is escaped |
| Full stack | `@SpringBootTest` + Testcontainers | the Specification SQL, entity graphs, DB constraints, the real security chain, real session semantics |

Authorization tests are deliberately weighted towards the **negative** cases. A test proving an
admin can create a problem says nothing about whether everyone else can too, and that second
question is the one that matters.

### A bug the tests hid, and one they caused

Two failures from this project worth keeping, because neither was a typo.

**The engine returned 2 suggestions instead of 10.** The prerequisite gate judged mastery using
the *smoothed* proficiency, which scores 1-solved-of-1-attempted at 0.25 — below the 0.34 floor.
A perfect record read as a failure, and on the root topic `implementation` that verdict cascaded
through the entire taxonomy. The fix was recognising that ranking and gating are different
questions: ranking wants smoothing, gating wants the raw ratio plus a minimum-evidence
requirement. The regression test is named after the symptom.

**A test-infrastructure hiccup destroyed the fixtures.** `AbstractApiIT` undoes each test's
writes by deleting rows above an id watermark recorded in `@BeforeEach`. JUnit runs `@AfterEach`
even when `@BeforeEach` throws — so when a Redis connection failed during setup, the watermark
stayed at its default of zero and `DELETE ... WHERE id > 0` emptied the seeded tables that every
other test depends on. One unrelated failure took out sixteen tests.

Both fixes are small and both are the kind that only exist once you have seen the failure: the
watermark now starts at a sentinel that cleanup refuses to act on, and it is recorded before
anything that can fail.

### A bug the tests were hiding

The API integration tests were originally `@Transactional`, the usual way to get rollback
isolation. They all passed. The API then returned **HTTP 500** on `GET /api/v1/problems` the
first time it was exercised against the running container.

The cause: `spring.jpa.open-in-view` is disabled, so the persistence session closes when the
service method returns. Mapping an entity's lazy `tags` collection in the controller therefore
throws `LazyInitializationException` — except in a test, where the test's own transaction keeps
a session open for the whole request and the lazy load quietly succeeds.

Two changes came out of it:

1. **Services return DTOs, not entities.** Mapping happens inside the transaction, so the
   transaction boundary and the "what is loaded" boundary are the same line of code.
2. **`AbstractApiIT` is no longer `@Transactional`.** The tests now run with production session
   semantics and undo their own writes by id watermark instead. The trade-off is that a test
   must create whatever it intends to mutate rather than editing seeded rows — which is worth
   it to stop the suite lying about what production does.

What the suite covers:

- every Flyway migration applies successfully, in order
- seeded catalogue invariants: every problem tagged, every tag used, difficulty label agrees
  with numeric rating
- `user_tag_stats` re-derived from submissions matches the stored rows exactly
- the `tag_prerequisites` graph is acyclic — Kahn's algorithm must consume every node
- entity-graph fetching, pagination, composite keys and JPA auditing round-trip correctly
- database-level uniqueness is genuinely enforced, not just checked in Java
- proficiency smoothing behaviour, including the `0/0` case that would otherwise be `NaN`
- LIKE wildcards in a search term are escaped — searching `%` matches nothing, not everything
- the page-size cap is applied, and an unknown `?sort=` property is a 400 rather than a 500
- the reporting SQL's null semantics: untouched tags report `null`, not `0.0`
- `?sort=DROP TABLE users` is rejected before it can reach the query
- the full token lifecycle against real signing and decoding: login → bearer call → rotate →
  replay the rotated token → every session dies
- a forged signature, a foreign issuer and an expired token are each rejected
- registration never echoes the password back, in success *or* validation responses
- a refused admin write genuinely did not happen — asserted by reading the row back
- the bounded heap returns **exactly** what a full sort would, over 100,000 random candidates
- the prerequisite gate stays inert for a newcomer and sharp for a user with real history
- the diversity cap holds, and backfills rather than silently returning fewer than requested
- Redis sorted-set ranking and the Lua token bucket, against a real Redis rather than a mock
- the pipeline in both directions against a real broker: what the API publishes is what the
  judge expects, and a published verdict is applied to the row
- a duplicate verdict changes nothing — the failure at-least-once delivery guarantees you
- an orphan verdict does not stall the partition; the consumer survives and handles the next
- judging is deterministic, so an end-to-end test can assert an exact verdict
- every Thymeleaf page actually renders: the UI slices run the template engine and assert
  against the produced HTML, so a fragment typo fails the build rather than the demo
- submitted source is escaped, not interpreted — `<script>` comes back as `&lt;script&gt;`

---

## Web UI

Server-rendered Thymeleaf, reachable at <http://localhost:8080/>.

| Page | Path |
|---|---|
| Home / dashboard | `/` |
| Problem catalogue with filters | `/problems` |
| Problem detail + code editor | `/problems/{slug}` |
| Submission history / detail | `/submissions`, `/submissions/{id}` |
| Profile with charts | `/users/{username}`, `/me` |
| Leaderboard | `/leaderboard` |
| Admin problem CRUD | `/admin/problems` |

### Two authentication mechanisms, on purpose

`/api/**` is a **stateless bearer-token** chain; everything else is a **session** chain with
form login. That is not indecision — a server-rendered page has nowhere to keep a JWT that
JavaScript cannot also read, so using tokens for the UI would trade a `HttpOnly` session cookie
for an XSS-readable credential. The session chain keeps CSRF protection on precisely because
authentication rides on a cookie; the API chain disables it precisely because it does not.

Both chains resolve identity through the same `CurrentUserProvider`, so services never know
which one a request came through.

### Front-end assets are WebJars, not CDN links

Bootstrap, CodeMirror and Chart.js are Maven dependencies served from the jar. The container
renders correctly with no outbound network access, and no third party sits inside the page's
trust boundary. Versions live in the parent pom and appear in exactly one template.

### Notes worth calling out

- **The editor is progressive enhancement.** CodeMirror upgrades a real `<textarea>`; with
  JavaScript disabled the form still submits the code rather than silently posting nothing.
- **Chart data is serialised to JSON server-side**, then handed to `th:inline="javascript"` as
  a single string. Building JS array literals out of Thymeleaf loops is how XSS gets into a
  page — one tag containing a quote breaks out of the string.
- **Form-backing beans are mutable classes, not the API's records.** `th:field` has to write
  back into the object to re-populate a form after a validation failure, and a record cannot be
  written to.
- **Delete is a POST form, never a link.** A `GET` delete is one crawler away from data loss.
- **A rejected password is never re-rendered** into the registration form.

### Two bugs that only surfaced in the browser

Both passed the test suite and failed the moment the flow was walked by hand:

1. **Another user's submission returned 500, not 403.** The controller throws
   `AccessDeniedException`, and the UI advice's catch-all `Exception` handler swallowed it —
   reporting "the server broke" for what was really "not yours", and logging it at `ERROR` on
   every probe. Fixed with an explicit handler; Spring resolves by exception-type distance, not
   declaration order.
2. **A CSRF failure on a form POST returned 405.** `accessDeniedPage` *forwards*, and a forward
   preserves the request method, so the POST arrived at a `@GetMapping`-only handler. The user
   saw "Method Not Allowed" for what was really an expired session.

Both now have regression tests naming the symptom.

### Note on the IDE and `target/`

If a build fails with a `ClassNotFoundException` for a class that is plainly in `target/classes`,
or a bean whose implementation is right there "not qualifying as an autowire candidate", check
whether an IDE is compiling the same module. VS Code's Java extension writes Eclipse-compiled
classes into `target/` too, and a background rebuild can overwrite Maven's output mid-build,
leaving class files that contain `Unresolved compilation problems` and fail at *runtime*.

The build takes a directory override for exactly this:

```bash
./mvnw -Darena.build.directory=target-cli clean verify
```

### Note on Docker API versions

Testcontainers reaches the daemon through docker-java, which still negotiates Docker API
**1.32** by default. Docker Engine 25+ advertises a minimum API of **1.40** and replies to
anything older with a bare `HTTP 400` — which Testcontainers reports as the thoroughly
unhelpful *"Could not find a valid Docker environment"*, even though the daemon is running and
the CLI works fine.

docker-java reads this as a **system property**, not an environment variable, so
`DOCKER_API_VERSION` has no effect. The root `pom.xml` hands it to the forked test JVM instead:

```xml
<argLine>-Dapi.version=${docker.api.version}</argLine>
```

Override with `-Ddocker.api.version=…` if your engine needs something different.

---

## Project structure

```
.
├── arena-common/          shared enums and Kafka event contracts
├── arena-api/             web + REST + persistence
│   ├── src/main/java/com/codearena/api/
│   │   ├── config/        OpenAPI, JPA auditing
│   │   ├── domain/        JPA entities
│   │   ├── reporting/     Spring JDBC reporting DAO
│   │   ├── repository/    Spring Data repositories + Specifications
│   │   ├── service/       business logic, transaction boundaries
│   │   └── web/           controllers, DTOs, MapStruct mappers, error handling
│   └── src/main/resources/db/migration/   Flyway V1..V5
├── arena-judge/           Kafka worker (Phase 6)
├── arena-ai/              Spring AI service (Phase 7)
├── docs/screenshots/      README imagery
├── docker-compose.yml
└── pom.xml
```

---

## Roadmap

| Phase | Scope | Status |
|---|---|---|
| 1 | Multi-module scaffold, entities, migrations, seed data, repository tests | ✅ done |
| 2 | REST API, DTOs, validation, RFC 7807 errors, OpenAPI, `JdbcTemplate` report | ✅ done |
| 3 | Spring Security, JWT access/refresh, OAuth2 Google, rate limiting | ✅ done |
| 4 | Thymeleaf UI: Bootstrap, CodeMirror editor, Chart.js progress charts | ✅ done |
| 5 | Recommendation engine, Redis leaderboard | ✅ done |
| 6 | Kafka pipeline, `arena-judge` worker, SSE live verdicts | ✅ done |
| 7 | MongoDB statements/sources, `arena-ai` hints and complexity analysis | ⬜ |
| 8 | Full compose stack, GitHub Actions, docs, screenshots | ⬜ |

---

## License

[MIT](LICENSE)
